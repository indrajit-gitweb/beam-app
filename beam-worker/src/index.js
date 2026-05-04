/**
 * Beam Cloud Worker — Cloudflare Workers + R2
 *
 * Upload flow (chunked multipart — no size limit):
 *   POST /upload/init        browser starts upload; returns { key, uploadId }
 *   POST /upload/part        browser sends one 50 MB chunk; returns { partNumber, etag }
 *   POST /upload/complete    browser signals done; Worker seals R2 multipart,
 *                            stores token in KV; returns { ok, token, expiresAt }
 *
 * Download flow:
 *   GET  /download/:token    validate token → delete KV → stream R2 object to browser
 *                            → delete R2 object after stream (ctx.waitUntil)
 *
 * Liveness:
 *   GET  /health             { ok, ts }
 *
 * Scheduled (cron "* * * * *"):
 *   Scans KV for expired dl:* entries, deletes R2 objects + KV keys.
 *
 * Bindings (wrangler.toml + secrets):
 *   BEAM_EXPIRY          KV namespace
 *   BEAM_BUCKET          R2 bucket
 *   BEAM_ALLOWED_ORIGIN  CORS origin (wrangler secret put BEAM_ALLOWED_ORIGIN)
 */

// ─── CORS ────────────────────────────────────────────────────────────────────

function corsHeaders(origin) {
  return {
    'Access-Control-Allow-Origin':  origin || '*',
    'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
    'Access-Control-Allow-Headers':
      'Content-Type, X-Beam-Key, X-Beam-Upload-Id, X-Beam-Part-Number, ' +
      'X-Beam-Expiry-Minutes, X-Beam-Filename',
    'Access-Control-Max-Age': '86400',
  };
}

function json(data, status = 200, origin) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...corsHeaders(origin) },
  });
}

function htmlError(title, body, status, origin) {
  return new Response(
    `<!doctype html><html><head><meta charset="utf-8">
    <title>${title}</title>
    <style>body{font-family:system-ui;text-align:center;padding:60px 20px;background:#06060f;color:#f1f5f9}
    h2{font-size:1.4rem;margin-bottom:12px}p{color:#94a3b8;font-size:.9rem}</style>
    </head><body><h2>${title}</h2><p>${body}</p></body></html>`,
    { status, headers: { 'Content-Type': 'text/html;charset=utf-8', ...corsHeaders(origin) } },
  );
}

// ─── Token ───────────────────────────────────────────────────────────────────

