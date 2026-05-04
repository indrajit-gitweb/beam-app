# Deploying Beam Worker to Cloudflare

This Worker handles Cloud Transfer mode for Beam:
- Accepts file uploads and pushes them to MEGA (anonymously, no account needed)
- Stores expiry records in Cloudflare KV
- Deletes expired MEGA files every minute via cron trigger

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

This opens a browser window. Authorise Wrangler to access your Cloudflare account.

---

## Step 3 — Create the KV namespace

```bash
npx wrangler kv namespace create BEAM_EXPIRY
```

Wrangler will print something like:

```
🌀 Creating namespace with title "beam-worker-BEAM_EXPIRY"
✨ Success!
Add the following to your configuration file in your kv_namespaces array:
{ binding = "BEAM_EXPIRY", id = "abc123..." }
```

**Copy the `id` value** and paste it into `wrangler.toml`:

```toml
[[kv_namespaces]]
binding = "BEAM_EXPIRY"
id      = "abc123..."   # ← paste here
```

*(Optional)* Create a preview namespace for local `wrangler dev`:

```bash
npx wrangler kv namespace create BEAM_EXPIRY --preview
```

Paste that ID as `preview_id` in `wrangler.toml`.

---

## Step 4 — Set the CORS allowed origin (secret)

Replace `https://yourdomain.com` with wherever your Beam page is hosted
(e.g. a GitHub Pages URL, a Vercel URL, or `*` for testing):

```bash
npx wrangler secret put BEAM_ALLOWED_ORIGIN
# When prompted, enter: https://yourdomain.com
```

---

## Step 5 — Deploy

```bash
npm run deploy
```

Wrangler will print your Worker URL, e.g.:

```
https://beam-worker.<your-subdomain>.workers.dev
```

**Copy this URL** — you'll need it in the Beam app (`BEAM_WORKER_URL` constant in `index.html`).

---

## Step 6 — Wire the Worker URL into Beam

Open `beam/index.html` (or `outputs/beam.html`) and find:

```javascript
const BEAM_WORKER_URL = 'https://beam-worker.YOUR-SUBDOMAIN.workers.dev';
```

Replace the placeholder with your actual Worker URL. Save, re-deploy Beam.

---

## Step 7 — Verify it works

```bash
# Health check
curl https://beam-worker.<your-subdomain>.workers.dev/health

# Expected:
# {"ok":true,"ts":1234567890123}
```

---

## Local development (optional)

```bash
npm run dev
```

Wrangler starts a local server at `http://localhost:8787`. The cron won't trigger automatically locally — use `wrangler dev --test-scheduled` and visit `http://localhost:8787/__scheduled?cron=*+*+*+*+*` to trigger it manually.

---

## Pricing / limits

| Plan       | Requests/day | KV reads/day | KV writes/day | Cron invocations |
|------------|-------------|--------------|---------------|------------------|
| Free       | 100,000      | 100,000       | 1,000         | unlimited        |
| Paid ($5+) | 10M          | 10M           | 1M            | unlimited        |

For typical Beam usage (occasional file shares), the free plan is more than enough.

**Important**: Cloudflare Workers on the free plan have a **25 MB request body limit**.
Files larger than 25 MB should use Beam's P2P mode instead.

On the **paid plan (Workers Paid, $5/mo)**, the body limit increases to 500 MB.

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| `wrangler: command not found` | Run `npm install` first, then use `npx wrangler` |
| CORS errors in browser | Check `BEAM_ALLOWED_ORIGIN` secret matches your Beam page origin exactly |
| KV write errors | Confirm the `id` in `wrangler.toml` matches the namespace you created |
| MEGA upload fails | MEGA's anonymous API is occasionally rate-limited — retry or switch to P2P |
| Cron not firing | Crons only run on deployed Workers, not `wrangler dev` — use `--test-scheduled` flag |
