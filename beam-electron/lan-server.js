/**
 * Beam LAN Server
 * Runs locally — handles WebSocket signaling + HTTP file transfer over WiFi LAN.
 * No WebRTC needed: files upload via HTTP POST, download via HTTP GET.
 * Also serves index.html so Android/iOS/Windows browsers can connect.
 * Announces via mDNS so devices auto-discover it on the same network.
 */

const http    = require('http');
const path    = require('path');
const fs      = require('fs');
const crypto  = require('crypto');
const { WebSocketServer, WebSocket } = require('ws');
const mdns    = require('multicast-dns')();
const os      = require('os');

// ── In-memory file store (single-use, auto-expiry based on file size) ────────
// fileId -> { buffer, name, size, type, fromPeer, toPeer, expires }
//
// Expiry is determined by the sender based on total transfer size:
//   < 100 MB        → 10 min
//   100 MB – 500 MB → 20 min
//   500 MB – 1.5 GB → 30 min
//   1.5 GB – 3 GB   → 45 min
//   > 3 GB          → 60 min
// Sender passes X-Expiry-Minutes header; server clamps to [10, 60].
const fileStore = new Map();

const PORT        = 7777;
const DEVICE_NAME = os.hostname().replace('.local', '');

// ── Peer registry ──────────────────────────────────────────────────────────────
// peers: Map<peerId, { ws, name, deviceType }>

const peers = new Map();

function broadcastPeerList() {
  const list = Array.from(peers.entries()).map(([id, p]) => ({
    id,
    name: p.name,
    deviceType: p.deviceType,
    signal: p.signal || '●●●● Excellent',
  }));
  const msg = JSON.stringify({ type: 'peers', list });
  peers.forEach(p => {
    if (p.ws.readyState === WebSocket.OPEN) p.ws.send(msg);
  });
}

// ── HTTP server ────────────────────────────────────────────────────────────────

// Dev: beam-electron/../index.html  |  Packaged: electron-builder copies it locally
const localCopy = path.join(__dirname, 'index.html');
const htmlPath  = fs.existsSync(localCopy)
  ? localCopy
  : path.join(__dirname, '..', 'index.html');

