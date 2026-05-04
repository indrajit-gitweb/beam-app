/**
 * Beam Cloud Worker — Cloudflare Workers
 *
 * Routes:
 *   POST /upload            — accepts file bytes, uploads to transfer.sh server-side,
 *                             stores a single-use token in KV, returns { ok, token, expiresAt }
 *   GET  /download/:token   — validates token, fires delete on transfer.sh, returns { ok, url, filename, size }
 *   GET  /health            — liveness check
 *
 * Scheduled handler (cron "* * * * *"):
 *   Scans all dl:* KV keys, fires DELETE on expired transfer.sh entries, purges KV.
 *
 * Environment bindings (wrangler.toml / wrangler secret):
 *   BEAM_EXPIRY         — KV namespace
 *   BEAM_ALLOWED_ORIGIN — CORS allowed origin (set via `wrangler secret put BEAM_ALLOWED_ORIGIN`)
 */

// ---------------------------------------------------------------------------
// CORS helpers
// ---------------------------------------------------------------------------

function corsHeaders(origin) {
  return {
    'Access-Control-Allow-Origin':  origin || '*',
    'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, X-Beam-Expiry-Minutes, X-Beam-Filename',
    'Access-Control-Max-Age':       '86400',
  };
}

function jsonResponse(data, status = 200, origin) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json', ...corsHeaders(origin) },
  });
}

// ---------------------------------------------------------------------------
// Token generator  (URL-safe base64, 24 chars)
// ---------------------------------------------------------------------------

function generateToken() {
  const arr = new Uint8Array(18);
  crypto.getRandomValues(arr);
  return btoa(String.fromCharCode(...arr))
    .replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

// ---------------------------------------------------------------------------
// transfer.sh delete helper (fire-and-forget)
// ---------------------------------------------------------------------------

async function deleteFile(deleteUrl) {
  if (!deleteUrl) return;
  try {
    await fetch(deleteUrl, { method: 'DELETE' });
  } catch (err) {
    console.error('[beam-worker] delete error:', err.message);
  }
}

// ---------------------------------------------------------------------------
// POST /upload
// Accepts raw file bytes, uploads to transfer.sh server-side, returns token.
// ---------------------------------------------------------------------------

async function handleUpload(request, env, origin) {
  // Parse headers sent by the browser
  const rawExpiry  = request.headers.get('X-Beam-Expiry-Minutes') || '30';
  const rawName    = request.headers.get('X-Beam-Filename')       || 'file';

  const expiryMinutes = Math.min(Math.max(parseInt(rawExpiry, 10) || 30, 5), 1440);
  let filename;
  try { filename = decodeURIComponent(rawName); } catch { filename = rawName; }

  // Read file bytes
  let fileBytes;
  try {
    fileBytes = await request.arrayBuffer();
  } catch (err) {
    return jsonResponse({ error: 'Could not read request body.' }, 400, origin);
  }
  if (!fileBytes || fileBytes.byteLength === 0) {
    return jsonResponse({ error: 'Empty file.' }, 400, origin);
  }

  // Upload to transfer.sh from Worker (server-side — no browser CORS issues)
  let downloadUrl, deleteUrl;
  try {
    const uploadRes = await fetch(
      `https://transfer.sh/${encodeURIComponent(filename)}`,
      {
        method:  'PUT',
        headers: {
          'Max-Downloads': '1',
          'Max-Days':      '1',
        },
        body: fileBytes,
      },
    );

    if (!uploadRes.ok) {
      const body = await uploadRes.text().catch(() => '');
      throw new Error(`transfer.sh returned HTTP ${uploadRes.status}: ${body.slice(0, 120)}`);
    }

    downloadUrl = (await uploadRes.text()).trim();
    deleteUrl   = uploadRes.headers.get('X-Url-Delete') || '';

    if (!downloadUrl.startsWith('https://')) {
      throw new Error('Unexpected response from transfer.sh — no download URL returned.');
    }
  } catch (err) {
    console.error('[beam-worker] transfer.sh upload error:', err.message);
    return jsonResponse(
      { error: `Cloud storage upload failed: ${err.message}` },
      502,
      origin,
    );
  }

  // Store token → metadata in KV
  const token     = generateToken();
  const expiresAt = Date.now() + expiryMinutes * 60 * 1000;

  await env.BEAM_EXPIRY.put(
    `dl:${token}`,
    JSON.stringify({ downloadUrl, deleteUrl, filename, size: fileBytes.byteLength, expiresAt }),
    { expirationTtl: expiryMinutes * 60 },
  );

  return jsonResponse({ ok: true, token, expiresAt, expiryMinutes }, 200, origin);
}

// ---------------------------------------------------------------------------
// GET /download/:token
// Validates token, fires transfer.sh DELETE, returns download URL.
// ---------------------------------------------------------------------------

async function handleDownload(token, env, origin) {
  if (!token || token.length < 10) {
    return jsonResponse({ error: 'Invalid token.' }, 400, origin);
  }

  const raw = await env.BEAM_EXPIRY.get(`dl:${token}`);
  if (!raw) {
    return jsonResponse({ ok: false, message: 'Link expired or already used.' }, 410, origin);
  }

  let meta;
  try { meta = JSON.parse(raw); }
  catch { return jsonResponse({ error: 'Corrupted token data.' }, 500, origin); }

  if (Date.now() > meta.expiresAt) {
    await env.BEAM_EXPIRY.delete(`dl:${token}`);
    deleteFile(meta.deleteUrl);
    return jsonResponse({ ok: false, message: 'Link has expired.' }, 410, origin);
  }

  // Delete from KV immediately — single-use enforced
  await env.BEAM_EXPIRY.delete(`dl:${token}`);

  // Tell transfer.sh to delete the file after the receiver fetches the URL
  // (fire-and-forget — receiver opens URL directly, file deletes on first GET)
  deleteFile(meta.deleteUrl);

  return jsonResponse(
    { ok: true, url: meta.downloadUrl, filename: meta.filename, size: meta.size },
    200,
    origin,
  );
}

// ---------------------------------------------------------------------------
// Scheduled cleanup  (cron: * * * * *)
// Belt-and-suspenders: KV TTL already handles expiry, but this also fires
// the transfer.sh DELETE for any entries that slipped through.
// ---------------------------------------------------------------------------

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
        console.log(`[beam-worker] cron expiring ${key.name} (${meta.filename})`);
        deleteFile(meta.deleteUrl);
        await env.BEAM_EXPIRY.delete(key.name);
      }
    }
  } while (cursor);
}

// ---------------------------------------------------------------------------
// Main router
// ---------------------------------------------------------------------------

async function handleRequest(request, env) {
  const origin = env.BEAM_ALLOWED_ORIGIN || '*';
  const url    = new URL(request.url);

  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders(origin) });
  }

  if (url.pathname === '/health' && request.method === 'GET') {
    return jsonResponse({ ok: true, ts: Date.now() }, 200, origin);
  }

  if (url.pathname === '/upload' && request.method === 'POST') {
    return handleUpload(request, env, origin);
  }

  const dlMatch = url.pathname.match(/^\/download\/([A-Za-z0-9\-_]{10,})$/);
  if (dlMatch && request.method === 'GET') {
    return handleDownload(dlMatch[1], env, origin);
  }

  return jsonResponse({ error: 'Not found.' }, 404, origin);
}

// ---------------------------------------------------------------------------
// Worker entry point
// ---------------------------------------------------------------------------

export default {
  async fetch(request, env) {
    return handleRequest(request, env);
  },

  async scheduled(_event, env) {
    await handleScheduled(env);
  },
};
