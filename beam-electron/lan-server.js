/**
 * Beam LAN Server — LocalSend-style, pure HTTP.
 *
 * All signaling is HTTP — no persistent WebSocket needed for file transfer.
 * This means transfers survive screen-off and app minimize on both sides.
 *
 * Endpoints:
 *   GET  /hello              → device info JSON
 *   GET  /health             → device info (compatibility alias)
 *   POST /send-request       → sender announces files; store + emit event to browser
 *   GET  /status/:id         → sender polls: pending | accepted | declined
 *   POST /accept/:id         → receiver accepts (called by browser JS)
 *   POST /decline/:id        → receiver declines
 *   GET  /pending            → list pending sessions
 *   POST /upload             → receive file, stream to Downloads folder
 *   GET  /                   → serve index.html
 *
 * The minimal WebSocket is kept ONLY for notifying the browser about incoming
 * transfer requests (since the browser can't poll without a tab visible on Mac).
 */

const http   = require('http');
const path   = require('path');
const fs     = require('fs');
const crypto = require('crypto');
const { WebSocketServer, WebSocket } = require('ws');
const mdns   = require('multicast-dns')();
const os     = require('os');

const PORT        = 7777;
const DEVICE_NAME = os.hostname().replace('.local', '');

// ── Session store ─────────────────────────────────────────────────────────────
// sessionId → { senderName, senderIp, files, status, createdAt }
const sessions = new Map();

// ── Browser receiver sessions (QR flow) ───────────────────────────────────────
// sessionId → { receiverName, files: [{fileId, name, size, type, buffer}] }
const browserReceiverSessions = new Map();

function cleanOldSessions() {
  const cutoff = Date.now() - 5 * 60_000;
  for (const [id, s] of sessions) {
    if (s.createdAt < cutoff) sessions.delete(id);
  }
}

// ── WebSocket (notifications only — no signaling) ─────────────────────────────
// Connected browsers use this to receive popup notifications for incoming
// transfer requests. NOT used for file signaling or data transfer.
const browsers = new Map();  // id → ws

// ── HTTP server ────────────────────────────────────────────────────────────────

const localCopy = path.join(__dirname, 'index.html');
const htmlPath  = fs.existsSync(localCopy)
  ? localCopy
  : path.join(__dirname, '..', 'index.html');