const httpServer = http.createServer((req, res) => {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, OPTIONS');
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, X-Filename, X-Filesize, X-Filetype, X-Target-Peer, X-From-Peer, X-From-Name');

  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }

  // ── Health check ──
  if (req.url === '/health' && req.method === 'GET') {
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ ok: true, name: DEVICE_NAME, port: PORT }));
    return;
  }

  // ── Peer list ──
  if (req.url === '/peers' && req.method === 'GET') {
    const list = Array.from(peers.entries()).map(([id, p]) => ({
      id, name: p.name, deviceType: p.deviceType,
    }));
    res.writeHead(200, { 'Content-Type': 'application/json' });
    res.end(JSON.stringify({ peers: list }));
    return;
  }

  // ── File upload (sender → LAN server) ──
  // POST /upload
  // Headers: X-Filename, X-Filesize, X-Filetype, X-Target-Peer, X-From-Peer, X-From-Name
  if (req.url === '/upload' && req.method === 'POST') {
    const filename   = decodeURIComponent(req.headers['x-filename']    || 'file');
    const filesize   = parseInt(req.headers['x-filesize']  || '0', 10);
    const filetype   = req.headers['x-filetype']   || 'application/octet-stream';
    const targetPeer = req.headers['x-target-peer'] || '';
    const fromPeer   = req.headers['x-from-peer']  || '';
    const fromName   = decodeURIComponent(req.headers['x-from-name']   || DEVICE_NAME);
    const fileIndex    = parseInt(req.headers['x-file-index']    || '0', 10);
    const totalFiles   = parseInt(req.headers['x-total-files']   || '1', 10);
    const expiryMins   = Math.min(Math.max(parseInt(req.headers['x-expiry-minutes'] || '10', 10), 10), 60);
    const expiryMs     = expiryMins * 60 * 1000;

    const chunks = [];
    req.on('data', chunk => chunks.push(chunk));
    req.on('end', () => {
      const buffer = Buffer.concat(chunks);
      const fileId = crypto.randomBytes(16).toString('hex');
      const expires = Date.now() + expiryMs;

      fileStore.set(fileId, { buffer, name: filename, size: filesize, type: filetype, fromPeer, toPeer: targetPeer, expires });

      // Auto-delete after expiry window (determined by sender based on total transfer size)
      setTimeout(() => fileStore.delete(fileId), expiryMs);
      console.log(`[beam-lan] File stored: ${filename} (${buffer.length} bytes) expires in ${expiryMins} min`);

      console.log(`[beam-lan] File stored: ${filename} (${buffer.length} bytes) → peer ${targetPeer}`);

      // Notify target peer via WebSocket
      const target = peers.get(targetPeer);
      if (target && target.ws.readyState === WebSocket.OPEN) {
        target.ws.send(JSON.stringify({
          type:        'file-ready',
          fileId,
          filename,
          filesize:    buffer.length,
          filetype,
          fromName,
          fileIndex,
          totalFiles,
          downloadUrl: `/download/${fileId}`,
        }));
      }

      res.writeHead(200, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ ok: true, fileId }));
    });
    req.on('error', () => { res.writeHead(500); res.end('Upload error'); });
    return;
  }

  // ── File download (LAN server → receiver) ──
  // GET /download/:fileId
  if (req.method === 'GET' && req.url.startsWith('/download/')) {
    const fileId = req.url.slice('/download/'.length).split('?')[0];
    const file   = fileStore.get(fileId);

    if (!file) {
      res.writeHead(404, { 'Content-Type': 'text/plain' });
      res.end('File not found or already downloaded');
      return;
    }

    console.log(`[beam-lan] Serving file: ${file.name} (${file.buffer.length} bytes)`);

    // Only delete AFTER the response is fully flushed to the client.
    // If the connection drops mid-download, the file stays in the store
    // so the receiver can retry with the same URL.
    let fullyDelivered = false;
    res.on('finish', () => {
      fullyDelivered = true;
      fileStore.delete(fileId);
      console.log(`[beam-lan] File delivered and removed: ${file.name}`);
    });
    res.on('close', () => {
      if (!fullyDelivered) {
        console.log(`[beam-lan] Download interrupted — keeping file for retry: ${file.name}`);
      }
    });

    res.writeHead(200, {
      'Content-Type':        file.type,
      'Content-Disposition': `attachment; filename="${encodeURIComponent(file.name)}"`,
      'Content-Length':      String(file.buffer.length),
      'Cache-Control':       'no-store',
    });
    res.end(file.buffer);
    return;
  }

  // ── Serve index.html for Android/iOS/Windows browsers ──
  if (req.method === 'GET' && (req.url === '/' || req.url === '/index.html')) {
    try {
      const html = fs.readFileSync(htmlPath, 'utf8');
      res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(html);
    } catch (e) {
      res.writeHead(404); res.end('index.html not found');
    }
    return;
  }

  res.writeHead(404); res.end('Not found');
});

// ── WebSocket signaling server ─────────────────────────────────────────────────

const wss = new WebSocketServer({ server: httpServer });

