/**
 * Electron 主进程入口:尽早落盘日志 → 单实例锁 → 加载配置 → 建窗口(启动占位)
 * → 启动后端(hub/worker)→ 后端就绪后加载前端静态站。退出时优雅停 worker 再停 hub。
 * 启动过程会同步写入 <EVERYAGENT_HOME>/logs/desktop.log,并通过 IPC 推送到启动占位页。
 */
import { app, BrowserWindow, ipcMain, Menu, nativeImage, Tray } from 'electron'
import { join } from 'node:path'
import { appendFileSync, existsSync, mkdirSync, readFileSync } from 'node:fs'
import {
  bootstrapFor,
  loadConfig,
  resolveHome,
  resolveGpuWorkaround,
  pathsFor,
  type DesktopBootstrap,
  type DesktopConfig,
  type DesktopPaths,
} from './config'
import { startBackend, type BackendHandles } from './backend'
import { startStaticServer, type StaticServer } from './static-server'
import { registerNotifyIpc } from './notify'
import { webRoot } from './paths'

// 必须在 app ready 之前、越早越好:禁用 GPU 硬件加速,用软件渲染。
// 修复「双击打开一闪而过」:无独立显卡/远程桌面/虚拟机/受完整性级别限制的环境下,
// Chromium GPU 进程启动失败(error_code=18)→ FATAL "GPU process isn't usable" → 整个应用退出。
app.disableHardwareAcceleration()
// GPU 兼容开关(配置项 gpuWorkaround,默认启用;仅显式写 false 才关闭):
// 追加以下绕过开关,解决无独显/远程桌面/受限会话下 GPU 子进程创建失败
// (error_code=18)→ FATAL 崩溃的问题。必须在 app ready 之前设置。
try {
  if (resolveGpuWorkaround(resolveHome())) {
    app.commandLine.appendSwitch('in-process-gpu')
    app.commandLine.appendSwitch('disable-gpu-compositing')
    app.commandLine.appendSwitch('disable-breakpad')
    app.commandLine.appendSwitch('noerrdialogs')
  }
} catch {
  /* 读取配置失败,保持默认(不启用) */
}

let mainWindow: BrowserWindow | null = null
let tray: Tray | null = null
let backend: BackendHandles | null = null
let staticServer: StaticServer | null = null
let bootstrap: DesktopBootstrap | null = null
let cfg: DesktopConfig | null = null
let quitting = false

interface StartupLine {
  time: string
  text: string
}

const startupLines: StartupLine[] = []
let home = ''
let paths: DesktopPaths | null = null
let logsDir = ''

const originalConsole = { ...console }

function safeStringify(v: unknown): string {
  if (v instanceof Error) return v.stack ?? v.message
  try {
    return JSON.stringify(v)
  } catch {
    return String(v)
  }
}

/** 主进程自身日志落盘 <EVERYAGENT_HOME>/logs/desktop.log,并透传原 console。 */
function installMainLogFile(dir: string): void {
  try {
    mkdirSync(dir, { recursive: true })
  } catch (error) {
    originalConsole.error('[desktop] 创建日志目录失败:', error)
    return
  }
  const file = join(dir, 'desktop.log')
  const emit = (level: string, args: unknown[]): void => {
    const line = `[${new Date().toISOString()}] [${level}] ${args
      .map((a) => (typeof a === 'string' ? a : safeStringify(a)))
      .join(' ')}`
    try {
      appendFileSync(file, line + '\n', 'utf8')
    } catch {
      /* 日志写入失败不阻塞主流程 */
    }
    if (level === 'ERROR') originalConsole.error(...args)
    else originalConsole.log(...args)
  }
  console.log = (...args: unknown[]) => emit('INFO', args)
  console.info = (...args: unknown[]) => emit('INFO', args)
  console.warn = (...args: unknown[]) => emit('WARN', args)
  console.error = (...args: unknown[]) => emit('ERROR', args)
}

/** 记录一条启动进度:同时写 desktop.log 与推送给启动页渲染进程。 */
function pushStatus(text: string): void {
  const time = new Date().toISOString()
  startupLines.push({ time, text })
  if (startupLines.length > 500) startupLines.shift()
  console.info('[startup] ' + text)
  broadcastStatus(`[${time}] ${text}`)
}

function broadcastStatus(line: string): void {
  if (!app.isReady()) return
  for (const win of BrowserWindow.getAllWindows()) {
    try {
      win.webContents.send('desktop:startup-status', line)
    } catch {
      /* 窗口尚未就绪时忽略 */
    }
  }
}

