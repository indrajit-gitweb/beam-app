# Deploying Beam Worker to Cloudflare

This Worker handles Cloud Transfer mode for Beam:
- **No size limit** — files upload in 50 MB chunks via R2 multipart (supports 20 GB+ files)
- Browser splits the file into chunks → each chunk goes to the Worker → Worker assembles in **Cloudflare R2** (free object storage, up to 5 TB per file, 10 GB-month free tier)
- Receiver hits `/download/:token` → Worker streams R2 object directly to the browser, then deletes it
- Single-use download tokens stored in **Cloudflare KV**, expire automatically

---

## Prerequisites

1. A free [Cloudflare account](https://dash.cloudflare.com/sign-up)
2. Node.js 18+ installed locally

---

## Step 1 — Install dependencies

```bash
cd beam-worker
npm install
```

---

## Step 2 — Log in to Cloudflare

```bash
npx wrangler login
```

---

## Step 3 — Create the R2 bucket

```bash
npx wrangler r2 bucket create beam-files
```

The bucket name **must match** the `bucket_name` in `wrangler.toml` (`beam-files`).

---

## Step 4 — Create the KV namespace

```bash
npx wrangler kv namespace create BEAM_EXPIRY
```

Wrangler prints something like:

```
✨ Success! Add the following to your wrangler.toml:
{ binding = "BEAM_EXPIRY", id = "abc123..." }
```

**Copy the `id`** and paste it into `wrangler.toml`:

```toml
[[kv_namespaces]]
binding = "BEAM_EXPIRY"
id      = "abc123..."   # paste here
```

---

## Step 5 — Set the CORS allowed origin

```bash
npx wrangler secret put BEAM_ALLOWED_ORIGIN
# When prompted, enter: https://yourdomain.com
# (or * for local/quick testing only)
```

---

## Step 6 — Deploy

```bash
npm run deploy
```

Wrangler prints your Worker URL, e.g.:

```
https://beam-worker.<your-subdomain>.workers.dev
```

---

## Step 7 — Wire the Worker URL into Beam

Open `index.html` and find:

```javascript
const BEAM_WORKER_URL = 'https://beam-worker.YOUR-SUBDOMAIN.workers.dev';
```

Replace with your actual Worker URL. Save and re-deploy Beam.

---

## Step 8 — Verify

```bash
curl https://beam-worker.<your-subdomain>.workers.dev/health
# Expected: {"ok":true,"ts":...}
```

---

## Local development (optional)

```bash
npm run dev
```

Wrangler starts a local server at `http://localhost:8787`.
Use `wrangler dev --test-scheduled` and visit `/__scheduled?cron=*+*+*+*+*` to trigger the cron manually.

---

## Free tier limits

| Resource       | Free allowance / month      |
|----------------|-----------------------------|
| R2 storage     | 10 GB-month                 |
| R2 Class A ops | 1,000,000 (PUT/POST)        |
| R2 Class B ops | 10,000,000 (GET)            |
| R2 egress      | **Free** (no egress fees)   |
| Worker requests| 100,000 / day               |
| KV reads       | 100,000 / day               |
| KV writes      | 1,000 / day                 |

Files are deleted from R2 after first download (or at expiry), so storage is used only briefly per transfer. The free tier is sufficient for regular use at any file size.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `wrangler: command not found` | Run `npm install` first, then use `npx wrangler` |
| CORS errors in browser | Check `BEAM_ALLOWED_ORIGIN` secret matches your Beam page origin exactly |
| KV write errors | Confirm the `id` in `wrangler.toml` matches the namespace you created |
| R2 not found | Run `wrangler r2 bucket create beam-files` and redeploy |
| Upload fails mid-way | Parts retry up to 3x automatically; check Worker logs with `npm run tail` |
| Cron not firing | Crons only run on deployed Workers, not `wrangler dev` |
