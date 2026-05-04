# Deploying Beam Worker to Cloudflare

This Worker handles Cloud Transfer mode for Beam:
- **No size limit, no credit card** — files upload directly from the browser to **Gofile.io** (free, anonymous, unlimited size)
- Browser uploads file to Gofile via XHR → Worker registers the fileId in **Cloudflare KV** and returns a single-use token
- Receiver hits `/download/:token` → Worker fetches the Gofile direct link → streams file to browser → deletes from Gofile
- Single-use download tokens stored in **Cloudflare KV**, expire automatically

---

## Prerequisites

1. A free [Cloudflare account](https://dash.cloudflare.com/sign-up) (no credit card required)
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

## Step 3 — Create the KV namespace

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

## Step 4 — Set the CORS allowed origin

```bash
npx wrangler secret put BEAM_ALLOWED_ORIGIN
# When prompted, enter: https://yourdomain.com
# (or * for local/quick testing only)
```

---

## Step 5 — Deploy

```bash
npm run deploy
```

Wrangler prints your Worker URL, e.g.:

```
https://beam-worker.<your-subdomain>.workers.dev
```

---

## Step 6 — Wire the Worker URL into Beam

Open `index.html` and find:

```javascript
const BEAM_WORKER_URL = 'https://beam-worker.YOUR-SUBDOMAIN.workers.dev';
```

Replace with your actual Worker URL. Save and re-deploy Beam.

---

## Step 7 — Verify

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

| Resource           | Free allowance / month      |
|--------------------|-----------------------------|
| Gofile storage     | Unlimited (files auto-deleted after download) |
| Gofile file size   | No limit                    |
| Gofile bandwidth   | Free                        |
| Worker requests    | 100,000 / day               |
| KV reads           | 100,000 / day               |
| KV writes          | 1,000 / day                 |

Files are deleted from Gofile immediately after the first download (or when the token expires via cron), so storage is only used briefly per transfer. No credit card is ever required.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `wrangler: command not found` | Run `npm install` first, then use `npx wrangler` |
| CORS errors in browser | Check `BEAM_ALLOWED_ORIGIN` secret matches your Beam page origin exactly |
| KV write errors | Confirm the `id` in `wrangler.toml` matches the namespace you created |
| Upload fails | Check browser console for XHR errors; Gofile may be temporarily down |
| Download returns 502 | Gofile link may have expired; the file may need to be re-uploaded |
| Cron not firing | Crons only run on deployed Workers, not `wrangler dev` |