wss.on('connection', ws => {
  let myId = null;

  ws.on('message', raw => {
    let msg;
    try { msg = JSON.parse(raw); } catch { return; }

    switch (msg.type) {

      case 'register': {
        myId = msg.id;
        peers.set(myId, {
          ws,
          name:       msg.name       || DEVICE_NAME,
          deviceType: msg.deviceType || 'desktop',
          signal:     msg.signal     || '●●●● Excellent',
        });
        // Confirm registration
        ws.send(JSON.stringify({ type: 'registered', id: myId }));
        broadcastPeerList();
        break;
      }

      case 'signal': {
        // Forward WebRTC offer/answer/ICE to the target peer
        const target = peers.get(msg.to);
        if (target && target.ws.readyState === WebSocket.OPEN) {
          target.ws.send(JSON.stringify({
            type: 'signal',
            from: myId,
            fromName: peers.get(myId)?.name || '?',
            data: msg.data,
          }));
        }
        break;
      }

      case 'transfer-request': {
        // Sender tells receiver: "I want to send you these files"
        const target = peers.get(msg.to);
        if (target && target.ws.readyState === WebSocket.OPEN) {
          target.ws.send(JSON.stringify({
            type:     'transfer-request',
            from:     myId,
            fromName: peers.get(myId)?.name || '?',
            files:    msg.files,
          }));
        }
        break;
      }

      case 'transfer-accept': {
        const target = peers.get(msg.to);
        if (target && target.ws.readyState === WebSocket.OPEN) {
          target.ws.send(JSON.stringify({ type: 'transfer-accepted', from: myId }));
        }
        break;
      }

      case 'transfer-decline': {
        const target = peers.get(msg.to);
        if (target && target.ws.readyState === WebSocket.OPEN) {
          target.ws.send(JSON.stringify({ type: 'transfer-declined', from: myId }));
        }
        break;
      }
    }
  });

  ws.on('close', () => {
    if (myId) {
      peers.delete(myId);
      broadcastPeerList();
    }
  });

  ws.on('error', () => {
    if (myId) peers.delete(myId);
  });
});

// ── mDNS: announce + browse ────────────────────────────────────────────────────
// ANNOUNCE: respond to queries so others (Android) can find this Mac server.
// BROWSE:   actively query for other Beam servers (Android) on the network.

// Track discovered external servers so we don't notify duplicates
const discoveredServers = new Set(); // Set of "ip:port" strings

// ── Respond to queries (existing announce behaviour) ──────────────────────────
mdns.on('query', query => {
  const isBeamQuery = query.questions.some(
    q => q.name === '_beam-lan._tcp.local' || q.name === '_services._dns-sd._udp.local'
  );
  if (!isBeamQuery) return;

  const localIp = getLocalIp();
  mdns.respond({
    answers: [
      {
        type: 'PTR',
        name: '_beam-lan._tcp.local',
        data: `${DEVICE_NAME}._beam-lan._tcp.local`,
      },
      {
        type: 'SRV',
        name: `${DEVICE_NAME}._beam-lan._tcp.local`,
        data: { target: `${DEVICE_NAME}.local`, port: PORT, priority: 0, weight: 0 },
      },
      {
        type: 'TXT',
        name: `${DEVICE_NAME}._beam-lan._tcp.local`,
        data: [`name=${DEVICE_NAME}`, `port=${PORT}`, `version=2`],
      },
      {
        type: 'A',
        name: `${DEVICE_NAME}.local`,
        data: localIp,
      },
    ],
  });
});

// ── Listen for responses (new browse behaviour) ───────────────────────────────
// When another Beam server (e.g. Android) replies to our PTR query,
// extract its IP + port and notify all connected WebSocket clients so their
// frontend can connect to it as an additional peer server.

mdns.on('response', response => {
  let name = null, ip = null, port = PORT;

  for (const answer of [...(response.answers || []), ...(response.additionals || [])]) {
    if (answer.type === 'PTR' && answer.name === '_beam-lan._tcp.local') {
      name = answer.data; // e.g. "Pixel7._beam-lan._tcp.local"
    }
    if (answer.type === 'SRV' && answer.data) {
      port = answer.data.port || PORT;
    }
    if (answer.type === 'A' && answer.data) {
      ip = answer.data;
    }
  }

  if (!ip || !name) return;

  // Skip our own address
  const localIp = getLocalIp();
  if (ip === localIp || ip === '127.0.0.1') return;

  const key = `${ip}:${port}`;
  if (discoveredServers.has(key)) return; // already notified
  discoveredServers.add(key);

  const deviceName = name.replace(/\._beam-lan\._tcp\.local$/, '');
  const serverUrl  = `http://${ip}:${port}`;

  console.log(`[beam-lan] Discovered external Beam server: ${deviceName} @ ${serverUrl}`);

  // Notify all connected WebSocket clients — frontend connects to it as extra peer
  const msg = JSON.stringify({
    type:       'external-server-found',
    url:        serverUrl,
    deviceName,
  });
  peers.forEach(p => {
    try { if (p.ws.readyState === 1) p.ws.send(msg); } catch (_) {}
  });
});

