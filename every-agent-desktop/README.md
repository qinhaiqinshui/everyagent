# Every Agent 桌面版(every-agent-desktop)

用 Electron 把 **frontend + hub + worker** 全部在本地打包运行,产出 Windows x64 便携版 + NSIS 安装包,开箱即用。

## 架构

```
┌────────────────────── every-agent-desktop(Electron) ──────────────────────┐
│  main 进程                                                                 │
│   ├─ spawn hub.jar   (jlink JRE javaw.exe,127.0.0.1:6101,等 /health)      │
│   ├─ spawn worker.jar (连 ws://127.0.0.1:6101/ws,复用 EVERYAGENT_HOME)    │
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
  "hubPort": 6101,
  "workerPort": 6102
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
- **优雅退出**:托盘「退出桌面」停 hub,worker 保留运行(下次启动自动复用);「全部退出」停 hub + 对所有 worker 发 `POST /admin/shutdown` 触发 Spring 优雅关闭——desktop 自己启动的 worker HTTP shutdown 超时后 `child.kill()` 兜底,外部 worker 超时只记日志(不按端口强杀,零误杀风险)。

## 管理端点(仅 worker,仅本机)

worker(:6102)提供两个管理端点,用于外部进程探测与优雅关闭:

| 端点 | 认证 | 说明 |
|---|---|---|
| `GET /admin/identify` | `X-Admin-Key` | 返回 `{"service":"worker","workerId":"..."}` |
| `POST /admin/shutdown` | `X-Admin-Key` | 延迟 500ms 触发 `ApplicationContext.close()` 优雅关闭 |

- **认证**:`X-Admin-Key` 与 hubs[0].apiKey 明文比对。
- **安全**:仅监听 127.0.0.1(外部网络不可达);POST + 自定义请求头(浏览器不会自动携带,防 CSRF)。
- **密钥来源**:desktop-config.json 中的 workerApiKey。
- **hub 无 admin 端点**:hub 可能公网部署,暴露 shutdown 接口会被持有 hubKey 的人关掉,故不提供。hub 始终由 desktop 独占管理,退出时 `child.kill` 停止。

## 独立 worker 启动(无 GUI 场景)

安装包内附带 `resources/start-backend.bat`,可在无 Desktop GUI 的情况下独立启动 worker(hub 仍由 desktop 管理,不在此启动)。
适配 Windows 任务计划程序"系统启动时"触发器(Session 0 无 GUI 场景,如服务器/无人值守机器)。

**任务计划程序配置**:

1. 触发器选"**登录时**"(推荐)或"系统启动时"(后者需最高权限且因 Session 0 无 GUI 仅 worker 运行)。
2. 操作 → 启动程序:程序填 `<安装根>\resources\start-backend.bat`。
3. 脚本会自动检测 worker 是否已在运行(端口复用),避免重复启动。
4. worker 启动后自动重试连接 hub;desktop 后续打开时检测到已有 worker,不重复启动。

**Desktop 交互**:
- Desktop 启动时:hub 直接 spawn(检查端口是否被占);worker 调 `GET /admin/identify`(认证探测)判断是否已在运行。
- worker 已在运行 → 跳过启动,直接复用(日志显示"外部进程")。
- worker 端口被别的程序占用(认证失败) → 报错提示端口冲突。
- 托盘「退出桌面」:停 hub,worker 保留运行(下次启动自动复用)。
- 托盘「全部退出」:停 hub + 对所有 worker 发 `POST /admin/shutdown` 触发 Spring 优雅关闭。
