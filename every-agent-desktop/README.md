# Every Agent 桌面版(every-agent-desktop)

用 Electron 把 **frontend + hub + worker** 全部在本地打包运行,产出 Windows x64 便携版 + NSIS 安装包,开箱即用。

## 架构

```
┌────────────────────── every-agent-desktop(Electron) ──────────────────────┐
│  main 进程                                                                 │
│   ├─ spawn hub.jar   (env 注入 HUB_KEY=配置密钥,127.0.0.1:6101,等 /health) │
│   ├─ spawn worker.jar (凭据经 desktop-worker.yml 注入,连本机+用户远程 hub) │
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
  "hubKey": "safe:<safeStorage 密文>",
  "workerApiKey": "safe:<safeStorage 密文>",
  "workerId": "company-pc",
  "hubPort": 6101,
  "workerPort": 6102
}
```

- `hubKey` / `workerApiKey` **不内置任何默认密钥**(安全整改:历史硬编码默认密钥已随仓库公开)。
  首次启动(文件不存在或字段为空)自动生成 32 字节随机密钥(base64url),并经 Electron
  `safeStorage`(Windows DPAPI,绑定当前用户与机器)加密后以 `safe:` 前缀的密文形态落盘到该
  文件——磁盘上不再出现明文密钥,明文仅在运行中的内存里持有。旧版本已落盘的明文密钥在下次
  启动时**一次性迁移**为密文(值不变,ownerKey 数据归属不漂移);密文解密失败(换机器/系统
  凭据库重置/文件损坏)时重新生成随机密钥并告警。`safeStorage` 不可用的受限环境自动降级为
  明文存储并告警(行为与历史版本一致,不阻塞启动)。
- hub 子进程启动时以 `hubKey` 显式注入 `HUB_KEY` 环境变量(覆盖任何继承);worker 连接
  本机 hub 的 `api-key` / `hub-key` 由桌面版生成的 `<EVERYAGENT_HOME>/desktop/desktop-worker.yml`
  注入(`--spring.config.additional-location`,优先级高于 jar 内默认值与用户覆盖文件),
  与 hub 凭据始终同源一致——不会落回 jar 内公开默认密钥。**该文件同时是 worker 的最终
  hub 列表**:Spring 对 list 属性跨文件整表替换,故生成时会合并用户
  `application-worker.yaml` 的 `worker.hubs` 远程条目(本机条目恒为 hubs[0]、回环条目剔除、
  `${HUB_KEY:默认值}` 占位符还原为字面默认值),否则用户自配的远程 hub 会被覆盖丢失。
- 其余字段缺失/非法 → 回退默认值。
- `EVERYAGENT_HOME` 未设置时默认 `~/.everyagent`。

> 要与命令行方式共用同一批任务:`workerApiKey` 决定 ownerKey 数据归属,须与命令行 worker
> 配置(`<EVERYAGENT_HOME>/application-worker.yaml` 中 `worker.hubs[].api-key`)一致,
> `workerId` 同理;桌面版 hub 的密钥以本文件 `hubKey` 为准(worker 由桌面版自动接线)。

## 日志

- 桌面版主进程 → `<EVERYAGENT_HOME>/logs/desktop.log`:从进程启动第一行开始记录,包含
  `pid / 平台 / argv / home / logsDir`、配置加载、窗口创建、后端 jar 检查与 spawn(含 java 可执行文件、
  pid、退出码)、hub/worker 健康检查、静态服务端口、前端页面加载、以及未捕获异常/渲染进程崩溃等,用于排查
  “进程在但窗口不出现 / 后端没起来” 这类问题。
- 启动占位页会实时显示启动进度(与 `desktop.log` 同源,经 IPC 推送);启动失败时窗口内也会给出错误信息与日志目录。
- worker → `<EVERYAGENT_HOME>/logs/worker.log`(每日滚动,保留 7 天,见 worker logback-spring.xml)
- hub → `<EVERYAGENT_HOME>/logs/hub.log`(由 hub 内置 logback-spring.xml 指定,目录读 EVERYAGENT_HOME)
- 进程 stdout/stderr → `<EVERYAGENT_HOME>/logs/{hub,worker}.out.log`(为空通常表示 java 进程未真正启动,
  需配合 `desktop.log` 查看 spawn 是否失败/健康检查是否超时)