// ── Active browse: send PTR query every 6s to discover new devices ────────────
function browseForBeamDevices() {
  mdns.query({ questions: [{ name: '_beam-lan._tcp.local', type: 'PTR' }] });
}

let browseInterval = null;

// ── Subnet scanner (fallback for networks that block mDNS) ────────────────────
// Like LocalSend: probes every IP on the local subnet via HTTP.
// Runs once on start, then every 30s. Finds Beam devices without multicast.

async function scanSubnetForBeam() {
  const localIp = getLocalIp();
  if (!localIp || localIp === '127.0.0.1') return;

  const subnet = localIp.split('.').slice(0, 3).join('.');
  const myOctet = localIp.split('.')[3];

  console.log(`[beam-lan] Scanning subnet ${subnet}.1-254 for Beam devices…`);

  const probes = [];
  for (let i = 1; i <= 254; i++) {
    if (i.toString() === myOctet) continue; // skip self
    const ip = `${subnet}.${i}`;
    probes.push(probeIp(ip));
  }

  await Promise.allSettled(probes);
}

function probeIp(ip) {
  return new Promise(resolve => {
    const key = `${ip}:${PORT}`;
    if (discoveredServers.has(key)) { resolve(); return; } // already found

    const req = require('http').get(
      { host: ip, port: PORT, path: '/health', timeout: 600 },
      res => {
        let body = '';
        res.on('data', d => body += d);
        res.on('end', () => {
          try {
            const h = JSON.parse(body);
            if (h.ok && !discoveredServers.has(key)) {
              discoveredServers.add(key);
              const serverUrl = `http://${ip}:${PORT}`;
              console.log(`[beam-lan] Subnet scan found: ${h.name} @ ${serverUrl}`);
              const msg = JSON.stringify({ type: 'external-server-found', url: serverUrl, deviceName: h.name });
              peers.forEach(p => {
                try { if (p.ws.readyState === 1) p.ws.send(msg); } catch(_) {}
              });
            }
          } catch(_) {}
          resolve();
        });
      }
    );
    req.on('error', () => resolve());
    req.on('timeout', () => { req.destroy(); resolve(); });
    req.setTimeout(600);
  });
}

function getLocalIp() {
  const ifaces = os.networkInterfaces();
  for (const name of Object.keys(ifaces)) {
    for (const iface of ifaces[name]) {
      if (iface.family === 'IPv4' && !iface.internal) return iface.address;
    }
  }
  return '127.0.0.1';
}

// ── Start ──────────────────────────────────────────────────────────────────────

function start() {
  return new Promise((resolve, reject) => {
    httpServer.listen(PORT, '0.0.0.0', () => {
      const ip = getLocalIp();
      console.log(`[beam-lan] Server running on http://${ip}:${PORT}`);
      console.log(`[beam-lan] Device name: ${DEVICE_NAME}`);
      console.log(`[beam-lan] Android: open http://${ip}:${PORT} in browser`);

      // Start active browsing immediately then every 6s
      browseForBeamDevices();
      // Subnet scan as fallback for networks that block mDNS (like corporate WiFi)
      setTimeout(() => scanSubnetForBeam(), 2000); // first scan 2s after start
      browseInterval = setInterval(browseForBeamDevices, 6000);
      // Repeat subnet scan every 30s (in case new devices join later)
      setInterval(() => scanSubnetForBeam(), 30000);

      resolve({ port: PORT, ip, name: DEVICE_NAME });
    });
    httpServer.on('error', reject);
  });
}

function stop() {
  if (browseInterval) { clearInterval(browseInterval); browseInterval = null; }
  discoveredServers.clear();
  mdns.destroy();
  httpServer.close();
}

module.exports = { start, stop, getLocalIp, DEVICE_NAME, PORT };
