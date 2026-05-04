# ⚡ Beam — Zero-Server File Transfer

**Beam** is a browser-based file transfer tool that lets you send files of any size directly to another person — no sign-up, no cloud accounts, no subscriptions, and no credit card ever required.

It supports two transfer modes so it works in every situation:

| Mode | How it works | Best for |
|------|-------------|----------|
| **P2P (Direct)** | Files travel directly browser-to-browser over WebRTC | Fast transfers when both sides are online |
| **Cloud** | Files upload to free cloud storage; receiver downloads via a one-time link | Large files or when sender/receiver can't be online at the same time |

---

## What problem does Beam solve?

Sending a large file to someone is surprisingly painful:
- Email has size limits (usually 25 MB)
- Google Drive / Dropbox / WeTransfer require accounts or subscriptions for large files
- AirDrop only works on Apple devices on the same network
- USB drives require physical proximity

Beam removes every one of those blockers. Open the page, drop your file, share a code or link — done. No app to install, no account to create, nothing to pay.

---

## Features

- **Two transfer modes** — P2P for speed, Cloud for convenience
- **No size limit** — P2P is unlimited; Cloud reliably handles files up to ~5 GB (see [Filebin note](#realistic-file-size-for-cloud-mode))
- **No account needed** — for sender or receiver
- **Single-use download links** — Cloud links expire after first download
- **Auto-delete** — Cloud files are deleted from storage immediately after download
- **Expiry control** — Set how long a Cloud link stays valid (5 min to 24 hours)
- **Per-file cancel** — Cancel individual downloads or all downloads at once
- **Progress tracking** — Real upload/download progress bars
- **Share anywhere** — Copy link, share via WhatsApp, email, QR code
- **Fully private P2P** — In P2P mode files never leave the browser; only connection metadata touches a server
- **Works on any device** — Pure HTML/CSS/JS, no framework, no build step

---

## Pros & Limitations

### Pros
- ✅ Completely free — no credit card, no subscription, no hidden limits
- ✅ No installation — runs in any modern browser
- ✅ P2P transfers are end-to-end encrypted via WebRTC DTLS
- ✅ Cloud mode works even after the sender closes their tab
- ✅ Files are deleted from cloud storage after the first download
- ✅ **Configurable link expiry** — set the link to expire in as little as 5 minutes or up to 24 hours; the file is wiped from cloud storage the moment it expires
- ✅ Single-page app — one `index.html` file, self-hostable anywhere
- ✅ No backend server to maintain for P2P (uses PeerJS public signalling)

### Limitations
- ⚠️ **P2P requires both sides online simultaneously** — if the sender closes their tab, the transfer stops
- ⚠️ **P2P speed depends on network** — transfers between NAT-restricted networks may be slower (relayed via TURN)
- ⚠️ **Cloud links are single-use** — once the receiver downloads, the link is gone (by design for privacy)
- ⚠️ **Cloud file deletion depends on the Worker being alive** — files are deleted from Filebin immediately on download, or by the cleanup cron within ~1 minute of the expiry time you set. If the Worker's cron is somehow not running (e.g., zero requests for a long time), Filebin's own 6-day retention acts as the final safety net. In normal operation, the expiry you choose is what counts.
- ⚠️ **Cloud mode needs the Beam Worker** — the Cloudflare Worker must be deployed (see setup below); the hosted version at [indrajit-gitweb.github.io/beam-app](https://indrajit-gitweb.github.io/beam-app) already has this configured
- ⚠️ **Browser memory for P2P** — very large files (multi-GB) in P2P mode are chunked but held in browser memory; Cloud mode is better for files over ~1 GB
- ⚠️ **Filebin is "unlimited" in name, not in practice** — Filebin has no hard-coded size cap, but it is a free service run by a single developer. The realistic safe range is **1–5 GB**. Files up to ~10 GB may work but are unreliable — the upload can be killed mid-way or the bin silently deleted. "Unlimited" means no enforced limit, not that the infrastructure can absorb anything.

---

## How to use it (no setup needed)

### Sending a file

1. Open Beam in your browser
2. Choose **P2P** or **Cloud** tab under *Send*
3. Drop your file (or click to browse)
4. **P2P:** Share the 6-digit code with the receiver — they enter it on their device
5. **Cloud:** Set an expiry time → click **Upload to Cloud** → copy the link and share it

### Receiving a file

- **P2P:** Enter the sender's 6-digit code in the *Receive* tab and wait for the transfer
- **Cloud:** Open the link the sender shared — your browser downloads the file automatically

---

## Self-hosting

Beam is a single HTML file. You can host it anywhere that serves static files.

### Option 1 — GitHub Pages (simplest)

1. Fork this repository
2. Go to **Settings → Pages → Source → Deploy from branch → main**
3. Your app is live at `https://<your-username>.github.io/beam-app/`

### Option 2 — Any static host

Upload `index.html` to Netlify, Vercel, Cloudflare Pages, an S3 bucket, or your own server. No build step required.

### Option 3 — Run locally

```bash
git clone https://github.com/indrajit-gitweb/beam-app.git
cd beam-app
# Open index.html directly in your browser — no server needed for P2P mode
open index.html
```

> **Note:** Cloud mode requires the Beam Worker (see below). P2P mode works with just `index.html`.

---

## Realistic file size for Cloud mode

Filebin advertises no size limit — and technically there is no hard cap. But "unlimited" reflects the absence of a restriction, not the capacity of the infrastructure. Filebin is a free service maintained by a single developer.

| File size | Cloud mode reliability |
|-----------|----------------------|
| Up to 1 GB | ✅ Reliable |
| 1 GB – 5 GB | ✅ Generally works |
| 5 GB – 10 GB | ⚠️ May work, but the upload can be dropped mid-way |
| 10 GB+ | ❌ Not recommended — high chance of failure or silent deletion |

For very large files, P2P mode (direct browser-to-browser) has no such restriction as long as both sides stay online during the transfer.

---

## Setting up Cloud mode (Beam Worker)

### What the Cloudflare Worker actually does

The Worker never touches the file bytes during upload — the browser uploads directly to Filebin. The Worker's job is purely **control and security**:

| Role | What happens |
|------|-------------|
| **Token issuer** | After the browser finishes uploading, the Worker creates a secure single-use download token and stores it in KV |
| **Download proxy** | When the receiver opens the link, the Worker fetches the file from Filebin and streams it to the browser — the receiver never sees the raw Filebin URL |
| **Delete trigger** | Immediately after the stream finishes, the Worker deletes the file from Filebin |
| **Expiry enforcer** | A cron runs every minute and deletes files + tokens that have passed their expiry time |
| **Single-use gatekeeper** | The token is deleted from KV *before* the download starts, so the link can never be used twice even if two people click it simultaneously |

**Filebin is the storage. Cloudflare Workers is the control layer on top of it.**

Everything runs on Cloudflare's **free tier** — no credit card required.

### Prerequisites

- A free [Cloudflare account](https://dash.cloudflare.com/sign-up) (no credit card)
- Node.js 18 or later

### Deploy steps

```bash
# 1. Install Wrangler (Cloudflare's CLI)
cd beam-worker
npm install

# 2. Log in to Cloudflare
npx wrangler login

# 3. Create the KV namespace for tokens
npx wrangler kv namespace create BEAM_EXPIRY
# Copy the printed `id` into wrangler.toml under [[kv_namespaces]]

# 4. Set your allowed origin (the URL where Beam is hosted)
npx wrangler secret put BEAM_ALLOWED_ORIGIN
# Enter: https://your-domain.com  (or * for testing)

# 5. Deploy
npm run deploy
# Wrangler prints your Worker URL, e.g. https://beam-worker.yourname.workers.dev
```

### Wire the Worker URL into Beam

Open `index.html` and update:

```js
const BEAM_WORKER_URL = 'https://beam-worker.YOUR-SUBDOMAIN.workers.dev';
```

Replace with the URL Wrangler printed, then re-deploy `index.html`.

### Verify

```bash
curl https://beam-worker.<your-subdomain>.workers.dev/health
# {"ok":true,"ts":...}
```

---

## Project structure

```
beam-app/
├── index.html              # The entire frontend — one file
└── beam-worker/
    ├── src/
    │   └── index.js        # Cloudflare Worker (Cloud mode backend)
    ├── wrangler.toml        # Worker config (KV binding, cron, name)
    ├── package.json
    └── DEPLOY.md           # Detailed Worker deployment guide
```

---

## Dependencies

### Frontend (`index.html`)

| Dependency | Version | Purpose | Loaded from |
|------------|---------|---------|-------------|
| [PeerJS](https://peerjs.com) | 1.5.4 | WebRTC abstraction for P2P connections | unpkg CDN |
| WebRTC | Browser built-in | Encrypted peer-to-peer data channel | — |
| [Filebin.net](https://filebin.net) | — | Free anonymous file storage for Cloud mode | External API |

No build tools, no npm packages, no bundler. The frontend is plain HTML + CSS + vanilla JS.

### Backend (`beam-worker`)

| Dependency | Version | Purpose |
|------------|---------|---------|
| [Wrangler](https://developers.cloudflare.com/workers/wrangler/) | ^4.87.0 | Cloudflare Workers CLI (dev only) |
| Cloudflare Workers runtime | — | Serverless JS execution |
| Cloudflare KV | — | Token storage (free tier: 1,000 writes/day) |

No runtime npm dependencies — the Worker is pure JS with zero imports.

---

## Free tier limits

| Service | Limit | Notes |
|---------|-------|-------|
| Cloudflare Workers | 100,000 requests/day | More than enough for personal use |
| Cloudflare KV reads | 100,000/day | One read per download |
| Cloudflare KV writes | 1,000/day | One write per upload |
| Filebin.net storage | No hard cap (realistic: 1–5 GB reliably) | Files auto-deleted after download |
| Filebin.net retention | 6 days max (fallback only) | Cron deletes on expiry; 6 days is the last-resort safety net |
| PeerJS signalling | Free public server | `0.peerjs.com` — no account needed |

---

## Tech stack

- **Frontend:** Vanilla HTML5 / CSS3 / JavaScript (ES2020+) — no framework
- **P2P:** WebRTC via [PeerJS](https://peerjs.com) (signalling through `0.peerjs.com`)
- **Cloud storage:** [Filebin.net](https://filebin.net) — free, anonymous, no account
- **Cloud backend:** [Cloudflare Workers](https://workers.cloudflare.com/) + KV — free tier
- **Hosting:** GitHub Pages (static)

---

## License

MIT — free to use, modify, and self-host.
