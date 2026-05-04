/**
 * Beam Cloud Worker — Cloudflare Workers
 *
 * Routes:
 *   POST /upload   — accepts file bytes + metadata, uploads to MEGA anonymously,
 *                    stores expiry record in KV, returns share link + expiry timestamp
 *   GET  /health   — liveness check
 *
 * Cron (every minute):
 *   Scans KV for expired records and deletes the corresponding MEGA file,
 *   then removes the KV record.
 *
 * Environment bindings expected:
 *   BEAM_EXPIRY          — KV namespace
 *   BEAM_ALLOWED_ORIGIN  — CORS allowed origin (secret)
 */

// ---------------------------------------------------------------------------
// CORS helpers
// ---------------------------------------------------------------------------

function corsHeaders(origin) {
  return {
    'Access-Control-Allow-Origin': origin || '*',
    'Access-Control-Allow-Methods': 'POST, GET, OPTIONS',
    'Access-Control-Allow-Headers': 'Content-Type, X-Beam-Expiry-Minutes, X-Beam-Filename',
    'Access-Control-Max-Age': '86400',
  };
}

function jsonResponse(data, status = 200, origin) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json',
      ...corsHeaders(origin),
    },
  });
}

// ---------------------------------------------------------------------------
// MEGA anonymous session helpers
// ---------------------------------------------------------------------------

/**
 * Low-level MEGA API call.
 * MEGA's JSON API lives at https://g.api.mega.co.nz/cs
 */
async function megaApi(commands, sid = null) {
  const url = new URL('https://g.api.mega.co.nz/cs');
  if (sid) url.searchParams.set('sid', sid);

  const res = await fetch(url.toString(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(commands),
  });

  if (!res.ok) throw new Error(`MEGA HTTP error: ${res.status}`);
  const body = await res.json();

  // MEGA returns an array of results, one per command
  if (!Array.isArray(body)) throw new Error(`Unexpected MEGA response: ${JSON.stringify(body)}`);
  return body;
}

/**
 * Create an anonymous (ephemeral) MEGA session.
 * Returns { sid, masterKey, rsaPrivKey }
 *
 * This uses the mega.js approach for ephemeral accounts:
 *   1. Generate random master key + password key
 *   2. Call `up` (user create without email) to get a temporary user handle
 *   3. Call `us` with the derived auth key to get a session ID
 */
async function createAnonSession() {
  // Generate 16-byte master key and password key using Web Crypto
  const masterKeyBytes = crypto.getRandomValues(new Uint8Array(16));
  const passwordKeyBytes = crypto.getRandomValues(new Uint8Array(16));

  // MEGA expects keys as base64url
  const masterKey = toBase64(masterKeyBytes);
  const passwordKey = toBase64(passwordKeyBytes);

  // Encrypt master key with password key (AES-128-ECB, MEGA style)
  const encMasterKey = await aesEncryptECB(masterKeyBytes, passwordKeyBytes);

  // Hash the password key for auth
  const authKey = await megaStringHash(passwordKeyBytes);

  // Create ephemeral user
  const [upResult] = await megaApi([{
    a: 'up',
    k: toBase64(encMasterKey),
    ts: base64FromString(masterKey + authKey),
  }]);

  if (typeof upResult !== 'string') {
    throw new Error(`MEGA up error: ${JSON.stringify(upResult)}`);
  }
  const userHandle = upResult;

  // Login to get session ID
  const [usResult] = await megaApi([{
    a: 'us',
    user: userHandle,
    uh: authKey,
  }]);

  if (typeof usResult !== 'object' || !usResult.k) {
    throw new Error(`MEGA us error: ${JSON.stringify(usResult)}`);
  }

  return {
    sid: usResult.csid || usResult.sid || '',
    userHandle,
    masterKeyBytes,
    passwordKeyBytes,
  };
}

// ---------------------------------------------------------------------------
// Crypto helpers (Web Crypto API, available in Workers)
// ---------------------------------------------------------------------------

function toBase64(bytes) {
  // Standard base64 then convert to MEGA's url-safe variant
  return btoa(String.fromCharCode(...bytes))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=/g, '');
}

