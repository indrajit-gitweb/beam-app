const { app, BrowserWindow, ipcMain, Notification, dialog, nativeImage } = require('electron');
const path    = require('path');
const fs      = require('fs');
const os      = require('os');
const lanServer = require('./lan-server');

let mainWindow;
let lanInfo = null;

// ── Resolve index.html path (dev vs packaged) ─────────────────────────────────
// Dev:      beam-electron/../index.html
// Packaged: electron-builder copies ../index.html → index.html inside the asar
function resolveHtmlPath() {
  const localCopy = path.join(__dirname, 'index.html');
  const devCopy   = path.join(__dirname, '..', 'index.html');
  return fs.existsSync(localCopy) ? localCopy : devCopy;
}

// ── Window ─────────────────────────────────────────────────────────────────────

function createWindow() {
  mainWindow = new BrowserWindow({
    width:  960,
    height: 700,
    minWidth:  520,
    minHeight: 500,
    title: 'Beam',
    backgroundColor: '#06060f',
    webPreferences: {
      preload:          path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      nodeIntegration:  false,
    },
  });

  mainWindow.loadFile(resolveHtmlPath());

  // Open DevTools in dev mode
  if (process.argv.includes('--dev')) {
    mainWindow.webContents.openDevTools();
  }

  mainWindow.on('closed', () => { mainWindow = null; });
}

// ── IPC handlers ───────────────────────────────────────────────────────────────

ipcMain.handle('lan:info', () => lanInfo);

ipcMain.handle('device:name', () =>
  os.hostname().replace('.local', '')
);

ipcMain.handle('device:type', () => {
  // Simple heuristic
  const p = process.platform;
  if (p === 'darwin' || p === 'win32' || p === 'linux') return 'laptop';
  return 'desktop';
});

ipcMain.handle('file:save', async (_event, filename, arrayBuffer) => {
  const downloadsDir = app.getPath('downloads');
  const safeName = filename.replace(/[/\\?%*:|"<>]/g, '_');
  let dest = path.join(downloadsDir, safeName);

  // Avoid overwriting existing files
  let i = 1;
  while (fs.existsSync(dest)) {
    const ext  = path.extname(safeName);
    const base = path.basename(safeName, ext);
    dest = path.join(downloadsDir, `${base} (${i++})${ext}`);
  }

  fs.writeFileSync(dest, Buffer.from(arrayBuffer));
  return dest;
});

ipcMain.handle('notify', (_event, title, body) => {
  if (Notification.isSupported()) {
    new Notification({ title, body }).show();
  }
});

// ── App lifecycle ──────────────────────────────────────────────────────────────

app.whenReady().then(async () => {
  try {
    lanInfo = await lanServer.start();
    console.log('[main] LAN server started:', lanInfo);
  } catch (err) {
    console.error('[main] LAN server failed to start:', err.message);
  }
  createWindow();
});

app.on('window-all-closed', () => {
  lanServer.stop();
  if (process.platform !== 'darwin') app.quit();
});

app.on('activate', () => {
  if (BrowserWindow.getAllWindows().length === 0) createWindow();
});