function registerIpc(): void {
  registerNotifyIpc()
  ipcMain.handle('desktop:get-bootstrap', () => bootstrap)
  ipcMain.handle('desktop:get-startup-status', () =>
    startupLines.map((l) => `[${l.time}] ${l.text}`),
  )

  // 自定义标题栏窗口控制:最小化 / 最大化·还原 / 关闭 / 查询最大化状态。
  // 用事件来源定位窗口,避免依赖闭包 mainWindow(多窗口/窗口重建更健壮)。
  ipcMain.handle('desktop:window-minimize', (event) => {
    BrowserWindow.fromWebContents(event.sender)?.minimize()
  })
  ipcMain.handle('desktop:window-toggle-maximize', (event) => {
    const win = BrowserWindow.fromWebContents(event.sender)
    if (!win) return
    if (win.isMaximized()) win.unmaximize()
    else win.maximize()
  })
  ipcMain.handle('desktop:window-close', (event) => {
    BrowserWindow.fromWebContents(event.sender)?.close()
  })
  ipcMain.handle('desktop:window-is-maximized', (event) => {
    return BrowserWindow.fromWebContents(event.sender)?.isMaximized() ?? false
  })
}

function installProcessHandlers(): void {
  process.on('uncaughtException', (error) => {
    console.error('[desktop] 未捕获异常:', error)
  })
  process.on('unhandledRejection', (reason) => {
    console.error('[desktop] 未处理的 Promise 拒绝:', reason)
  })
  app.on('render-process-gone', (_event, webContents, details) => {
    console.error('[desktop] 渲染进程异常退出:', details)
  })
}

// 1) 尽早解析 home/logsDir 并接管 console,保证后续每一步都有日志。
try {
  home = resolveHome()
  paths = pathsFor(home)
  logsDir = paths.logsDir
  installMainLogFile(logsDir)
  installProcessHandlers()
  pushStatus(
    `进程启动 pid=${process.pid} packaged=${app.isPackaged} platform=${process.platform} argv=${JSON.stringify(process.argv)}`,
  )
  pushStatus(`运行时目录 home=${home}`)
  pushStatus(`日志目录 logsDir=${logsDir}`)
} catch (error) {
  originalConsole.error('[desktop] 启动初始化失败:', error)
}

const gotLock = app.requestSingleInstanceLock()
if (!gotLock) {
  pushStatus('未获取单实例锁(已有实例在运行),退出')
  app.quit()
} else {
  app.on('second-instance', () => {
    pushStatus('收到二次启动请求,显示已有窗口')
    showMainWindow()
  })

  registerIpc()
  void startup()
}

async function startup(): Promise<void> {
  try {
    // 极少数情况下早期初始化失败(home 解析异常),这里兜底重试一次。
    if (!paths) {
      home = resolveHome()
      paths = pathsFor(home)
      logsDir = paths.logsDir
      installMainLogFile(logsDir)
      pushStatus(`运行时目录 home=${home}`)
    }

    pushStatus('加载运行时配置...')
    cfg = loadConfig(home)
    bootstrap = bootstrapFor(cfg)
    pushStatus(
      `配置就绪: hubPort=${cfg.hubPort}, workerPort=${cfg.workerPort}, workerId=${cfg.workerId}, gpuWorkaround=${cfg.gpuWorkaround}`,
    )

    await app.whenReady()
    // 无边框自绘标题栏:去掉系统默认菜单(含 Alt 键临时弹出的菜单)。
    Menu.setApplicationMenu(null)
    // Windows toast 通知归属:与 electron-builder appId 一致,
    // 保证桌面系统通知正常显示(否则可能不弹或显示为 Electron)。
    app.setAppUserModelId('dev.everyagent.desktop')
    pushStatus('app 已就绪,创建启动窗口...')
    createWindow(startingPage())
    createTray()

    pushStatus('启动本地后端(hub / worker)...')
    backend = await startBackend(cfg, paths, pushStatus)

    // worker 实际 workerId 可能与 desktop-config.json 的配置值不一致(worker 可经
    // application-worker.yaml / WORKER_ID 覆盖默认)。前端建连与 tasks.list 等 RPC 必须以
    // worker 实际 workerId 定向 cmd 频道,否则启动时发到「配置名」频道落空,任务列表恒空,
    // 只能去设置页手动「保存并连接」重建后恢复。这里用 /health 上报的真实值与 bootstrap 对齐。
    if (backend.workerId && backend.workerId !== cfg.workerId) {
      pushStatus(`worker 实际 workerId=${backend.workerId}(配置=${cfg.workerId}),已对齐 bootstrap`)
      bootstrap = bootstrapFor({ ...cfg, workerId: backend.workerId })
    }

    const indexHtml = join(webRoot(), 'index.html')
    if (!existsSync(indexHtml)) {
      throw new Error('未找到 resources/web/index.html,请先运行 npm run build:web')
    }
    pushStatus(`启动本地静态服务(root=${webRoot()})...`)
    staticServer = await startStaticServer(webRoot())
    pushStatus(`静态服务就绪: ${staticServer.url}`)

    if (mainWindow && !mainWindow.isDestroyed()) {
      pushStatus(`加载前端页面: ${staticServer.url}`)
      void mainWindow.loadURL(staticServer.url)
    }
  } catch (error) {
    const message = error instanceof Error ? error.stack ?? error.message : String(error)
    pushStatus(`启动失败: ${message}`)
    console.error('[desktop] 启动失败:', error)
    try {
      await app.whenReady()
      if (mainWindow && !mainWindow.isDestroyed()) {
        // errorPage 返回 HTML 字符串,须编码为 data: URL 再 loadURL;直接传裸 HTML
        // 会触发 ERR_INVALID_URL(且 did-fail-load 打印超长 URL)。
        void mainWindow.loadURL(
          'data:text/html;charset=utf-8,' + encodeURIComponent(errorPage(message, logsDir)),
        )
      } else {
        createWindow(errorPage(message, logsDir))
      }
    } catch (error2) {
      console.error('[desktop] 显示错误页失败:', error2)
    }
  }
}