function base64FromString(str) {
  return btoa(str).replace(/\+/g, '-').replace(/\//g, '_').replace(/=/g, '');
}

async function aesEncryptECB(data, key) {
  const cryptoKey = await crypto.subtle.importKey(
    'raw', key, { name: 'AES-CBC' }, false, ['encrypt']
  );
  // ECB: encrypt in 16-byte blocks independently (IV = zero)
  const result = new Uint8Array(data.length);
  const zeroIV = new Uint8Array(16);
  for (let i = 0; i < data.length; i += 16) {
    const block = data.slice(i, i + 16);
    const enc = await crypto.subtle.encrypt({ name: 'AES-CBC', iv: zeroIV }, cryptoKey, block);
    // AES-CBC with zero IV, single block: first 16 bytes of output = ECB result
    result.set(new Uint8Array(enc).slice(0, 16), i);
  }
  return result;
}

async function megaStringHash(key) {
  // Simple deterministic hash for MEGA auth (condensed XOR approach)
  const hash = new Uint8Array(8);
  for (let i = 0; i < key.length; i++) {
    hash[i % 8] ^= key[i];
  }
  const cryptoKey = await crypto.subtle.importKey(
    'raw', key, { name: 'AES-CBC' }, false, ['encrypt']
  );
  const zeroIV = new Uint8Array(16);
  let h = hash.slice(0, 16);
  if (h.length < 16) { const p = new Uint8Array(16); p.set(h); h = p; }
  for (let i = 0; i < 16384; i++) {
    const enc = await crypto.subtle.encrypt({ name: 'AES-CBC', iv: zeroIV }, cryptoKey, h);
    h = new Uint8Array(enc).slice(0, 16);
  }
  const result = new Uint8Array(8);
  result.set(h.slice(0, 4)); result.set(h.slice(4, 8), 4);
  return toBase64(result);
}

// ---------------------------------------------------------------------------
// Upload to MEGA via chunked PUT
// ---------------------------------------------------------------------------

/**
 * Upload raw bytes to MEGA anonymously and return a share link.
 * Uses MEGA's ul (upload) API directly (no SDK dependency at runtime).
 *
 * Returns { shareLink, nodeHandle }
 */
async function uploadToMega(fileBytes, filename) {
  // 1. Create anonymous session
  const session = await createAnonSession();

  // 2. Generate a random file key (32 bytes: 16 file key + 8 IV + 8 meta-MAC placeholder)
  const fileKey = crypto.getRandomValues(new Uint8Array(32));
  const fileKeyBytes = fileKey.slice(0, 16);
  const iv = fileKey.slice(16, 24);

  // 3. Request an upload URL from MEGA
  const [ulResult] = await megaApi([{
    a: 'u',
    s: fileBytes.byteLength,
  }], session.sid);

  if (!ulResult || !ulResult.p) {
    throw new Error(`MEGA upload URL error: ${JSON.stringify(ulResult)}`);
  }
  const uploadUrl = ulResult.p;

  // 4. Encrypt file bytes with AES-128-CTR (MEGA uses CTR mode)
  const cryptoKey = await crypto.subtle.importKey(
    'raw', fileKeyBytes, { name: 'AES-CTR' }, false, ['encrypt']
  );
  // MEGA CTR counter: IV (8 bytes) padded with 8 zero bytes, little-endian block counter
  const counter = new Uint8Array(16);
  counter.set(iv, 0);

  const encryptedBytes = await crypto.subtle.encrypt(
    { name: 'AES-CTR', counter, length: 64 },
    cryptoKey,
    fileBytes
  );

  // 5. PUT the encrypted bytes to MEGA's upload endpoint
  const putRes = await fetch(`${uploadUrl}/0`, {
    method: 'POST',
    body: encryptedBytes,
    headers: { 'Content-Type': 'application/octet-stream' },
  });
  if (!putRes.ok) throw new Error(`MEGA PUT error: ${putRes.status}`);
  const completionHandle = await putRes.text();

  // 6. Encrypt filename for MEGA (AES-128-CBC with zero IV, padded)
  const nameBytes = new TextEncoder().encode(filename);
  const paddedName = new Uint8Array(Math.ceil(nameBytes.length / 16) * 16);
  paddedName.set(nameBytes);
  const encName = await aesEncryptECB(paddedName, fileKeyBytes);

  // 7. Build the node attribute JSON, encrypt it
  const attrJson = JSON.stringify({ n: filename });
  const attrBytes = new TextEncoder().encode('MEGA' + attrJson);
  const paddedAttr = new Uint8Array(Math.ceil(attrBytes.length / 16) * 16);
  paddedAttr.set(attrBytes);
  const encAttr = await aesEncryptECB(paddedAttr, fileKeyBytes);

  // 8. XOR file key with master key to produce the stored node key
  const nodeKey = new Uint8Array(32);
  for (let i = 0; i < 16; i++) {
    nodeKey[i] = fileKeyBytes[i] ^ session.masterKeyBytes[i % 16];
  }
  nodeKey.set(iv, 16);  // store IV in upper half

  // 9. Commit the file node
  const [pResult] = await megaApi([{
    a: 'p',
    t: 0,  // upload to root
    n: [{
      h: completionHandle,
      t: 0,  // file
      a: toBase64(encAttr),
      k: toBase64(nodeKey),
    }],
  }], session.sid);

  if (!pResult || !pResult.f || !pResult.f[0]) {
    throw new Error(`MEGA node commit error: ${JSON.stringify(pResult)}`);
  }
  const nodeHandle = pResult.f[0].h;

  // 10. Create a public share link
  // Export key: base64 of [fileKey XOR masterKey ... ] — actually just the raw file key
  const exportKey = toBase64(fileKey.slice(0, 16));

  // Request public link from MEGA
  const [lResult] = await megaApi([{
    a: 'l',
    n: nodeHandle,
    i: 1,   // 1 = create link
  }], session.sid);

  // lResult is the public handle
  const publicHandle = typeof lResult === 'string' ? lResult : nodeHandle;

  const shareLink = `https://mega.nz/file/${publicHandle}#${exportKey}`;

  return {
    shareLink,
    nodeHandle,
    publicHandle,
    sessionData: {
      sid: session.sid,
      userHandle: session.userHandle,
    },
  };
}

// ---------------------------------------------------------------------------
// KV record helpers
// ---------------------------------------------------------------------------

async function storeExpiryRecord(env, id, record) {
  await env.BEAM_EXPIRY.put(id, JSON.stringify(record));
}

async function getAllExpiryRecords(env) {
  const list = await env.BEAM_EXPIRY.list();
  const records = [];
  for (const key of list.keys) {
    const val = await env.BEAM_EXPIRY.get(key.name, { type: 'json' });
    if (val) records.push({ kvKey: key.name, ...val });
  }
  return records;
}

async function deleteExpiryRecord(env, id) {
  await env.BEAM_EXPIRY.delete(id);
}

// ---------------------------------------------------------------------------
// Delete a MEGA file (best-effort — ignores errors)
// ---------------------------------------------------------------------------

async function deleteMegaFile(nodeHandle, sid) {
  try {
    await megaApi([{
      a: 'd',  // delete
      n: nodeHandle,
      i: 1,
    }], sid);
  } catch (err) {
    console.error(`[beam-worker] Failed to delete MEGA node ${nodeHandle}:`, err.message);
  }
}

// ---------------------------------------------------------------------------
// HTTP handler
// ---------------------------------------------------------------------------

async function handleRequest(request, env) {
  const origin = env.BEAM_ALLOWED_ORIGIN || '*';
  const url = new URL(request.url);

  // Preflight
  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders(origin) });
  }

  // Health check
  if (url.pathname === '/health' && request.method === 'GET') {
    return jsonResponse({ ok: true, ts: Date.now() }, 200, origin);
  }

  // Upload endpoint
  if (url.pathname === '/upload' && request.method === 'POST') {
    return handleUpload(request, env, origin);
  }

  return jsonResponse({ error: 'Not found' }, 404, origin);
}

