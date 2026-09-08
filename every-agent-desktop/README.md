# Every Agent 桌面版(every-agent-desktop)

用 Electron 把 **frontend + hub + worker** 全部在本地打包运行,产出 Windows x64 便携版 + NSIS 安装包,开箱即用。

## 架构

```
┌────────────────────── every-agent-desktop(Electron) ──────────────────────┐
│  main 进程                                                                 │
│   ├─ spawn hub.jar   (jlink JRE javaw.exe,127.0.0.1:9100,等 /health)      │
│   ├─ spawn worker.jar (连 ws://127.0.0.1:9100/ws,复用 EVERYAGENT_HOME)    │
│   └─ 本地静态服务 127.0.0.1:<随机端口> → 加载 resources/web(前端 dist)     │
│  renderer(前端,经 preload 注入 bootstrap)                                  │
│   └─ 自动 applyConfig(hub) + setWorkerApiKey(worker) → 开箱即用连接       │
└───────────────────────────────────────────────────────────────────────────┘
```

- 前端走 `http://127.0.0.1`(安全上下文,WebCrypto 可用),再经 WS 连本地 hub。
- worker 数据目录复用现有 `EVERYAGENT_HOME`(默认 `~/.everyagent`),与命令行/docker 方式看到同一批任务。

## 运行时配置

安装后,配置从 `<EVERYAGENT_HOME>/desktop-config.json` 读取(缺省用默认值):

```json
{
  "hubKey": "sljlw23948LKS",
  "workerApiKey": "dev-key",
  "workerId": "company-pc",
  "hubPort": 9100,
  "workerPort": 9200
}
```

- 文件不存在 → 首次启动自动生成带默认值的配置。
- 字段缺失/非法 → 回退默认值。
- `EVERYAGENT_HOME` 未设置时默认 `~/.everyagent`。

> 要与命令行方式共用同一批任务,`workerApiKey`(决定 ownerKey 数据归属)与 `workerId` 必须与
> 命令行 worker 一致;桌面版默认值已对齐 worker 生产默认(`dev-key` / `company-pc`)。

## 日志

- 桌面版主进程 → `<EVERYAGENT_HOME>/logs/desktop.log`:从进程启动第一行开始记录,包含
  `pid / 平台 / argv / home / logsDir`、配置加载、窗口创建、后端 jar 检查与 spawn(含 java 可执行文件、
  pid、退出码)、hub/worker 健康检查、静态服务端口、前端页面加载、以及未捕获异常/渲染进程崩溃等,用于排查
  “进程在但窗口不出现 / 后端没起来” 这类问题。
- 启动占位页会实时显示启动进度(与 `desktop.log` 同源,经 IPC 推送);启动失败时窗口内也会给出错误信息与日志目录。
- worker → `<EVERYAGENT_HOME>/logs/worker.log`(每日滚动,保留 7 天,见 worker logback-spring.xml)
- hub → `<EVERYAGENT_HOME>/logs/hub.log`(由主进程生成的 desktop-hub.yml 指定 `logging.file.name`)
- 进程 stdout/stderr → `<EVERYAGENT_HOME>/logs/{hub,worker}.out.log`(为空通常表示 java 进程未真正启动,
  需配合 `desktop.log` 查看 spawn 是否失败/健康检查是否超时)

## 构建与打包

前置:JDK 25(设 `JAVA_HOME`)、Maven、Node/npm、PowerShell(jlink 脚本)。

```bash
cd every-agent-desktop
npm install

# 分步
npm run build:backend   # mvn 打包 hub/worker jar → resources/backend/{hub,worker}.jar
npm run build:web       # 构建前端 dist → resources/web
npm run build:jre       # jlink 生成精简 JRE → resources/jre

# 一键打包(NSIS 安装包 + portable)
npm run dist
# 仅解包目录(端到端验证用)
npm run dist:dir
```

产物在 `every-agent-desktop/release/`。打包前确保 `resources/` 三个子目录已生成(build:assets 会依次执行)。

## 开发态运行

```bash
cd every-agent-desktop
npm install
npm run build:backend && npm run build:web && npm run build:jre
npm run compile
npm start
```

开发态(`app.isPackaged === false`)要求 `resources/web/index.html`、`resources/backend/*.jar` 存在;
JRE 缺失时回退系统 `java`(Windows 下 `windowsHide` 隐藏控制台)。

## 关键实现点

- **worker.hubs 覆盖**:Spring Boot 列表属性跨配置源按索引合并,仅覆盖 `[0]` 会残留 jar 内默认
  远端 hub。桌面版用 `--spring.config.additional-location` 注入生成的 `desktop-worker.yml`,把每个
  默认索引都写满(`[1]` 用空 url,worker `resolveHubs` 静默跳过),彻底剔除远端条目。
- **配置注入**:`desktop-hub.yml` / `desktop-worker.yml` 生成到 `<EVERYAGENT_HOME>/desktop/`,
  经 `--spring.config.additional-location=file:///...` 覆盖 jar 内默认值。
- **单实例锁**:二次启动聚焦已有窗口。
- **优雅退出**:`before-quit` 先 SIGTERM worker(触发落盘)再停 hub,超时强杀;退出无残留 java 进程。