const httpServer = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin',  '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers',
    'Content-Type,X-Filename,X-Filesize,X-Filetype,X-From-Name,' +
    'X-From-Ip,X-Session-Id,X-File-Index,X-Total-Files');
  res.setHeader('Access-Control-Allow-Private-Network', 'true');

  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  // ── Device info ──────────────────────────────────────────────────────────────
  if ((req.url === '/hello' || req.url === '/health') && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, name: DEVICE_NAME, platform: 'mac', version: 2, port: PORT }));
    return;
  }

  // ── Announce incoming transfer ────────────────────────────────────────────────
  if (req.url === '/send-request' && req.method === 'POST') {
    let body = '';
    req.on('data', d => body += d);
    req.on('end', () => {
      try {
        cleanOldSessions();
        const json      = JSON.parse(body || '{}');
        const sessionId = crypto.randomBytes(16).toString('base64url');
        const senderIp  = req.socket.remoteAddress?.replace('::ffff:', '') || '';
        const session   = {
          sessionId,
          senderName: json.senderName || 'Unknown',
          senderIp:   json.senderIp  || senderIp,
          files:      json.files     || [],
          status:     'pending',
          createdAt:  Date.now(),
        };
        sessions.set(sessionId, session);
        // Auto-expire after 5 min
        setTimeout(() => sessions.delete(sessionId), 5 * 60_000);

        // Notify all connected browsers so they can show the Accept popup
        const notification = JSON.stringify({
          type:       'incoming-request',
          sessionId,
          senderName: session.senderName,
          senderIp:   session.senderIp,
          files:      session.files,
          fileName:   session.files[0]?.name  || '',
          fileSize:   session.files[0]?.size  || 0,
          fileCount:  session.files.length,
          totalSize:  session.files.reduce((s, f) => s + (f.size || 0), 0),
        });
        browsers.forEach(ws => {
          try { if (ws.readyState === 1) ws.send(notification); } catch (_) {}
        });

        console.log(`[beam-lan] Incoming request ${sessionId} from ${session.senderName}`);
        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, sessionId, status: 'pending' }));
      } catch (e) {
        res.writeHead(400); res.end('Bad request');
      }
    });
    return;
  }

  // ── Poll session status ───────────────────────────────────────────────────────
  const statusMatch = req.url?.match(/^\/status\/([^/?]+)/);
  if (statusMatch && req.method === 'GET') {
    const sess = sessions.get(statusMatch[1]);
    res.writeHead(sess ? 200 : 404, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ status: sess ? sess.status : 'not-found' }));
    return;
  }

  // ── Accept session ────────────────────────────────────────────────────────────
  const acceptMatch = req.url?.match(/^\/accept\/([^/?]+)/);
  if (acceptMatch && (req.method === 'POST' || req.method === 'GET')) {
    const sess = sessions.get(acceptMatch[1]);
    if (sess) sess.status = 'accepted';
    console.log(`[beam-lan] Session accepted: ${acceptMatch[1]}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  // ── Decline session ───────────────────────────────────────────────────────────
  const declineMatch = req.url?.match(/^\/decline\/([^/?]+)/);
  if (declineMatch && (req.method === 'POST' || req.method === 'GET')) {
    const sess = sessions.get(declineMatch[1]);
    if (sess) sess.status = 'declined';
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  // ── Cancel session (mid-transfer) ─────────────────────────────────────────────
  const cancelMatch = req.url?.match(/^\/cancel\/([^/?]+)/);
  if (cancelMatch && (req.method === 'POST' || req.method === 'GET')) {
    const sess = sessions.get(cancelMatch[1]);
    if (sess) {
      sess.status = 'cancelled';
      browsers.forEach(ws => {
        try { if (ws.readyState === 1) ws.send(JSON.stringify({ type: 'transfer-cancelled' })); }
        catch (_) {}
      });
    }
    console.log(`[beam-lan] Cancelled: ${cancelMatch[1]}`);
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  // ── List pending sessions ─────────────────────────────────────────────────────
  if (req.url === '/pending' && req.method === 'GET') {
    cleanOldSessions();
    const pending = [];
    for (const s of sessions.values()) {
      if (s.status === 'pending') {
        pending.push({
          sessionId:  s.sessionId,
          senderName: s.senderName,
          senderIp:   s.senderIp,
          fileCount:  s.files.length,
          fileName:   s.files[0]?.name  || '',
          fileSize:   s.files[0]?.size  || 0,
          totalSize:  s.files.reduce((sum, f) => sum + (f.size || 0), 0),
        });
      }
    }
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ sessions: pending }));
    return;
  }

  // ── Receive file: stream directly to Downloads ────────────────────────────────
  if (req.url === '/upload' && req.method === 'POST') {
    const filename   = safeDecodeHeader(req.headers['x-filename'])   || 'beam-file';
    const filetype   = req.headers['x-filetype']                     || 'application/octet-stream';
    const fromName   = safeDecodeHeader(req.headers['x-from-name'])  || DEVICE_NAME;
    const sessionId  = req.headers['x-session-id']                   || '';
    const fileIndex  = parseInt(req.headers['x-file-index']  || '0', 10);
    const totalFiles = parseInt(req.headers['x-total-files'] || '1', 10);

    const downloadsDir = getDownloadsDir();
    if (!fs.existsSync(downloadsDir)) fs.mkdirSync(downloadsDir, { recursive: true });

    const safe  = filename.replace(/[/\\?%*:|"<>]/g, '_');
    let destPath = path.join(downloadsDir, safe);
    let i = 1;
    while (fs.existsSync(destPath)) {
      const ext  = path.extname(safe);
      const base = path.basename(safe, ext);
      destPath = path.join(downloadsDir, `${base}_(${i})${ext}`);
      i++;
    }

    const contentLen = parseInt(req.headers['content-length'] || '0', 10);
    const writeStream = fs.createWriteStream(destPath);
    let total = 0;
    let lastPct = -1;
    let streamEnded = false;   // true only when req fires 'end' (all bytes received)

    // ── Sender dropped the connection before finishing ─────────────────────────
    // This fires for BOTH clean close (after end) and premature drops.
    // The streamEnded flag lets us tell them apart.
    req.on('close', () => {
      if (!streamEnded) {
        // Sender disconnected mid-transfer (cancelled or crashed)
        writeStream.destroy();
        try { fs.unlinkSync(destPath); } catch (_) {}
        console.log(`[beam-lan] Upload aborted by sender: ${filename} (${total}/${contentLen} bytes)`);
        // Tell the browser the transfer was cancelled
        browsers.forEach(ws => {
          try {
            if (ws.readyState === 1)
              ws.send(JSON.stringify({ type: 'transfer-cancelled', filename }));
          } catch (_) {}
        });
        if (!res.headersSent) { res.writeHead(499); res.end('cancelled'); }
      }
    });

    req.on('data', chunk => {
      // Check mid-transfer cancel
      const sess = sessionId ? sessions.get(sessionId) : null;
      if (sess && sess.status === 'cancelled') {
        writeStream.destroy();
        try { fs.unlinkSync(destPath); } catch (_) {}
        req.destroy();
        if (!res.headersSent) { res.writeHead(499); res.end('cancelled'); }
        return;
      }
      total += chunk.length;
      writeStream.write(chunk);
      // Send progress to browser via WebSocket (throttled to 1% increments)
      if (contentLen > 0) {
        const pct = Math.floor(total * 100 / contentLen);
        if (pct !== lastPct) {
          lastPct = pct;
          const prog = JSON.stringify({
            type: 'transfer-progress', pct, filename,
            bytesTransferred: total, totalBytes: contentLen,
          });
          browsers.forEach(ws => {
            try { if (ws.readyState === 1) ws.send(prog); } catch (_) {}
          });
        }
      }
    });

    req.on('end', () => {
      streamEnded = true;
      writeStream.end();
      writeStream.on('finish', () => {
        // ── Incomplete transfer: bytes received < Content-Length ──────────────
        // Happens when sender cancelled after we already got 'end' but data
        // was truncated (e.g. chunked encoding signalled end early).
        if (contentLen > 0 && total < contentLen) {
          try { fs.unlinkSync(destPath); } catch (_) {}
          console.log(`[beam-lan] Incomplete upload (${total}/${contentLen}): ${filename}`);
          browsers.forEach(ws => {
            try {
              if (ws.readyState === 1)
                ws.send(JSON.stringify({ type: 'transfer-cancelled', filename }));
            } catch (_) {}
          });
          if (!res.headersSent) { res.writeHead(499); res.end('cancelled'); }
          return;
        }

        console.log(`[beam-lan] Saved ${path.basename(destPath)} (${total} bytes) from ${fromName}`);

        // Notify the browser
        const note = JSON.stringify({
          type:     'transfer-complete',
          filename: path.basename(destPath),
          fromName,
          fileIndex,
          totalFiles,
          savedPath: destPath,
        });
        browsers.forEach(ws => {
          try { if (ws.readyState === 1) ws.send(note); } catch (_) {}
        });

        res.writeHead(200, { 'Content-Type': 'application/json' });
        res.end(JSON.stringify({ ok: true, saved: path.basename(destPath), size: total }));
      });
    });

    req.on('error', err => {
      writeStream.destroy();
      console.error('[beam-lan] Upload error:', err);
      res.writeHead(500); res.end('Upload error');
    });
    return;
  }

  // ── Browser receiver sessions (QR flow) ──────────────────────────────────────

  // POST /receiver-ready  — browser scanned QR, wants to receive files
  if (req.url === '/receiver-ready' && req.method === 'POST') {
    let body = '';
    req.on('data', d => body += d);
    req.on('end', () => {
      try {
        const { receiverName = 'Browser' } = JSON.parse(body || '{}');
        const sessionId = require('crypto').randomBytes(16).toString('hex');
        browserReceiverSessions.set(sessionId, { receiverName, files: [] });
        // Auto-expire after 10 min
        setTimeout(() => browserReceiverSessions.delete(sessionId), 10 * 60 * 1000);
        // Notify sender's browser via WebSocket
        const msg = JSON.stringify({ type: 'receiver-ready', sessionId, receiverName });
        browsers.forEach(ws => { try { if (ws.readyState === 1) ws.send(msg); } catch (_) {} });
        console.log(`[beam-lan] Browser receiver ready: ${receiverName} (${sessionId})`);
        res.writeHead(200, { 'Content-Type': 'application/json', });
        res.end(JSON.stringify({ ok: true, sessionId, senderName: DEVICE_NAME }));
      } catch (e) { res.writeHead(500); res.end('error'); }
    });
    return;
  }

  // GET /pending-receivers  — sender polls for browser devices waiting to receive
  if (req.url === '/pending-receivers' && req.method === 'GET') {
    const receivers = [];
    browserReceiverSessions.forEach((sess, id) => {
      if (sess.files.length === 0) receivers.push({ sessionId: id, receiverName: sess.receiverName });
    });
    res.writeHead(200, { 'Content-Type': 'application/json', });
    res.end(JSON.stringify({ receivers }));
    return;
  }

  // POST /upload-for-session  — sender uploads a file for a browser receiver session
  if (req.url === '/upload-for-session' && req.method === 'POST') {
    const filename  = decodeURIComponent(req.headers['x-filename']  || 'file');
    const filetype  = req.headers['x-filetype']  || 'application/octet-stream';
    const sessionId = req.headers['x-session-id'] || '';
    const sess      = browserReceiverSessions.get(sessionId);
    if (!sess) { res.writeHead(404); res.end('Session not found'); return; }
    const chunks = [];
    req.on('data', c => chunks.push(c));
    req.on('end', () => {
      const buffer = Buffer.concat(chunks);
      const fileId = require('crypto').randomBytes(12).toString('hex');
      sess.files.push({ fileId, name: filename, size: buffer.length, type: filetype, buffer });
      console.log(`[beam-lan] File stored for session ${sessionId}: ${filename} (${buffer.length} B)`);
      res.writeHead(200, { 'Content-Type': 'application/json', });
      res.end(JSON.stringify({ ok: true, fileId }));
    });
    return;
  }

  // GET /files-for-session/:id  — receiver polls for available files
  if (req.method === 'GET' && req.url.startsWith('/files-for-session/')) {
    const sessionId = req.url.slice('/files-for-session/'.length).split('?')[0];
    const sess      = browserReceiverSessions.get(sessionId);
    const files     = sess ? sess.files.map(f => ({ fileId: f.fileId, name: f.name, size: f.size, type: f.type })) : [];
    res.writeHead(200, { 'Content-Type': 'application/json', });
    res.end(JSON.stringify({ files }));
    return;
  }

  // POST /session-downloaded/:id  — receiver signals all files downloaded
  if (req.url.startsWith('/session-downloaded/') && (req.method === 'POST' || req.method === 'GET')) {
    const sessionId = req.url.slice('/session-downloaded/'.length).split('?')[0];
    // Clear files but keep session alive for next batch
    const s = browserReceiverSessions.get(sessionId);
    if (s) s.files = [];
    const msg = JSON.stringify({ type: 'session-complete', sessionId });
    browsers.forEach(ws => { try { if (ws.readyState === 1) ws.send(msg); } catch(_) {} });
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true }));
    return;
  }

  // GET /download-for-session/:sessionId/:fileId  — receiver downloads a file
  if (req.method === 'GET' && req.url.startsWith('/download-for-session/')) {
    const parts     = req.url.slice('/download-for-session/'.length).split('?')[0].split('/');
    const sessionId = parts[0], fileId = parts[1];
    const sess      = browserReceiverSessions.get(sessionId);
    const file      = sess?.files.find(f => f.fileId === fileId);
    if (!file) { res.writeHead(404); res.end('Not found'); return; }
    sess.files.splice(sess.files.indexOf(file), 1); // remove after delivery
    res.writeHead(200, {
      'Content-Type':                    file.type,
      'Content-Disposition':             `attachment; filename="${encodeURIComponent(file.name)}"`,
      'Content-Length':                  String(file.buffer.length),
      'Cache-Control':                   'no-store',
      'Access-Control-Expose-Headers':   'Content-Length',
    });
    res.end(file.buffer);
    return;
  }

  // ── Serve index.html ──────────────────────────────────────────────────────────
  if (req.method === 'GET' && (req.url === '/' || req.url === '/index.html')) {
    try {
      const html = fs.readFileSync(htmlPath, 'utf8');
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(html);
    } catch (e) { res.writeHead(404); res.end('index.html not found'); }
    return;
  }

  res.writeHead(404); res.end('Not found');
});

// ── WebSocket (notifications only) ────────────────────────────────────────────
const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', ws => {
  const id = crypto.randomBytes(8).toString('hex');
  browsers.set(id, ws);
  console.log(`[beam-lan] Browser connected (${browsers.size} total)`);

  ws.on('close', () => {
    browsers.delete(id);
    console.log(`[beam-lan] Browser disconnected (${browsers.size} remain)`);
  });
  ws.on('error', () => browsers.delete(id));
  // Browsers can send a keepalive ping — no other messages needed
  ws.on('message', raw => {
    try {
      const msg = JSON.parse(raw);
      if (msg.type === 'ping') ws.send(JSON.stringify({ type: 'pong' }));
    } catch (_) {}
  });
});

// ── mDNS: announce + browse ────────────────────────────────────────────────────

const discoveredServers = new Set();

mdns.on('query', query => {
  if (!query.questions.some(q =>
    q.name === '_beam-lan._tcp.local' || q.name === '_services._dns-sd._udp.local'))
    return;
  const localIp = getLocalIp();
  mdns.respond({ answers: [
    { type: 'PTR', name: '_beam-lan._tcp.local', data: `${DEVICE_NAME}._beam-lan._tcp.local` },
    { type: 'SRV', name: `${DEVICE_NAME}._beam-lan._tcp.local`,
      data: { target: `${DEVICE_NAME}.local`, port: PORT, priority: 0, weight: 0 } },
    { type: 'TXT', name: `${DEVICE_NAME}._beam-lan._tcp.local`,
      data: [`name=${DEVICE_NAME}`, `port=${PORT}`, `version=2`] },
    { type: 'A',   name: `${DEVICE_NAME}.local`, data: localIp },
  ]});
});

mdns.on('response', response => {
  let name = null, ip = null, port = PORT;
  for (const a of [...(response.answers || []), ...(response.additionals || [])]) {
    if (a.type === 'PTR' && a.name === '_beam-lan._tcp.local') name = a.data;
    if (a.type === 'SRV' && a.data) port = a.data.port || PORT;
    if (a.type === 'A'   && a.data) ip   = a.data;
  }
  if (!ip || !name) return;
  const localIp = getLocalIp();
  if (ip === localIp || ip === '127.0.0.1') return;
  const key = `${ip}:${port}`;
  if (discoveredServers.has(key)) return;
  discoveredServers.add(key);
  const deviceName = name.replace(/\._beam-lan\._tcp\.local$/, '');
  console.log(`[beam-lan] mDNS found: ${deviceName} @ http://${ip}:${port}`);
  browsers.forEach(ws => {
    try {
      if (ws.readyState === 1)
        ws.send(JSON.stringify({ type: 'device-found', ip, port, name: deviceName }));
    } catch (_) {}
  });
});