/** 显示/聚焦主窗口:托盘点击、二次启动、activate 共用。窗口从未销毁,show 即恢复。 */
function showMainWindow(): void {
  if (mainWindow && !mainWindow.isDestroyed()) {
    if (mainWindow.isMinimized()) mainWindow.restore()
    mainWindow.show()
    mainWindow.focus()
  }
}

/** 创建系统托盘:后台运行时的恢复入口与真正退出入口。 */
function createTray(): void {
  try {
    // 复用项目 logo 图标(开发模式 build/icon.png;打包后同路径打进 asar)。
    const iconPath = join(app.getAppPath(), 'build', 'icon.png')
    if (!existsSync(iconPath)) {
      pushStatus('未找到托盘图标,跳过系统托盘')
      return
    }
    const image = nativeImage.createFromPath(iconPath)
    if (image.isEmpty()) {
      pushStatus('托盘图标无效,跳过系统托盘')
      return
    }
    tray = new Tray(image.resize({ width: 16, height: 16 }))
    tray.setToolTip('Every Agent')
    tray.setContextMenu(
      Menu.buildFromTemplate([
        { label: '显示 Every Agent', click: showMainWindow },
        { type: 'separator' },
        {
          label: '退出',
          click: () => {
            pushStatus('托盘菜单:退出应用')
            app.quit()
          },
        },
      ]),
    )
    tray.on('click', showMainWindow)
    tray.on('double-click', showMainWindow)
    pushStatus('系统托盘已创建')
  } catch (error) {
    console.error('[desktop] 创建系统托盘失败:', error)
  }
}