async function handleUpload(request, env, origin) {
  try {
    // Parse headers
    const expiryMinutes = parseInt(request.headers.get('X-Beam-Expiry-Minutes') || '30', 10);
    const filename = decodeURIComponent(request.headers.get('X-Beam-Filename') || 'file');

    if (![15, 30, 45, 60].includes(expiryMinutes)) {
      return jsonResponse({ error: 'Invalid expiry. Must be 15, 30, 45, or 60.' }, 400, origin);
    }

    // Read file bytes
    const fileBytes = await request.arrayBuffer();
    if (fileBytes.byteLength === 0) {
      return jsonResponse({ error: 'Empty file' }, 400, origin);
    }

    // Max 4 GB safety check (Workers have a 25 MB request body limit on the free plan)
    // On paid plan this can be larger. We warn accordingly.
    const MAX_BYTES = 100 * 1024 * 1024; // 100 MB limit per upload request
    if (fileBytes.byteLength > MAX_BYTES) {
      return jsonResponse({
        error: `File too large for a single Worker request. Max: ${MAX_BYTES / 1024 / 1024} MB. Use P2P mode for larger files.`,
      }, 413, origin);
    }

    // Upload to MEGA
    const { shareLink, nodeHandle, sessionData } = await uploadToMega(
      new Uint8Array(fileBytes),
      filename
    );

    // Store expiry record in KV
    const expiresAt = Date.now() + expiryMinutes * 60 * 1000;
    const recordId = `beam-${crypto.randomUUID()}`;

    await storeExpiryRecord(env, recordId, {
      nodeHandle,
      sessionData,
      expiresAt,
      filename,
      uploadedAt: Date.now(),
    });

    return jsonResponse({
      ok: true,
      shareLink,
      expiresAt,
      expiryMinutes,
      recordId,
    }, 200, origin);

  } catch (err) {
    console.error('[beam-worker] Upload error:', err);
    return jsonResponse({ error: err.message || 'Upload failed' }, 500, origin);
  }
}

// ---------------------------------------------------------------------------
// Cron handler — runs every minute, deletes expired files
// ---------------------------------------------------------------------------

async function handleCron(env) {
  console.log('[beam-worker] Cron: scanning for expired records…');
  const now = Date.now();
  let deleted = 0;

  try {
    const records = await getAllExpiryRecords(env);

    for (const rec of records) {
      if (rec.expiresAt <= now) {
        console.log(`[beam-worker] Deleting expired file: ${rec.filename} (expired ${Math.round((now - rec.expiresAt) / 1000)}s ago)`);
        await deleteMegaFile(rec.nodeHandle, rec.sessionData?.sid);
        await deleteExpiryRecord(env, rec.kvKey);
        deleted++;
      }
    }

    console.log(`[beam-worker] Cron complete. Deleted: ${deleted}, Remaining: ${records.length - deleted}`);
  } catch (err) {
    console.error('[beam-worker] Cron error:', err);
  }
}

// ---------------------------------------------------------------------------
// Worker entry point
// ---------------------------------------------------------------------------

export default {
  async fetch(request, env) {
    return handleRequest(request, env);
  },

  async scheduled(event, env) {
    await handleCron(env);
  },
};