// ── Subnet scanner (fallback for networks that block mDNS) ────────────────────

async function scanSubnetForBeam() {
  const localIp = getLocalIp();
  if (!localIp || localIp === '127.0.0.1') return;
  const subnet  = localIp.split('.').slice(0, 3).join('.');
  const myOctet = localIp.split('.')[3];
  console.log(`[beam-lan] Subnet scan ${subnet}.1-254…`);
  const probes = [];
  for (let i = 1; i <= 254; i++) {
    if (i.toString() === myOctet) continue;
    probes.push(probeIp(`${subnet}.${i}`));
  }
  await Promise.allSettled(probes);
}

function probeIp(ip) {
  return new Promise(resolve => {
    const key = `${ip}:${PORT}`;
    if (discoveredServers.has(key)) { resolve(); return; }
    const req = http.get({ host: ip, port: PORT, path: '/hello', timeout: 700 }, res => {
      let body = '';
      res.on('data', d => body += d);
      res.on('end', () => {
        try {
          const h = JSON.parse(body);
          if ((h.ok) && !discoveredServers.has(key)) {
            discoveredServers.add(key);
            console.log(`[beam-lan] Subnet scan found: ${h.name} @ http://${ip}:${PORT}`);
            browsers.forEach(ws => {
              try {
                if (ws.readyState === 1)
                  ws.send(JSON.stringify({ type: 'device-found', ip, port: PORT, name: h.name }));
              } catch (_) {}
            });
          }
        } catch (_) {}
        resolve();
      });
    });
    req.on('error', () => resolve());
    req.on('timeout', () => { req.destroy(); resolve(); });
    req.setTimeout(700);
  });
}

