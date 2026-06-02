# Beam Desktop (Phase 2 — Offline LAN)

Electron wrapper for Beam that enables real offline file transfers over WiFi LAN.

## Run locally (development)

```bash
cd beam-electron
npm install
npm start
```

## How it works

1. When the app starts, it launches a local LAN server on port 7777
2. Other devices on the same WiFi network open `http://<your-ip>:7777` in their browser
3. Both devices appear in the Offline device list automatically
4. Files transfer via WebRTC data channels — directly over LAN, no internet needed
5. Files are saved to your Downloads folder automatically

## Android

On Android, open Chrome and navigate to `http://<mac-ip>:7777` — the full Beam UI loads and connects to the LAN server automatically.

## Platform support

| Platform | Status |
|---|---|
| macOS | ✅ Full support |
| Windows | ✅ Full support |
| Linux | ✅ Full support |
| Android (browser) | ✅ Connect to desktop server |
| iOS (browser) | ✅ Connect to desktop server |
| Bluetooth | 🔜 Phase 3 |