function generateToken() {
  const arr = new Uint8Array(18);
  crypto.getRandomValues(arr);
  return btoa(String.fromCharCode(...arr))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

// ─── POST /upload/init ────────────────────────────────────────────────────────
// Body: { filename, size, type }
// Returns: { key, uploadId }

async function handleUploadInit(request, env, origin) {
  let body;
  try { body = await request.json(); } catch {
    return json({ error: 'Invalid JSON.' }, 400, origin);
  }

  const { filename, size, type } = body;
  if (!filename || typeof filename !== 'string') {
    return json({ error: 'filename required.' }, 400, origin);
  }

  // Unique key: timestamp + random suffix + sanitised filename
  const rand = Math.random().toString(36).slice(2, 8);
  const safe = filename.replace(/[^a-zA-Z0-9._-]/g, '_').slice(0, 100);
  const key  = `uploads/${Date.now()}-${rand}-${safe}`;

  const multipart = await env.BEAM_BUCKET.createMultipartUpload(key, {
    httpMetadata: {
      contentType:        type || 'application/octet-stream',
      contentDisposition: `attachment; filename="${encodeURIComponent(filename)}"`,
    },
    customMetadata: {
      originalName: filename,
      fileSize:     String(size || 0),
    },
  });

  return json({ key, uploadId: multipart.uploadId }, 200, origin);
}

// ─── POST /upload/part ────────────────────────────────────────────────────────
// Headers: X-Beam-Key, X-Beam-Upload-Id, X-Beam-Part-Number
// Body: raw chunk bytes (≤ 50 MB)
// Returns: { partNumber, etag }

async function handleUploadPart(request, env, origin) {
  const key        = request.headers.get('X-Beam-Key');
  const uploadId   = request.headers.get('X-Beam-Upload-Id');
  const partNumber = parseInt(request.headers.get('X-Beam-Part-Number') || '0', 10);

  if (!key || !uploadId || !partNumber || partNumber < 1 || partNumber > 10000) {
    return json({ error: 'Invalid part headers.' }, 400, origin);
  }

  const multipart = env.BEAM_BUCKET.resumeMultipartUpload(key, uploadId);

  let uploaded;
  try {
    // Pass request.body (ReadableStream) directly — no buffering needed
    uploaded = await multipart.uploadPart(partNumber, request.body);
  } catch (err) {
    console.error('[beam-worker] part upload error:', err.message);
    return json({ error: `Part upload failed: ${err.message}` }, 500, origin);
  }

  return json({ partNumber: uploaded.partNumber, etag: uploaded.etag }, 200, origin);
}

// ─── POST /upload/complete ────────────────────────────────────────────────────
// Body: { key, uploadId, parts: [{partNumber, etag}], filename, size, expiryMinutes }
// Returns: { ok, token, expiresAt, expiryMinutes }

async function handleUploadComplete(request, env, origin) {
  let body;
  try { body = await request.json(); } catch {
    return json({ error: 'Invalid JSON.' }, 400, origin);
  }

  const { key, uploadId, parts, filename, size, expiryMinutes: rawExpiry } = body;

  if (!key || !uploadId || !Array.isArray(parts) || parts.length === 0) {
    return json({ error: 'key, uploadId and parts[] required.' }, 400, origin);
  }

  const expiryMinutes = Math.min(Math.max(parseInt(rawExpiry, 10) || 30, 5), 1440);

  // Complete the multipart upload in R2
  const multipart = env.BEAM_BUCKET.resumeMultipartUpload(key, uploadId);
  try {
    await multipart.complete(parts); // parts: [{partNumber, etag}]
  } catch (err) {
    console.error('[beam-worker] complete error:', err.message);
    return json({ error: `Upload complete failed: ${err.message}` }, 500, origin);
  }

  // Store download token in KV
  const token     = generateToken();
  const expiresAt = Date.now() + expiryMinutes * 60 * 1000;

  await env.BEAM_EXPIRY.put(
    `dl:${token}`,
    JSON.stringify({ r2Key: key, filename: filename || 'file', size: size || 0, expiresAt }),
    { expirationTtl: expiryMinutes * 60 },
  );

  return json({ ok: true, token, expiresAt, expiryMinutes }, 200, origin);
}

// ─── GET /download/:token ─────────────────────────────────────────────────────
// Validates token, streams R2 object to client, deletes token + R2 object.

async function handleDownload(token, env, origin, ctx) {
  if (!token || token.length < 10) {
    return htmlError('Invalid link', 'This download link is invalid.', 400, origin);
  }

  const raw = await env.BEAM_EXPIRY.get(`dl:${token}`);
  if (!raw) {
    return htmlError(
      '⏰ Link expired',
      'This file has already been downloaded or the link has expired.',
      410, origin,
    );
  }

  let meta;
  try { meta = JSON.parse(raw); }
  catch { return json({ error: 'Corrupted token.' }, 500, origin); }

  if (Date.now() > meta.expiresAt) {
    await env.BEAM_EXPIRY.delete(`dl:${token}`);
    ctx.waitUntil(env.BEAM_BUCKET.delete(meta.r2Key));
    return htmlError(
      '⏰ Link expired',
      'This link has passed its expiry time. Ask the sender to re-upload.',
      410, origin,
    );
  }

  const obj = await env.BEAM_BUCKET.get(meta.r2Key);
  if (!obj) {
    await env.BEAM_EXPIRY.delete(`dl:${token}`);
    return htmlError('File not found', 'The file was not found in storage.', 404, origin);
  }

  // Delete KV immediately — single-use enforced from this point
  await env.BEAM_EXPIRY.delete(`dl:${token}`);

  // Stream R2 → client, delete R2 object after stream completes
  const { readable, writable } = new TransformStream();
  const pipeAndDelete = obj.body.pipeTo(writable)
    .then(() => env.BEAM_BUCKET.delete(meta.r2Key))
    .catch(err => console.error('[beam-worker] stream/delete error:', err.message));
  ctx.waitUntil(pipeAndDelete);

  const contentType = obj.httpMetadata?.contentType || 'application/octet-stream';
  const disposition = obj.httpMetadata?.contentDisposition
    || `attachment; filename="${encodeURIComponent(meta.filename)}"`;

  return new Response(readable, {
    headers: {
      'Content-Type':        contentType,
      'Content-Disposition': disposition,
      'Content-Length':      String(obj.size),
      'Cache-Control':       'no-store',
      ...corsHeaders(origin),
    },
  });
}

// ─── Scheduled cleanup ────────────────────────────────────────────────────────

async function handleScheduled(env) {
  let cursor;
  const now = Date.now();

  do {
    const list = await env.BEAM_EXPIRY.list({ prefix: 'dl:', cursor });
    cursor = list.cursor;

    for (const key of list.keys) {
      const raw = await env.BEAM_EXPIRY.get(key.name);
      if (!raw) continue;
      let meta;
      try { meta = JSON.parse(raw); } catch { continue; }

      if (now > meta.expiresAt) {
        console.log(`[beam-worker] cron: expiring ${key.name} (${meta.filename})`);
        await Promise.allSettled([
          env.BEAM_BUCKET.delete(meta.r2Key),
          env.BEAM_EXPIRY.delete(key.name),
        ]);
      }
    }
  } while (cursor);
}

// ─── Router ───────────────────────────────────────────────────────────────────

async function handleRequest(request, env, ctx) {
  const origin = env.BEAM_ALLOWED_ORIGIN || '*';
  const url    = new URL(request.url);

  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders(origin) });
  }

  if (url.pathname === '/health' && request.method === 'GET') {
    return json({ ok: true, ts: Date.now() }, 200, origin);
  }

  if (url.pathname === '/upload/init' && request.method === 'POST') {
    return handleUploadInit(request, env, origin);
  }

  if (url.pathname === '/upload/part' && request.method === 'POST') {
    return handleUploadPart(request, env, origin);
  }

  if (url.pathname === '/upload/complete' && request.method === 'POST') {
    return handleUploadComplete(request, env, origin);
  }

  const dlMatch = url.pathname.match(/^\/download\/([A-Za-z0-9\-_]{10,})$/);
  if (dlMatch && request.method === 'GET') {
    return handleDownload(dlMatch[1], env, origin, ctx);
  }

  return json({ error: 'Not found.' }, 404, origin);
}

// ─── Entry point ─────────────────────────────────────────────────────────────

export default {
  async fetch(request, env, ctx) {
    return handleRequest(request, env, ctx);
  },

  async scheduled(_event, env) {
    await handleScheduled(env);
  },
};
