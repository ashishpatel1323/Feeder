// Feeder for Mac: the web app in its own window, with a built-in server on 127.0.0.1
// (feed fetching, sync proxy, yt-dlp video saving). Data lives in this app's own storage.
const { app, BrowserWindow, Menu, shell } = require('electron')
const path = require('node:path')
const os = require('node:os')

// A fixed port keeps the same origin, so the app's saved articles and settings stay put
const PORT = Number(process.env.FEEDER_PORT) || 47815
const ORIGIN = `http://127.0.0.1:${PORT}`

// Apps started from Finder get a bare PATH: add the usual places of yt-dlp and ffmpeg
const home = os.homedir()
process.env.PATH = [`${home}/.local/bin`, `${home}/homebrew/bin`, '/opt/homebrew/bin', '/usr/local/bin', process.env.PATH].join(':')
// No database here: sync goes through the deployed Feeder sync server
process.env.SYNC_UPSTREAM = process.env.SYNC_UPSTREAM || 'https://feeder-web-snowy.vercel.app'

if (!app.requestSingleInstanceLock()) app.quit()

let win = null

function createWindow() {
  win = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 720,
    minHeight: 500,
    title: 'Feeder',
    backgroundColor: '#111311',
    webPreferences: { contextIsolation: true, sandbox: true },
  })
  win.loadURL(ORIGIN)
  // Article links and "Open on YouTube" open in the default browser
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:/i.test(url)) shell.openExternal(url)
    return { action: 'deny' }
  })
  win.webContents.on('will-navigate', (e, url) => {
    if (!url.startsWith(ORIGIN)) {
      e.preventDefault()
      shell.openExternal(url)
    }
  })
  win.on('closed', () => (win = null))
}

app.on('second-instance', () => {
  if (!win) return
  if (win.isMinimized()) win.restore()
  win.focus()
})

app.whenReady().then(async () => {
  const { startServer } = require('./dist/server.cjs')
  const root = path.join(__dirname, 'web')
  try {
    await startServer(root, PORT)
  } catch (e) {
    const { dialog } = require('electron')
    dialog.showErrorBox('Feeder could not start', `Port ${PORT} is in use (${e && e.message}). Quit the other copy of Feeder and try again.`)
    app.quit()
    return
  }
  Menu.setApplicationMenu(
    Menu.buildFromTemplate([
      { role: 'appMenu' },
      { role: 'editMenu' },
      {
        label: 'View',
        submenu: [{ role: 'reload' }, { role: 'forceReload' }, { role: 'toggleDevTools' }, { type: 'separator' }, { role: 'resetZoom' }, { role: 'zoomIn' }, { role: 'zoomOut' }, { type: 'separator' }, { role: 'togglefullscreen' }],
      },
      { role: 'windowMenu' },
    ]),
  )
  createWindow()
  app.on('activate', () => {
    if (!win) createWindow()
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit()
})
