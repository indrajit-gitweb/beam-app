/**
 * Beam Cloud Worker — Cloudflare Workers
 *
 * The Worker is a metadata-only gatekeeper. It never touches file bytes.
 * All file data flows directly between the browser and MEGA.
 *
 * Routes:
 *   POST /register        — browser uploads to MEGA directly, then registers
 *                           metadata + MEGA credentials here. Returns a token.
 *   GET  /download/:token — validates token, marks as single-use, schedules
 *                           MEGA deletion, returns the MEGA share URL for the
 *                           browser to open directly.
 *   GET  /health          — liveness check
 *
 * Scheduled (cron * * * * *):
 *   — deletes MEGA files whose 5-minute download window has passed
 *
 * KV entries:
 *   dl:{token}  → { shareLink, sid, nodeId, filename, size, expiresAt, used }
 *   del:{token} → { sid, nodeId, deleteAt }   (created after download, TTL 10 min)
 *
 * Environment bindings:
 *   BEAM_EXPIRY          — KV namespace
 *   BEAM_ALLOWED_ORIGIN  — CORS allowed origin secret
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

function generateToken() {
  const bytes = new Uint8Array(16);
  crypto.getRandomValues(bytes);
  return Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
}

// ---------------------------------------------------------------------------
// MEGA — delete (raw API call using stored session SID + nodeId)
// ---------------------------------------------------------------------------

async function deleteFromMega(sid, nodeId) {
  try {
    const seqno = Math.floor(Math.random() * 0xFFFFFFFF);
    const res = await fetch(
      `https://g.api.mega.co.nz/cs?id=${seqno}&sid=${encodeURIComponent(sid)}`,
      {
        method:  'POST',
        headers: { 'Content-Type': 'application/json' },
        body:    JSON.stringify([{ a: 'd', n: nodeId }]),
      },
    );
    const result = await res.json();
    console.log('[beam-worker] MEGA delete result for', nodeId, ':', JSON.stringify(result));
  } catch (err) {
    console.error('[beam-worker] MEGA delete error:', err.message);
  }
}

// ---------------------------------------------------------------------------
// POST /register
// Body: { shareLink, sid, nodeId, filename, size, expiryMinutes }
// Returns: { ok, token, expiresAt }
// ---------------------------------------------------------------------------

async function handleRegister(request, env, origin) {
  try {
    let body;
    try {
      body = await request.json();
    } catch {
      return jsonResponse({ error: 'Invalid JSON body.' }, 400, origin);
    }

    const { shareLink, sid, nodeId, filename, size, expiryMinutes: rawExpiry } = body;

    if (!shareLink || !sid || !nodeId || !filename || !size) {
      return jsonResponse({ error: 'Missing required fields.' }, 400, origin);
    }

    const expiryMinutes = parseInt(rawExpiry || 30, 10);
    if (![15, 30, 45, 60].includes(expiryMinutes)) {
      return jsonResponse({ error: 'Invalid expiry. Must be 15, 30, 45, or 60.' }, 400, origin);
    }

    const token      = generateToken();
    const expiresAt  = Date.now() + expiryMinutes * 60 * 1000;
    const ttlSeconds = expiryMinutes * 60;

    const meta = { shareLink, sid, nodeId, filename, size, expiresAt, used: false };

    await env.BEAM_EXPIRY.put(`dl:${token}`, JSON.stringify(meta), {
      expirationTtl: ttlSeconds,
    });

    return jsonResponse({ ok: true, token, expiresAt, expiryMinutes }, 200, origin);

  } catch (err) {
    console.error('[beam-worker] Register error:', err);
    return jsonResponse({ error: err.message || 'Register failed.' }, 500, origin);
  }
}

// ---------------------------------------------------------------------------
// GET /download/:token
// Validates token, marks single-use, schedules deletion, returns MEGA URL.
// ---------------------------------------------------------------------------

async function handleDownload(token, env, origin) {
  try {
    const raw = await env.BEAM_EXPIRY.get(`dl:${token}`, { type: 'text' });

    if (!raw) {
      return jsonResponse({
        error: 'expired',
        message: 'This link has expired or the file has already been downloaded.',
      }, 410, origin);
    }

    const meta = JSON.parse(raw);

    if (meta.used) {
      return jsonResponse({
        error: 'used',
        message: 'This file has already been downloaded and deleted.',
      }, 410, origin);
    }

    // Mark as used — single-use enforced from this point
    await env.BEAM_EXPIRY.put(
      `dl:${token}`,
      JSON.stringify({ ...meta, used: true }),
      { expirationTtl: 300 }, // 5 min grace, then KV auto-cleans
    );

    // Schedule MEGA deletion after 5 minutes (gives time to complete download)
    // Cron picks up del:{token} entries and deletes from MEGA
    await env.BEAM_EXPIRY.put(
      `del:${token}`,
      JSON.stringify({ sid: meta.sid, nodeId: meta.nodeId, deleteAt: Date.now() + 5 * 60 * 1000 }),
      { expirationTtl: 600 }, // 10-min TTL — KV auto-cleans if cron misses it
    );

    return jsonResponse({
      ok:        true,
      url:       meta.shareLink,
      filename:  meta.filename,
      size:      meta.size,
    }, 200, origin);

  } catch (err) {
    console.error('[beam-worker] Download error:', err);
    return jsonResponse({ error: err.message || 'Request failed.' }, 500, origin);
  }
}

// ---------------------------------------------------------------------------
// Scheduled handler — delete MEGA files whose 5-min window has passed
// ---------------------------------------------------------------------------

async function handleScheduled(env) {
  try {
    const list = await env.BEAM_EXPIRY.list({ prefix: 'del:' });

    const now = Date.now();
    const tasks = [];

    for (const key of list.keys) {
      const raw = await env.BEAM_EXPIRY.get(key.name, { type: 'text' });
      if (!raw) continue;

      const entry = JSON.parse(raw);
      if (entry.deleteAt <= now) {
        tasks.push(
          deleteFromMega(entry.sid, entry.nodeId)
            .then(() => env.BEAM_EXPIRY.delete(key.name)),
        );
      }
    }

    await Promise.allSettled(tasks);
    if (tasks.length > 0) {
      console.log(`[beam-worker] Cron deleted ${tasks.length} MEGA file(s).`);
    }
  } catch (err) {
    console.error('[beam-worker] Cron error:', err.message);
  }
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

  if (url.pathname === '/register' && request.method === 'POST') {
    return handleRegister(request, env, origin);
  }

  const dlMatch = url.pathname.match(/^\/download\/([a-f0-9]+)$/);
  if (dlMatch && request.method === 'GET') {
    return handleDownload(dlMatch[1], env, origin);
  }

  return jsonResponse({ error: 'Not found' }, 404, origin);
}

// ---------------------------------------------------------------------------
// Worker entry point
// ---------------------------------------------------------------------------

export default {
  async fetch(request, env) {
    return handleRequest(request, env);
  },
  async scheduled(event, env) {
    await handleScheduled(env);
  },
};
