/**
 * Beam Cloud Worker — Cloudflare Workers
 *
 * Storage backend: Filebin.net (free, anonymous, no size limit, no credit card).
 * The browser uploads directly to Filebin — the Worker never touches file bytes.
 * Worker stores a single-use download token in KV.
 *
 * Routes:
 *   POST /register          browser uploads to Filebin, then registers binId +
 *                           filename here. Returns { ok, token, expiresAt }.
 *   GET  /download/:token   validates token, fetches file from Filebin, streams
 *                           to client, deletes from Filebin + KV.
 *   GET  /health            liveness check.
 *
 * Scheduled (cron "* * * * *"):
 *   Scans KV for expired entries, deletes Filebin files, purges KV.
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

// ─── Filebin helpers ──────────────────────────────────────────────────────────

/**
 * Delete a specific file from Filebin.
 * Fire-and-forget — errors are logged but not fatal.
 */
async function filebinDelete(binId, filename) {
  if (!binId || !filename) return;
  try {
    await fetch(
      `https://filebin.net/${encodeURIComponent(binId)}/${encodeURIComponent(filename)}`,
      { method: 'DELETE' },
    );
  } catch (err) {
    console.error('[beam-worker] filebin delete error:', err.message);
  }
}

// ─── POST /register ───────────────────────────────────────────────────────────
// Called after the browser has uploaded the file to Filebin directly.
// Body: { binId, filename, size, mimeType?, expiryMinutes? }
// Returns: { ok, token, expiresAt, expiryMinutes }

async function handleRegister(request, env, origin) {
  let body;
  try { body = await request.json(); } catch {
    return json({ error: 'Invalid JSON body.' }, 400, origin);
  }

  const { binId, filename, size, mimeType, expiryMinutes: raw } = body;

  if (!binId    || typeof binId    !== 'string') return json({ error: 'binId required.'    }, 400, origin);
  if (!filename || typeof filename !== 'string') return json({ error: 'filename required.' }, 400, origin);

  const expiryMinutes = Math.min(Math.max(parseInt(raw, 10) || 30, 5), 1440);
  const token         = generateToken();
  const expiresAt     = Date.now() + expiryMinutes * 60 * 1000;

  await env.BEAM_EXPIRY.put(
    `dl:${token}`,
    JSON.stringify({
      binId,
      filename,
      size:     size     || 0,
      mimeType: mimeType || 'application/octet-stream',
      expiresAt,
    }),
    // TTL is set 10 minutes longer than expiresAt so the cleanup cron always
    // has a chance to read the entry and delete the Filebin file before KV
    // auto-removes it. Without this buffer, KV could self-delete the entry in
    // the same minute the cron fires, causing Filebin files to linger.
    { expirationTtl: expiryMinutes * 60 + 600 },
  );

  return json({ ok: true, token, expiresAt, expiryMinutes }, 200, origin);
}

// ─── GET /download/:token ─────────────────────────────────────────────────────
// Validates token, fetches file from Filebin, streams to browser, then cleans up.

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
    ctx.waitUntil(filebinDelete(meta.binId, meta.filename));
    return htmlError(
      '⏰ Link expired',
      'This link has passed its expiry time. Ask the sender to re-upload.',
      410, origin,
    );
  }

  // Delete KV immediately — single-use enforced from here
  await env.BEAM_EXPIRY.delete(`dl:${token}`);

  // Fetch from Filebin — follows the 302 redirect to S3 automatically.
  // Explicit non-browser headers prevent filebin from serving its HTML warning page.
  let fbRes;
  try {
    fbRes = await fetch(
      `https://filebin.net/${encodeURIComponent(meta.binId)}/${encodeURIComponent(meta.filename)}`,
      {
        redirect: 'follow',
        headers: {
          // Filebin serves its HTML warning page to unknown User-Agents.
          // Using a recognised downloader UA gets a direct 302 → S3 instead.
          'User-Agent': 'Wget/1.21',
          'Accept':     '*/*',
        },
      },
    );
    if (!fbRes.ok) throw new Error(`Filebin HTTP ${fbRes.status}`);
  } catch (err) {
    console.error('[beam-worker] filebin fetch error:', err.message);
    return htmlError(
      'File unavailable',
      'The file could not be retrieved from cloud storage. It may have expired.',
      502, origin,
    );
  }

  // Pipe Filebin → client; delete file from Filebin after stream completes
  const { readable, writable } = new TransformStream();
  const pipeAndDelete = fbRes.body
    .pipeTo(writable)
    .then(()  => filebinDelete(meta.binId, meta.filename))
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
          filebinDelete(meta.binId, meta.filename),
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
