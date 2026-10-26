import { app, BrowserWindow, dialog, shell } from 'electron'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { DEFAULT_PORT, findJarIn, resolveJar, startBackend, stopBackend, waitForHealth } from './backend.js'

/**
 * Electron 主进程（ADR-012 阶段六）。
 *
 * 三条与"网页版"不同、必须由壳负责的事：
 *  1. 起停后端（jar）并保证**退出时不留孤儿 java 进程**；
 *  2. 后端没起来/起不来时给可读的错误，而不是白屏；
 *  3. 单实例：双击两次不应起两个 java 抢同一个 8082。
 *
 * 加载策略：`loadURL('http://127.0.0.1:8082/')` —— 前端由同一个 jar 托管，
 * 因此路由 history 模式、`base '/'`、同源 API、cookie 全部与浏览器模式一致，前端零改动。
 * （`file://` 方案需要改 base、路由模式与 API 基址三处，且 SPA 深链会 404。）
 */
const repoRoot = join(fileURLToPath(new URL('.', import.meta.url)), '..', '..')
let backend = null
let mainWindow = null

if (!app.requestSingleInstanceLock()) {
  // 已有实例：直接退出，让已存在的窗口获得焦点（见 second-instance）
  app.quit()
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore()
      mainWindow.focus()
    }
  })
  app.whenReady().then(boot)
}

async function boot() {
  const dataDir = join(app.getPath('userData'), 'workspace')
  const logLines = []
  try {
    // 打包后 jar 在 resources/server 下（由 electron-builder 的 extraResources 放进去）；
    // 开发态仍从 server/target 找 —— 两条路径都要能跑，否则"装完打不开"要到用户手里才发现。
    const jarPath = app.isPackaged
      ? findJarIn(join(process.resourcesPath, 'server'))
      : resolveJar(repoRoot)
    backend = startBackend({
      jarPath,
      dataDir,
      port: DEFAULT_PORT,
      onLog: chunk => {
        logLines.push(chunk)
        if (logLines.length > 200) logLines.shift()
      },
    })
    backend.once('exit', code => {
      if (code !== 0 && code !== null) {
        dialog.showErrorBox('本地服务已退出', `退出码 ${code}\n\n最近日志：\n${logLines.join('').slice(-2000)}`)
      }
    })
    await waitForHealth({ port: DEFAULT_PORT })
    createWindow()
  } catch (error) {
    dialog.showErrorBox('本地服务启动失败', `${error.message}\n\n最近日志：\n${logLines.join('').slice(-2000)}`)
    await shutdown(0)
  }
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1280,
    height: 900,
    title: '智历',
    webPreferences: {
      // 页面本身就是本地服务提供的 SPA，不需要 Node 能力；关掉能显著缩小攻击面
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
    },
  })
  // 外链走系统浏览器，不在应用内开新窗口
  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    void shell.openExternal(url)
    return { action: 'deny' }
  })
  mainWindow.on('closed', () => {
    mainWindow = null
  })
  void mainWindow.loadURL(`http://127.0.0.1:${DEFAULT_PORT}/`)
}

async function shutdown(exitCode = 0) {
  await stopBackend(backend)
  backend = null
  app.exit(exitCode)
}

app.on('window-all-closed', () => {
  void shutdown(0)
})

// 托盘/任务管理器结束进程时也要带走后端；SIGINT 便于开发期 Ctrl+C
for (const signal of ['SIGINT', 'SIGTERM']) {
  process.on(signal, () => {
    void shutdown(0)
  })
}
