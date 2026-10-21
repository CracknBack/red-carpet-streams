/*
 * Red Carpet Streams for Windows.
 * The window shows the same app as the TVs. Files the built-in (Chrome) player can't handle,
 * such as MKV files or Dolby/DTS sound, are handed to mpv, a full-featured player bundled with the app.
 */
const { app, BrowserWindow, ipcMain } = require('electron');
const path = require('path');
const fs = require('fs');
const net = require('net');
const { spawn } = require('child_process');

let win = null;
let mpv = null;
const PIPE = '\\\\.\\pipe\\rcs-mpv-' + process.pid;

function createWindow() {
  win = new BrowserWindow({
    width: 1440, height: 880, minWidth: 1024, minHeight: 640,
    backgroundColor: '#0C1920',
    title: 'Red Carpet Streams',
    icon: path.join(__dirname, 'icon.png'),
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      /* IPTV servers don't send the headers a normal web page needs to read them, so this app allows it. */
      webSecurity: false
    }
  });
  win.loadFile(path.join(__dirname, 'app', 'index.html'));
}

function mpvPath() {
  const p = app.isPackaged ? path.join(process.resourcesPath, 'mpv', 'mpv.exe') : path.join(__dirname, 'mpv', 'mpv.exe');
  return fs.existsSync(p) ? p : null;
}

function tell(d) { if (win && !win.isDestroyed()) win.webContents.send('mpv', d); }

function stopMpv() {
  if (mpv) { try { mpv.proc.kill(); } catch (e) { } }
}

ipcMain.handle('mpv-play', (e, o) => {
  stopMpv();
  const exe = mpvPath();
  if (!exe) return { ok: false };
  const lang = (o.lang || 'en') + ',eng,en';
  const args = [
    o.url,
    '--fs', '--force-window=immediate', '--no-terminal', '--keep-open=no',
    '--title=' + (o.title || 'Red Carpet Streams'),
    '--input-ipc-server=' + PIPE,
    '--alang=' + lang,
    '--user-agent=Mozilla/5.0 (Windows NT 10.0; Win64; x64) RedCarpetStreams'
  ];
  if (o.start > 0) args.push('--start=' + Math.floor(o.start));
  const st = { proc: spawn(exe, args, { windowsHide: false }), got: false, pos: 0, dur: 0, eof: false, sock: null };
  mpv = st;

  /* Follow playback position through mpv's control pipe, so "resume" and "continue watching" work. */
  let tries = 0;
  st.connectT = setInterval(() => {
    if (st.sock || tries++ > 60) { clearInterval(st.connectT); return; }
    const s = net.connect(PIPE);
    s.on('connect', () => {
      st.sock = s;
      ['time-pos', 'duration', 'eof-reached'].forEach((name, i) => s.write(JSON.stringify({ command: ['observe_property', i + 1, name] }) + '\n'));
    });
    let buf = '';
    s.on('data', d => {
      buf += d;
      let i;
      while ((i = buf.indexOf('\n')) >= 0) {
        const line = buf.slice(0, i); buf = buf.slice(i + 1);
        try {
          const m = JSON.parse(line);
          if (m.event !== 'property-change') continue;
          if (m.name === 'time-pos' && m.data != null) { st.got = true; st.pos = m.data; }
          else if (m.name === 'duration' && m.data) st.dur = m.data;
          else if (m.name === 'eof-reached' && m.data) st.eof = true;
        } catch (err) { }
      }
    });
    s.on('error', () => { try { s.destroy(); } catch (err) { } if (st.sock === s) st.sock = null; });
  }, 250);
  st.tickT = setInterval(() => { if (st.got) tell({ type: 'time', pos: st.pos, dur: st.dur }); }, 2000);

  const done = code => {
    clearInterval(st.connectT); clearInterval(st.tickT);
    try { if (st.sock) st.sock.destroy(); } catch (err) { }
    if (mpv === st) mpv = null;
    const ended = st.eof || (st.dur > 0 && st.pos >= st.dur - 5);
    tell({ type: 'time', pos: st.pos, dur: st.dur });
    tell({ type: 'exit', code, failed: !st.got && code !== 0, ended });
    if (win && !win.isDestroyed()) { win.show(); win.focus(); }
  };
  st.proc.on('exit', code => done(code));
  st.proc.on('error', () => done(-1));
  return { ok: true };
});

ipcMain.handle('mpv-stop', () => stopMpv());
ipcMain.handle('fullscreen', () => { if (win) win.setFullScreen(!win.isFullScreen()); });
ipcMain.handle('quit', () => app.quit());

app.whenReady().then(createWindow);
app.on('window-all-closed', () => { stopMpv(); app.quit(); });
