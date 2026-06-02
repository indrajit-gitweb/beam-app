const { contextBridge, ipcRenderer } = require('electron');

contextBridge.exposeInMainWorld('electronAPI', {
  // LAN server info
  getLanInfo:   ()       => ipcRenderer.invoke('lan:info'),

  // Save a received file to disk
  saveFile:     (name, buffer) => ipcRenderer.invoke('file:save', name, buffer),

  // Show a system notification
  notify:       (title, body)  => ipcRenderer.invoke('notify', title, body),

  // Get this device's name and type
  getDeviceName: () => ipcRenderer.invoke('device:name'),
  getDeviceType: () => ipcRenderer.invoke('device:type'),

  // Platform
  platform: process.platform,
});