function createWindow(html: string): void {
  // 开发模式(npm start)下 Electron 仍用 electron.exe 默认图标,显式设置窗口图标
  // 让任务栏按钮显示项目 logo;打包后 exe 已内嵌图标,该文件不存在则回退 undefined。
  const devIcon = join(app.getAppPath(), 'build', 'icon.png')
  const win = new BrowserWindow({
    width: 1280,
    height: 820,
    minWidth: 900,
    minHeight: 620,
    title: 'Every Agent',
    // 去掉系统边框与标题栏,标题栏由前端自绘(frame: false)。
    frame: false,
    icon: existsSync(devIcon) ? devIcon : undefined,
    // 与启动占位页背景一致,避免 frameless 下窗口出现瞬间的白底闪烁。
    backgroundColor: '#fafafa',
    // 直接显示,不依赖 ready-to-show(某些环境下该事件可能不触发,导致进程在但窗口一直不出现)。
    show: true,
    webPreferences: {
      preload: join(__dirname, '..', 'preload', 'index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false,
    },
  })
  mainWindow = win

  // 最大化/还原状态变化推送渲染进程,供标题栏按钮切换图标。
  const emitMaximizedChanged = (): void => {
    if (win.isDestroyed()) return
    try {
      win.webContents.send('desktop:window-maximized-changed', win.isMaximized())
    } catch {
      /* 渲染进程尚未就绪时忽略 */
    }
  }
  win.on('maximize', emitMaximizedChanged)
  win.on('unmaximize', emitMaximizedChanged)

  // 关闭按钮(及 Alt+F4 等关闭途径)→ 隐藏窗口,程序后台运行,不退出。
  // 真正退出走托盘菜单「退出」→ before-quit → 停止后端 → app.exit,
  // 该流程经 before-quit preventDefault 中止,不会走到这里的 close 拦截。
  win.on('close', (event) => {
    pushStatus('收到窗口关闭请求,隐藏到托盘后台运行')
    event.preventDefault()
    win.hide()
  })

  win.once('ready-to-show', () => {
    pushStatus('启动窗口 ready-to-show')
  })
  win.webContents.on('did-finish-load', () => {
    // 不打印 URL:启动占位页是 data:text/html,URL 携带整段 HTML,会刷屏。
    pushStatus('页面加载完成')
  })
  win.webContents.on('did-fail-load', (_event, code, desc, url) => {
    // url 可能携带整段 HTML(data: URL)或超长地址,只保留描述性信息并截断,避免刷屏。
    const short = url && url.length > 120 ? `${url.slice(0, 120)}…` : url
    pushStatus(`页面加载失败 code=${code} desc=${desc} url=${short}`)
  })
  win.on('closed', () => {
    if (mainWindow === win) mainWindow = null
  })

  pushStatus('加载启动占位页...')
  void win.loadURL('data:text/html;charset=utf-8,' + encodeURIComponent(html))
}

function startingPage(): string {
  return `<!doctype html>
<meta charset="utf-8">
<style>
  body{font-family:system-ui;background:#fafafa;color:#333;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;padding-top:32px}
  .card{width:min(680px,90vw)}
  h1{font-size:18px;font-weight:600;margin:0 0 12px}
  #log{list-style:none;margin:0;padding:10px 12px;background:#fff;border:1px solid #eee;border-radius:8px;max-height:55vh;overflow:auto;font:12px/1.6 ui-monospace,Consolas,monospace}
  #log li{white-space:pre-wrap;word-break:break-all}
</style>
${windowControlsSnippet()}
<div class="card">
  <h1>Every Agent 正在启动…</h1>
  <ul id="log"></ul>
</div>
<script>
  (async function () {
    var logEl = document.getElementById('log');
    function add(line) {
      var li = document.createElement('li');
      li.textContent = line;
      logEl.appendChild(li);
      while (logEl.children.length > 200) logEl.removeChild(logEl.firstChild);
    }
    try {
      var api = window.everyAgentDesktop;
      if (api && api.getStartupStatus) {
        var lines = await api.getStartupStatus();
        lines.forEach(add);
        api.onStartupStatus(function (line) { add(line); });
      }
    } catch (e) {
      add('无法读取启动状态: ' + e);
    }
  })();
</script>`
}

function errorPage(message: string, logsDir: string): string {
  const hubTail = readTail(join(logsDir, 'hub.out.log'), 4000)
  const workerTail = readTail(join(logsDir, 'worker.out.log'), 4000)
  const desktopTail = readTail(join(logsDir, 'desktop.log'), 4000)
  return (
    `<meta charset="utf-8">` +
    `<style>body{font-family:system-ui;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;color:#888;background:#fafafa;padding-top:32px}</style>` +
    windowControlsSnippet() +
    `<div style="max-width:90vw;width:900px;text-align:left"><h2 style="color:#c00">Every Agent 启动失败</h2>` +
    `<pre style="color:#c00;white-space:pre-wrap;text-align:left;background:#fff;border:1px solid #f0c0c0;border-radius:8px;padding:12px">${escapeHtml(message)}</pre>` +
    (logsDir ? `<p style="color:#999">日志目录:${escapeHtml(logsDir)}</p>` : '') +
    `<h3 style="margin:18px 0 6px">desktop.log 尾部</h3>` +
    `<pre style="white-space:pre-wrap;background:#fff;border:1px solid #eee;border-radius:8px;padding:12px;font:12px/1.6 ui-monospace,Consolas,monospace;max-height:220px;overflow:auto">${escapeHtml(desktopTail)}</pre>` +
    `<h3 style="margin:18px 0 6px">hub.out.log 尾部</h3>` +
    `<pre style="white-space:pre-wrap;background:#fff;border:1px solid #eee;border-radius:8px;padding:12px;font:12px/1.6 ui-monospace,Consolas,monospace;max-height:220px;overflow:auto">${escapeHtml(hubTail)}</pre>` +
    `<h3 style="margin:18px 0 6px">worker.out.log 尾部</h3>` +
    `<pre style="white-space:pre-wrap;background:#fff;border:1px solid #eee;border-radius:8px;padding:12px;font:12px/1.6 ui-monospace,Consolas,monospace;max-height:220px;overflow:auto">${escapeHtml(workerTail)}</pre>` +
    `</div>`
  )
}

/** 读取日志文件尾部(最多 maxChars 字符),文件不存在/读失败返回占位说明。 */
function readTail(file: string, maxChars: number): string {
  try {
    if (!existsSync(file)) return '(日志文件不存在)'
    const text = readFileSync(file, 'utf8')
    if (!text.trim()) return '(日志文件为空)'
    return text.length > maxChars ? '...(截断)\n' + text.slice(-maxChars) : text
  } catch (error) {
    return '(读取日志失败: ' + (error as Error).message + ')'
  }
}

function escapeHtml(s: string): string {
  return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

/**
 * 过渡页(启动占位 / 错误)的自绘标题栏:可拖拽 + 最小化 / 最大化·还原 / 关闭。
 * frameless 窗口下这些 data: URL 页面没有系统标题栏,需自行提供窗口控制;
 * preload 已对 data: URL 注入 everyAgentDesktop.windowControl,缺失时整条隐藏。
 */
function windowControlsSnippet(): string {
  return `<div id="dwbar" style="position:fixed;top:0;left:0;right:0;height:32px;display:flex;align-items:center;-webkit-app-region:drag;background:#fafafa;border-bottom:1px solid #eee;z-index:10">
  <div style="flex:1;min-width:0"></div>
  <button data-dw="min" style="-webkit-app-region:no-drag;width:46px;height:100%;border:none;background:transparent;color:#666;cursor:default" title="最小化">&#x2015;</button>
  <button data-dw="toggle" style="-webkit-app-region:no-drag;width:46px;height:100%;border:none;background:transparent;color:#666;cursor:default" title="最大化">&#x25A1;</button>
  <button data-dw="close" style="-webkit-app-region:no-drag;width:46px;height:100%;border:none;background:transparent;color:#666;cursor:default" title="关闭">&#x2715;</button>
</div>
<script>
(function () {
  var api = window.everyAgentDesktop && window.everyAgentDesktop.windowControl;
  var bar = document.getElementById('dwbar');
  if (!api || !bar) {
    if (bar) bar.style.display = 'none';
    return;
  }
  var toggle = bar.querySelector('[data-dw="toggle"]');
  var apply = function (max) {
    if (!toggle) return;
    toggle.title = max ? '还原' : '最大化';
    toggle.innerHTML = max ? '&#x25F0;' : '&#x25A1;';
  };
  if (api.onMaximizedChanged) api.onMaximizedChanged(apply);
  if (api.isMaximized) api.isMaximized().then(apply);
  bar.addEventListener('dblclick', function (e) {
    if (e.target.closest('[data-dw]')) return;
    if (api.toggleMaximize) api.toggleMaximize();
  });
  bar.addEventListener('click', function (e) {
    var btn = e.target.closest('[data-dw]');
    if (!btn) return;
    var action = btn.getAttribute('data-dw');
    if (action === 'min' && api.minimize) api.minimize();
    else if (action === 'toggle' && api.toggleMaximize) api.toggleMaximize();
    else if (action === 'close' && api.close) api.close();
  });
})();
</script>`
}

app.on('window-all-closed', () => {
  pushStatus('所有窗口已关闭,应用保持后台运行(可通过系统托盘恢复)')
  // 关闭即后台运行:不退出。真正退出走托盘菜单「退出」。
})

app.on('activate', () => {
  // macOS 点击 Dock 恢复窗口;Windows 主要走托盘/二次启动。
  if (mainWindow && !mainWindow.isDestroyed()) {
    showMainWindow()
    return
  }
  if (staticServer) {
    createWindow(startingPage())
    void mainWindow!.loadURL(staticServer.url)
  }
})

app.on('before-quit', (event) => {
  if (quitting) return
  quitting = true
  pushStatus('应用即将退出,正在停止后端...')
  event.preventDefault()
  void (async () => {
    try {
      if (backend) await backend.stop()
    } finally {
      if (staticServer) staticServer.close()
      pushStatus('后端已停止,退出')
      app.exit(0)
    }
  })()
})