- **安装/卸载器 → `<EVERYAGENT_HOME>/logs/uninstall.log`(安装与卸载共用,追加式)**:
  `build/installer.nsh` 在 NSIS 安装/卸载器的各阶段(`customInit`/`customUnInstallCheck`/`customInstall`/
  `customInstallmode`/`customUnInit`/`customRemoveFiles`/`customUnInstall`)各写一行带时间戳的日志,
  用于排查「卸载器一秒内退出并提示成功,但安装目录/快捷方式/沙箱账户全部残留」的问题。解读:
  - 卸载后**没有任何 `uninstaller un.onInit done` 新行** → 卸载器进程在初始化完成前就异常退出
    (NSIS 自拷贝到 `%TEMP%\~nsu.tmp` 或壳层阶段失败;可配合「事件查看器 → Windows 日志 → 应用程序」的
    应用错误/WER 记录与杀软排查)。
  - 有 `un.onInit done` 但没有 `un.install section` 行 → 向导页面中途退出(用户取消/模式选择页异常)。
  - 有 `removing files` 且 `INSTDIR STILL CONTAINS FILES` → 删除被文件占用打断(如 worker javaw 未退出)。
  - 卸载务必走「设置→应用→卸载」或安装目录下的 `Uninstall Every Agent.exe` 直接双击,不要自带参数。
  - `delete-probe` 行:卸载器初始化时对 INSTDIR 做真实删除权探针;`FAILED -> relaunching ELEVATED` 表示检测到
    「管理员身份安装 + 普通身份卸载」错配,已自动弹 UAC 以管理员重跑;`FAILED even after elevated retry` 则强烈
    提示有安全软件在拦截删除操作(此时请查杀软的拦截记录)。
  - `forensic walk` 行:RMDir 失败后逐项枚举 INSTDIR 顶层条目并记录前 30 个删除失败项,用于区分「被锁」与「拒绝访问」。
  - `installer account type`:安装时若为 Admin(提权安装),产物文件/快捷方式/注册表将归 Administrators 所有,
    是「卸载删不动」一类问题的常见源头。
  - `installer real user`:安装器进程令牌里的真实用户名(UserInfo::GetName,免疫环境变量伪装)。若与当前登录
    用户不符,说明安装器跑在别的账户/沙箱上下文里,注册表与快捷方式会写进那个账户——对当前用户即表现为
    「装完了却什么都没有」。
  - `registry write probe`:安装开始时对安装注册表键做真实写读测试;失败且非静默、非提权时,安装器会带
    `/eaNoElevate` 守护参数自动弹 UAC 提权重跑一次(自愈「普通安装静默丢失注册表/快捷方式」)。
  - 已知坑:管理员身份装过的版本,其文件归 Administrators 所有,之后普通身份覆盖安装会因无法写入旧文件而失败
    (典型报错:无法写入 Uninstall Every Agent.exe)。处理:先用管理员卸载旧版本,再普通安装即可;此后一直用普通
    安装则不会再遇到。


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

- **凭据接线(安全整改)**:`desktop-config.json` 首启自动生成随机 `hubKey` / `workerApiKey`
  (已有非空值不覆盖、不重写),并经 Electron `safeStorage`(DPAPI,绑定用户+机器)以 `safe:`
  前缀密文落盘,旧明文在下次启动一次性迁移为密文(`safe-storage.ts` 是唯一 electron 装配点,
  `credentials.ts` 零 electron 依赖、node:test 可单测;密文损坏重生成并告警);hub 经子进程 env
  `HUB_KEY` 显式注入(覆盖任何继承,杜绝落回 jar 内公开默认密钥),worker 经每次启动前重写的
  `<EVERYAGENT_HOME>/desktop/desktop-worker.yml`
  (`--spring.config.additional-location`,实测优先级高于 jar 内默认值与
  `application-worker.yaml` 用户覆盖)拿到同一组凭据——worker 与 hub 凭据恒一致;
  该文件同时写全最终 hub 列表(hubs[0]=本机;用户 `worker.hubs` 的远程条目合并进来,
  回环剔除、`${HUB_KEY:默认值}` 还原为字面值),防 Spring list 整表替换吃掉用户远程 hub。
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
5. 凭据:直接复用桌面端生成的 `<EVERYAGENT_HOME>/desktop/desktop-worker.yml` 注入 worker
   (与桌面版主程序同一份文件;`desktop-config.json` 中的密钥已改为 safeStorage 密文落盘,
   脚本无法解密,故不再从配置拼装该文件)。若文件不存在,脚本报错退出——请先启动一次
   桌面端完成首启密钥生成(自动生成随机密钥并持久化,随后即生成该 yml)。

**Desktop 交互**:
- Desktop 启动时:hub 直接 spawn(检查端口是否被占);worker 调 `GET /admin/identify`(认证探测)判断是否已在运行。
- worker 已在运行 → 跳过启动,直接复用(日志显示"外部进程")。
- worker 端口被别的程序占用(认证失败) → 报错提示端口冲突。
- 托盘「退出桌面」:停 hub,worker 保留运行(下次启动自动复用)。
- 托盘「全部退出」:停 hub + 对所有 worker 发 `POST /admin/shutdown` 触发 Spring 优雅关闭。
