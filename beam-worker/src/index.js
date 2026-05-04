/**
 * Beam Cloud Worker — Cloudflare Workers
 *
 * Storage backend: Gofile.io (free, anonymous, unlimited size, no credit card).
 * The browser uploads directly to Gofile — the Worker never touches file bytes.
 * Worker only stores a single-use download token in KV.
 *
 * Routes:
 *   POST /register          browser uploads to Gofile, then registers the fileId +
 *                           guestToken here. Returns { ok, token, expiresAt }.
 *   GET  /download/:token   validates token, fetches Gofile directLink, streams
 *                           file to client, deletes from Gofile + KV.
 *   GET  /health            liveness check.
 *
 * Scheduled (cron "* * * * *"):
 *   Scans KV for expired entries, deletes Gofile files, purges KV.
 *
 * Bindings:
 *   BEAM_EXPIRY          KV namespace  (no R2, no credit card needed)
 *   BEAM_ALLOWED_ORIGIN  CORS origin secret
 */

// ─── CORS ────────────────────────────────────────────────────────────────────

function corsHeaders(origin) {
  return {
    'Access-Control-Allow-Origin':  origin || '*',
    'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type',
    'Access-Control-Max-Age':       '86400',
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
    `<!doctype html><html><head><meta charset="utf-8"><title>${title}</title>
    <style>body{font-family:system-ui;text-align:center;padding:60px 20px;
    background:#06060f;color:#f1f5f9}h2{font-size:1.4rem;margin-bottom:12px}
    p{color:#94a3b8}</style></head>
    <body><h2>${title}</h2><p>${body}</p></body></html>`,
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

// ─── Gofile helpers ───────────────────────────────────────────────────────────

/**
 * Delete a file from Gofile using the guestToken returned at upload time.
 * Fire-and-forget — errors are logged but not fatal.
 */
async function gofileDelete(fileId, guestToken) {
  if (!fileId || !guestToken) return;
  try {
    await fetch(`https://api.gofile.io/contents/${fileId}`, {
      method:  'DELETE',
      headers: { Authorization: `Bearer ${guestToken}` },
    });
  } catch (err) {
    console.error('[beam-worker] Gofile delete error:', err.message);
  }
}

/**
 * Get the direct download URL for a Gofile file.
 * Uses the guestToken (obtained at upload) for authenticated access.
 */
async function gofileDirectLink(fileId, guestToken) {
  const res = await fetch(
    `https://api.gofile.io/contents/${fileId}?token=${guestToken}`,
  );
  if (!res.ok) throw new Error(`Gofile content API HTTP ${res.status}`);
  const data = await res.json();
  if (data.status !== 'ok') throw new Error(`Gofile API error: ${data.status}`);
  const link = data.data?.directLink || data.data?.link;
  if (!link) throw new Error('Gofile returned no download URL');
  return link;
}

// ─── POST /register ───────────────────────────────────────────────────────────
// Called after the browser has uploaded the file to Gofile directly.
// Body: { fileId, guestToken, filename, size, mimeType?, expiryMinutes? }
// Returns: { ok, token, expiresAt, expiryMinutes }

async function handleRegister(request, env, origin) {
  let body;
  try { body = await request.json(); } catch {
    return json({ error: 'Invalid JSON body.' }, 400, origin);
  }

  const { fileId, guestToken, filename, size, mimeType, expiryMinutes: raw } = body;

  if (!fileId   || typeof fileId   !== 'string') return json({ error: 'fileId required.'   }, 400, origin);
  if (!guestToken || typeof guestToken !== 'string') return json({ error: 'guestToken required.' }, 400, origin);
  if (!filename || typeof filename !== 'string') return json({ error: 'filename required.' }, 400, origin);

  const expiryMinutes = Math.min(Math.max(parseInt(raw, 10) || 30, 5), 1440);
  const token         = generateToken();
  const expiresAt     = Date.now() + expiryMinutes * 60 * 1000;

  await env.BEAM_EXPIRY.put(
    `dl:${token}`,
    JSON.stringify({ fileId, guestToken, filename, size: size || 0, mimeType: mimeType || '', expiresAt }),
    { expirationTtl: expiryMinutes * 60 },
  );

  return json({ ok: true, token, expiresAt, expiryMinutes }, 200, origin);
}

// ─── GET /download/:token ─────────────────────────────────────────────────────
// Validates token, fetches file from Gofile, streams to browser, then cleans up.

async function handleDownload(token, env, origin, ctx) {
  if (!token || token.length < 10) {
    return htmlError('Invalid link', 'This download link is not valid.', 400, origin);
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
  try { meta = JSON.parse(raw); } catch {
    return json({ error: 'Corrupted token.' }, 500, origin);
  }

  if (Date.now() > meta.expiresAt) {
    await env.BEAM_EXPIRY.delete(`dl:${token}`);
    ctx.waitUntil(gofileDelete(meta.fileId, meta.guestToken));
    return htmlError(
      '⏰ Link expired',
      'This link has passed its expiry time. Ask the sender to re-upload.',
      410, origin,
    );
  }

  // Get the Gofile direct download URL
  let directLink;
  try {
    directLink = await gofileDirectLink(meta.fileId, meta.guestToken);
  } catch (err) {
    console.error('[beam-worker] Gofile link error:', err.message);
    return htmlError(
      'File unavailable',
      'The file could not be retrieved from cloud storage. It may have expired.',
      502, origin,
    );
  }

  // Delete KV immediately — single-use enforced from here
  await env.BEAM_EXPIRY.delete(`dl:${token}`);

  // Fetch from Gofile
  let goRes;
  try {
    goRes = await fetch(directLink);
    if (!goRes.ok) throw new Error(`Gofile HTTP ${goRes.status}`);
  } catch (err) {
    console.error('[beam-worker] Gofile fetch error:', err.message);
    return htmlError('Download failed', 'Could not fetch file from cloud storage.', 502, origin);
  }

  // Pipe Gofile → client; delete file from Gofile after stream completes
  const { readable, writable } = new TransformStream();
  const pipeAndDelete = goRes.body
    .pipeTo(writable)
    .then(()  => gofileDelete(meta.fileId, meta.guestToken))
    .catch(err => console.error('[beam-worker] stream error:', err.message));
  ctx.waitUntil(pipeAndDelete);

  const contentType = meta.mimeType || 'application/octet-stream';
  const disposition = `attachment; filename="${encodeURIComponent(meta.filename)}"`;

  return new Response(readable, {
    headers: {
      'Content-Type':        contentType,
      'Content-Disposition': disposition,
      'Content-Length':      String(meta.size),
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
          gofileDelete(meta.fileId, meta.guestToken),
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

  if (url.pathname === '/register' && request.method === 'POST') {
    return handleRegister(request, env, origin);
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
