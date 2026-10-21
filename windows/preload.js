/* What the app can ask the Windows side to do. */
const { contextBridge, ipcRenderer } = require('electron');
contextBridge.exposeInMainWorld('RCSPC', {
  mpv: opts => ipcRenderer.invoke('mpv-play', opts),
  mpvStop: () => ipcRenderer.invoke('mpv-stop'),
  onMpv: cb => ipcRenderer.on('mpv', (e, d) => cb(d)),
  fullscreen: () => ipcRenderer.invoke('fullscreen'),
  quit: () => ipcRenderer.invoke('quit')
});