// ── Helpers ───────────────────────────────────────────────────────────────────

function getLocalIp() {
  const ifaces = os.networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    for (const iface of ifaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) return iface.address;
    }
  }
  return '127.0.0.1';
}

function getDownloadsDir() {
  // Mac: ~/Downloads  |  Linux: ~/Downloads  |  Windows: ~/Downloads
  return path.join(os.homedir(), 'Downloads');
}

function safeDecodeHeader(val) {
  if (!val) return '';
  try { return decodeURIComponent(val); } catch (_) { return val; }
}

// ── Start / stop ──────────────────────────────────────────────────────────────

let browseInterval = null;

function start() {
  return new Promise((resolve, reject) => {
    httpServer.listen(PORT, '0.0.0.0', () => {
      const ip = getLocalIp();
      console.log(`[beam-lan] Server running on http://${ip}:${PORT}`);
      console.log(`[beam-lan] Device name: ${DEVICE_NAME}`);

      // mDNS browse + subnet scan
      mdns.query({ questions: [{ name: '_beam-lan._tcp.local', type: 'PTR' }] });
      browseInterval = setInterval(() => {
        mdns.query({ questions: [{ name: '_beam-lan._tcp.local', type: 'PTR' }] });
      }, 8000);
      setTimeout(() => scanSubnetForBeam(), 2000);
      setInterval(() => scanSubnetForBeam(), 30_000);

      resolve({ port: PORT, ip, name: DEVICE_NAME });
    });
    httpServer.on('error', reject);
  });
}

function stop() {
  if (browseInterval) { clearInterval(browseInterval); browseInterval = null; }
  discoveredServers.clear();
  try { mdns.destroy(); } catch (_) {}
  httpServer.close();
}

module.exports = { start, stop, getLocalIp, DEVICE_NAME, PORT };
