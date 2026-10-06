---
title: 故障排查
nav_order: 16
parent: guides
has_children: false
---

**一句话定位**：本篇是一张**以真实日志与报错文案为行**的排查表——现象列全部逐字来自 worker / 前端源码或实测输出（逐条标注 `文件:行号`），按「你在哪里看到它」分六个入口组；先在你能看到的地方 grep 到那条文案，再顺行读到修复命令。

## 1. 怎么用这张表

1. **确定入口组**：你在哪里看到异常？下表直达。
2. **逐字对号**：现象列是源码里原样的文案（`{}` / `<id>` 为占位）。用 §3 的 grep 命令把它找出来。
3. **顺行处理**：定位列给取证动作，修复列给命令；需要背景时点详解链接。

> 与[调试与测试](debugging-and-testing.md)的分工：那边讲**方法论**（测试桩、改-验循环、日志文案全集表），本篇是**对号速查**——同一批文案在这里按「现象 → 根因 → 修复」重排并补齐前端/构建/安装侧。

排查前先记住三个**结构性事实**，它们决定你能看到什么、该去哪看：

1. 插件加载发生在 worker 启动的 `@PostConstruct`（`PluginLoader.java:151-154` 的 `init()` → `scanAndLoad()`），**没有任何热路径**——启停 / 装卸 / 换 jar 一律重启 worker 才生效（[持久化与状态](../backend/persistence-and-state.md) §5）。
2. `plugin.list` RPC **不返回 status**（`PluginManifest.java:22-24` 无此字段），且 `active` 只反映禁用名单——**worker 日志是插件真实状态的唯一真相源**。
3. 前端宿主按 `webMain` 换算产物路径（源码路径去扩展名拼 `.js`，约定 `"web/index.ts"` → `web/index.js`；known-issues #5 修复前曾硬编码），源码 `web/index.ts` 不上屏；改前端 = 重新构建 + 刷新页面。

| 第一眼症状 | 直达 |
| --- | --- |
| worker 启动日志里有 WARN / 启动直接失败 | §2.1 |
| `plugin.list` 里看不到 / 字段不对劲 | §2.2 |
| 浏览器里图标、面板、改动不生效 | §2.3 |
| `mvn` / `npm` 构建命令报错 | §2.4 |
| 插件激活了，对话 / RPC / 事件没反应 | §2.5 |
| 安装、卸载、外部插件目录的问题 | §2.6 |

现象列的**两类来源**（均已逐字核对，未编造任何「可能显示」的文案）：

- **源码逐字**：日志与 RPC 文案直接抄自 `PluginLoader.java`、`BuiltInPluginScanner.java`、`ExternalPluginScanner.java`、`PluginRpcMethods.java`、`pluginLoader.ts`、`build-plugins.mjs`（行号随文标注）；
- **实测输出**：Maven 中央仓 404 报错（§2.4 首行，空本地仓库在线解析实测）；npm `EPERM` 与 `npm.ps1` 执行策略文案（[快速上手](../getting-started.md) §1.2 的实测记录）。

「可见症状」类行的口径：凡不是逐字文案的现象（如「图标不出现」「面板白屏」），都对应一类**确定可复现的可见状态**，判定依据（代码行号或既有文档的实测记录）放在根因 / 定位列；本文不使用「可能显示」式的主观措辞。

## 2. 排查总表

### 2.1 worker 启动日志组

入口：worker 启动控制台或 `<home>/logs/worker.log`（取法见 §3），grep 关键字 `[plugins`。注意别按日志级别过滤——本组的「跳过 / 失败」类多为 WARN，但「已禁用」「声明式插件」是 INFO；按文案过滤最稳。先对照**健康基线**——一次正常启动应出现这些行：

| 健康基线（出现即正常） | 出处 |
| --- | --- |
| `[plugins-builtin] 扫描到 {} 个内置插件 (共 {} 个子目录)` | BuiltInPluginScanner.java:103 |
| `[plugins-external] 扫描到 {} 个外部插件` | ExternalPluginScanner.java:55 |
| `[plugins-external] 外部插件目录不存在,跳过扫描: {}`（INFO——还没装过任何外部插件时的正常行） | ExternalPluginScanner.java:39 |
| `[plugins] 扫描到 {} 个插件(来自 {} 个扫描器)` | PluginLoader.java:180 |
| `[plugins] 插件已激活: id={} name={} v{} entry={} source={}`（**激活成功的唯一标志**） | PluginLoader.java:337 |
| `[plugins] 声明式插件已注册: id={} name={} v{} (无 Java 入口,source={})` | PluginLoader.java:263 |
| `[plugins] 加载完成: {}/{} 个插件成功加载`（分子 = 分母才算干净） | PluginLoader.java:186 |
| `[plugins] 恢复 {} 个禁用插件`（INFO——存在 `.disabled-plugins` 文件时出现） | PluginStateStore.java:86 |
| `[plugins] 目录扫描完成: 共 {} 个插件` | PluginRegistry.java:74 |

异常行的后果同时会写进加载器内部的 `LoadedPlugin.status` 字段——它**不出网**（`plugin.list` 拿不到），但日志行与它一一对应，按下表互查（完整版见[持久化与状态](../backend/persistence-and-state.md) §4.1）：

| `LoadedPlugin.status`（逐字） | active | 对应日志行 | 设置点 |
| --- | --- | --- | --- |
| `已激活(内置)` / `已激活` | true | `[plugins] 插件已激活: id=…` | PluginLoader.java:335-337 |
| `声明式插件` | true | `[plugins] 声明式插件已注册: id=…` | PluginLoader.java:263-266 |
| `已禁用(未激活)` | false | `[plugins] 插件已禁用,跳过激活: id=…` | PluginLoader.java:241-245 |
| `无 jar 文件` | false | `[plugins] 插件 {} 声明了入口类但无可用 jar` | PluginLoader.java:287-289 |
| `入口类未实现 EveryAgentPlugin` | false | `[plugins] 插件 {} 的入口类 {} 未实现 EveryAgentPlugin 接口` | PluginLoader.java:312-315 |
| `lib 目录不可读: <消息>` | false | `[plugins] 插件 {} 的 lib 目录不可读,跳过` | PluginLoader.java:279-282 |
| `激活失败: <消息>` | false | `[plugins] 插件 {} 激活失败: <消息>` + 堆栈 | PluginLoader.java:341-344 |

下面是异常行（路径均相对 `every-agent-worker/src/main/java/dev/everyagent/worker/`）：

| 现象（逐字文案） | 根因 | 定位 · 取证 | 修复 | 详解 |
| --- | --- | --- | --- | --- |
| `[plugins-builtin] 内置插件未构建,请先 mvn package: {}`（WARN） | java 插件有 `target/classes/plugin.json` 但 `target/` 下没有非 sources/javadoc 的 jar——没跑过 `mvn package`（或产物被 clean） | `Get-ChildItem every-agent-plugins\<id>\target\*.jar` | `mvn -f every-agent-plugins\<id>\pom.xml package` 后重启 worker | [快速上手](../getting-started.md) §5 |
| `[plugins] 插件 {} 激活失败: {}` 后跟完整堆栈（WARN） | `activate()` 抛异常。高频根因：`main` 的 FQN 拼错 → `ClassNotFoundException`；`ctx.services()` 取的依赖为 null 后 NPE | WARN 第三参就是 Throwable——**堆栈直接跟在那行后面**，第一帧落在你的包名即现场 | 按堆栈修代码（或修 `main`），重新 `mvn package` + 重启 | [清单参考](../plugin-manifest.md) §7、[调试与测试](debugging-and-testing.md) §3.3 |
| `[plugins] 插件已禁用,跳过激活: id={} name={} (source={})`（INFO） | id 在 `.disabled-plugins` 禁用名单（`plugin.disable` RPC 或手工写入） | `Get-Content "$HOME\.everyagent\plugins\.disabled-plugins"` | 发 `plugin.enable` RPC 或删掉该行，重启 worker | [持久化与状态](../backend/persistence-and-state.md) §3.2 |
| `[plugins-builtin] 插件已禁用(enabled=false),跳过: {}`（INFO） | 根 plugin.json 写了 `"enabled": false`（这个字段只有内置扫描器读） | 看根清单 `enabled` | 改回 `true` 并重启；运行期禁用请走 `.disabled-plugins` | [清单参考](../plugin-manifest.md) §6 |
| `[plugins] 声明式插件已注册: id={} … (无 Java 入口,source={})`——但你写的是 java 插件 | 清单里没有 `main` 字段（也没有旧格式 `provides.spi.EveryAgentPlugin`）⇒ 加载器按纯声明式处理，**一个 jar 都不会加载** | grep `"main"` 根 plugin.json **和** `target/classes/plugin.json`（加载期优先读 target 里的那份） | 补 `main` 字段 + `mvn package` + 重启。纯 web 插件见到这行属正常 | [清单参考](../plugin-manifest.md) §2.1 |
| `[plugins] 插件 {} 声明了入口类但无可用 jar (source={})`（WARN；status=`无 jar 文件`） | 有 `main` 但 jar 不在约定位置：内置找 `target/`、外部找 `<id>/lib/` | 内置查 `target\*.jar`；外部查 `lib\*.jar` | 内置 `mvn package`；外部把 jar 摆进 `lib/`（外部**不认** `target/` 布局） | [打包与安装](packaging-and-install.md) §3.2 |
| `[plugins] 插件 {} 的入口类 {} 未实现 EveryAgentPlugin 接口`（WARN） | `main` 指到的类没有 `implements EveryAgentPlugin` | 打开该类看声明 | 补接口实现，或让 `main` 指向真正的入口类 | [后端总览](../backend/overview.md) §5 |
| `[plugins] 插件 {} 已加载,跳过重复: {} (source={})`（WARN，后到者多为 external） | 同 id 被两个扫描器发现：builtin 排序在前先加载，外部同名版被去重跳过 | 两边目录是否各有一个同 id 目录 | 想让外部版生效：把内置版根清单写 `enabled:false`（或移走目录）再重启 | [持久化与状态](../backend/persistence-and-state.md) §5.3 |
| `[plugins-builtin] 内置插件源码目录不存在,跳过扫描: {}(桌面安装包出现此行 = 打包未含 every-agent-plugins)`（WARN） | worker 启动目录（user.dir）下没有 `every-agent-plugins/`——**从哪个目录启动决定能不能扫到**；或 `worker.builtin-plugins-dir` 配置指错。桌面安装包见此行 = 打包缺目录（文案自证） | 对照日志里打印的**绝对路径** | 从仓库根启动 worker，或把 `worker.builtin-plugins-dir` 指到正确目录 | [打包与安装](packaging-and-install.md) §3.3 |
| `[plugins] 未扫描到任何插件`（INFO） | 两个扫描器都空手而归：目录不存在 / 目录里全没有 plugin.json / 内置全被 `enabled=false` 拦 | 先看上一行那两条 WARN/INFO 出现没有 | 按上面两行修复后重启 | [持久化与状态](../backend/persistence-and-state.md) §4.3 |
| `[plugins] 插件 {} 的 lib 目录不可读,跳过: {}`（WARN） | 外部插件目录缺 `lib/` 子目录（或无权限列举） | `Get-ChildItem "$HOME\.everyagent\plugins\<id>\"` | 建 `lib/` 并放入 jar，重启 | [打包与安装](packaging-and-install.md) §3.2 |
| `[plugins] 加载完成: 24/26 个插件成功加载`（分子 ≠ 分母） | 有插件没加载成——往上翻启动日志逐条核对本表 WARN 行 | `Select-String -Path <日志> -Pattern "\[plugins"` | 按 WARN 对应行修复 | [调试与测试](debugging-and-testing.md) §3.2 |
| worker **启动直接失败**，堆栈是 Jackson 异常；此前可见 `[plugins-builtin] 解析 plugin.json 失败,视为已启用: {}`（WARN） | plugin.json 语法错（尾逗号、`//` 注释、括号不配）：jackson 解析异常是非受检 `JacksonException`，穿透只 `catch (IOException)` 的加载分支，冒出 `@PostConstruct`（异常类型实测；启动失败复现未实测） | 打开报错路径的 plugin.json 查语法 | 修成严格 JSON 后重启 | [清单参考](../plugin-manifest.md) §7 |
| `[plugins-builtin] 扫描内置插件目录失败: {}` / `[plugins] 插件扫描器 {} 扫描失败,跳过: {}` 等扫描器级 WARN | IO / 权限类环境问题（单个扫描器失败不影响其他扫描器） | 看跟随的堆栈 | 修文件系统访问后重启 | [后端总览](../backend/overview.md) §8.1 |

### 2.2 plugin.list 组

入口：前端「扩展管理」面板，或手工往 worker cmd 频道发 `plugin.list` RPC 帧（wire 示例见[打包与安装](packaging-and-install.md) §3.1）：

```jsonc
// channel: "u.<ownerKey>.worker.<workerId>.cmd", event: "rpc"
{ "reqId": "req-list-1", "method": "plugin.list", "params": {} }
// 应答在 evt 频道（event: "rpc.ok"）：
// { "reqId": "req-list-1", "result": { "plugins": [ … ], "disabledIds": [ … ] } }
```

| 现象（可见症状） | 根因 | 定位 · 取证 | 修复 | 详解 |
| --- | --- | --- | --- | --- |
| `plugins` 数组里根本没有你的插件 | 目录没被扫描到：位置不对（不在 `every-agent-plugins/` 或 `~/.everyagent/plugins/` 的**一级子目录**）；**目录根没有 plugin.json——静默跳过、无任何日志**（BuiltInPluginScanner.java:86-87、ExternalPluginScanner.java:46）；内置 `enabled=false`（INFO 跳过）；java 插件未构建（WARN 跳过，同样进不了列表） | grep 启动日志 `[plugins` 找对应跳过行 | 摆对位置 / 补根 plugin.json / `mvn package` / 改 enabled，重启 worker | [持久化与状态](../backend/persistence-and-state.md) §4.3 |
| 条目报 `"active": true`，但插件毫无作用 | `active` 只看禁用名单（`PluginRpcMethods.java:68` 的 `!isDisabled`），**激活失败的插件照样报 true**；且 `LoadedPlugin.status` 不出网（`PluginManifest.java:22-24` 无此字段） | worker 日志 grep `插件已激活: id=<你的id>` / `激活失败`，以日志为准 | 按 §2.1 对应行修复 | [持久化与状态](../backend/persistence-and-state.md) §4.3 |
| 顶层 `disabledIds` 数组里出现你的 id | 在 `.disabled-plugins` 名单（`PluginStateStore.java:38`；disable RPC 或手工编辑都会写它，管的范围含内置插件） | `Get-Content "$HOME\.everyagent\plugins\.disabled-plugins"` | `plugin.enable` RPC 或删该行，重启 worker | [打包与安装](packaging-and-install.md) §4.2 |
| 日志 `[plugins] 读取禁用列表失败: {}` / `[plugins] 写入禁用列表失败: {}`（WARN，PluginStateStore.java:89,98） | `.disabled-plugins` 文件 IO 失败（权限 / 占用 / 编码） | 查该文件属性与占用 | 修好后重启 worker；`plugin.enable` / `plugin.disable` 会触发重写 | [打包与安装](packaging-and-install.md) §4.2 |

### 2.3 前端组

入口：浏览器 F12 控制台（前端 loader 只打两条插件日志，`every-agent-web/src/plugin/pluginLoader.ts`）与页面表现。注意**被过滤的插件是零日志静默**（`plugin.list` 拿不到、`hasWebMain=false`、被禁用三者在 `pluginLoader.ts:365-367` 的过滤条件里直接出局）。

| 现象（逐字文案 / 可见症状） | 根因 | 定位 · 取证 | 修复 | 详解 |
| --- | --- | --- | --- | --- |
| 侧边栏图标不出现，控制台**零报错**（成功/失败两条日志都没有） | 被加载过滤器静默拦下：`active && hasWebMain && !disabled`（pluginLoader.ts:365-367）任一不满足——最常见：清单缺 `webMain`、在 `disabledIds` 里、worker 未连接（此时整体静默降级） | `plugin.list` 逐项核对 `hasWebMain` / `active` / `disabledIds` | 补 `webMain: "web/index.ts"` → `plugin.enable` → 刷新页面 | [前端总览](../web/overview-and-loading.md) §1 |
| 控制台 `[plugins] 插件 <id> 加载失败:` + 异常对象（console.warn，pluginLoader.ts:408） | 取 webSource / import / `activate()` 任一环节抛错。产物缺失时异常里可见 `文件不存在或越界: web/index.js`（webSource 的 NOT_FOUND 文案，PluginRpcMethods.java:196） | F12 展开异常对象看是哪一环 | 产物缺失 → 跑构建（下行）；activate 抛错 → 按堆栈修代码 | [调试与测试](debugging-and-testing.md) §2.3 |
| 异常对象文本是 `插件 <id> 无 <产物路径> 源码`（`pluginLoader.ts`，产物路径 = `webMain` 换算结果） | webSource 成功返回但 content 为空——产物文件存在却是空文件（异常产物） | 看 `every-agent-plugins\<id>\web\index.js` 大小 | 重新 `npm.cmd run build:plugins` + 刷新 | [前端总览](../web/overview-and-loading.md) 约定 5 |
| 改了 `web/index.ts`，刷新页面无变化 | 页面加载的是**构建产物**（路径由 `webMain` 换算，约定即 `web/index.js`）；没重跑构建；standalone 工程则要跑自己的 `scripts/build.mjs` | 看 `web\index.js` 的修改时间 | `npm.cmd run build:plugins`（或开着 `watch:plugins`）+ 刷新页面 | [调试与测试](debugging-and-testing.md) §2.4 |
| 改了根 `plugin.json`（webMain 等），重启 worker 也没变化 | java 插件加载期读的是 `target/classes/plugin.json`（`resolveManifestPath` 优先 target，BuiltInPluginScanner.java:193-204）；没重新 package ⇒ 还是旧清单（例外：`enabled` 永远看根） | diff 根清单与 `target\classes\plugin.json` | `mvn package` + 重启 worker | [持久化与状态](../backend/persistence-and-state.md) §5.4 |
| 控制台有成功行 `[plugins] 插件已激活: <id> (<name>)`（pluginLoader.ts:406），但界面什么都没多出来 | `activate()` 正常返回但**没调 `ctx.ui.register*`**（注册代码在条件分支里 / 忘了写）——前端不校验注册面，零注册也打成功行 | 读 `web/index.ts` 的 `activate` 函数体 | 补注册调用 → `build:plugins` → 刷新 | [UI 扩展点](../web/ui-extensions.md) §1 |
| 图标出现、点开面板**白屏** | 面板组件**渲染期**抛错（控制台是 React 渲染堆栈）；若是 `activate()` 里注册完才 throw，则走上一行打 `[plugins] 插件 <id> 加载失败:` | F12 Console 区分堆栈来源（渲染期 / 激活期） | 按堆栈修组件或 activate | [UI 扩展点](../web/ui-extensions.md) §2.3 |
| 图标在但顺序 / 内容被内置覆盖 | 不是故障：侧边栏按 `order` float 升序混排；其余扩展点没有 order，按「插件在前」的合并顺序覆盖 | 对照侧边栏 order 坐标系表 | 选未被占用的 `order`；或接受覆盖语义 | [UI 扩展点](../web/ui-extensions.md) §3、[前端总览](../web/overview-and-loading.md) §5 |

### 2.4 构建组

入口：`mvn` / `npm.cmd run build:plugins`（或 `watch:plugins`）的命令行输出。

| 现象（逐字文案） | 根因 | 定位 · 取证 | 修复 | 详解 |
| --- | --- | --- | --- | --- |
| `Could not find artifact dev.everyagent:every-agent-plugin-api:jar:1.0.0 in central (https://repo.maven.apache.org/maven2)`（伴随 WARNING `The POM for dev.everyagent:every-agent-plugin-api:jar:1.0.0 is missing, no dependency information available`；空本地仓库在线解析实测输出） | API 包未发布到任何远程仓（Maven Central `dev/everyagent/` 目录 404），本地仓库又没装过——新克隆的仓库 / 新机器直接构建插件就会撞上 | — | 仓库根一次性：`mvn -pl every-agent-plugin-api -am install -DskipTests` | [快速上手](../getting-started.md) §5 |
| 测试启动时 `WARNING: A Java agent has been loaded dynamically ...` 与 `Mockito is currently self-attaching ...` | Mockito inline-mock-maker 在 JDK 25 上动态自挂 agent | — | **无害，忽略**（实测测试全绿时即有此警告） | [调试与测试](debugging-and-testing.md) §1.5 |
| `[build-plugins] 构建失败:` + 错误对象（build-plugins.mjs:187） | esbuild 打包失败：`web/index.ts` 语法错 / import 了白名单（react 系、antd、icons 共 5 项）之外的裸包 / 引用不存在的文件 | 看其后跟随的 esbuild 诊断（含文件与行列号） | 按诊断修源码后重跑 | [前端总览](../web/overview-and-loading.md) 约定 3 |
| `[build-plugins] 错误:--only 中不存在的插件 id: <id>` + 可用 id 清单（build-plugins.mjs:116-119） | id 拼错；或该插件没有 `web/index.ts` 入口（java-only 插件不在 web 构建范围） | 看随后列出的 `[build-plugins] 可用的插件 id:` | 更正 id；java-only 不需要 web 构建 | [调试与测试](debugging-and-testing.md) §2.2 |
| `[build-plugins] 监听中…` 常驻（watch 模式），改代码自动重编了，**页面还是旧的** | watch 只负责重编产物；宿主页面加载的是 blob URL 快照，**没有热替换** | 看 `web\index.js` 修改时间确已更新 | 手动刷新浏览器（F5）——前端改动从不需要重启 worker，但总需要刷新 | [调试与测试](debugging-and-testing.md) §2.2 |
| npm 报 `EPERM`（写缓存目录被拒） | 受限 / 沙箱环境下 npm 默认缓存目录不可写 | — | `npm.cmd install --cache "$env:TEMP\npm-cache"` | [快速上手](../getting-started.md) §1.2 |
| `无法加载文件 …npm.ps1，因为在此系统上禁止运行脚本` | PowerShell 执行策略拦住 npm 垫片 | — | 一律用 `npm.cmd`（`npx` 同理用 `npx.cmd`） | [快速上手](../getting-started.md) §1.2 |

### 2.5 运行组

入口：对话行为、前端 RPC 报错、worker 运行期日志。多数「已激活但不生效」的问题有同一批根因，五幕剧本在[调试与测试](debugging-and-testing.md) §4，这里各压缩成一行。

先分清两类问题：「**根本没激活**」（回 §2.1 对 WARN 行）与「**激活了但不生效**」（本组）——判定依据是日志里有没有 `插件已激活: id=<你的id>`。

| 现象（逐字文案 / 可见症状） | 根因 | 定位 · 取证 | 修复 | 详解 |
| --- | --- | --- | --- | --- |
| 日志有「插件已激活」，但 Advisor 不起作用（模型行为毫无变化） | 注册没走到（条件分支 / 提前 return）；`appliesTo` 把自己拦了；order 撞车（注册表零校验，撞了不报错） | 剧本 a 三步：grep 激活行 → `RecordingPluginContext` 单测断言注册面 → 对照 order 坐标图 | 按三步结论修，重新 package + 重启 | [调试与测试](debugging-and-testing.md) §4 剧本 a、[Advisor](../backend/advisors.md) §2 |
| 工具注册了，AI 就是不调用 | `ToolProvider.appliesTo` 的沙箱后端判据不匹配；`createTools` 拿不到依赖静默返回空；`@Tool` 描述没说清何时该用 | 剧本 b 三步（后端 id / 空返回 / 描述质量） | 对照常见坑表修 | [调试与测试](debugging-and-testing.md) §4 剧本 b、[工具与沙箱](../backend/tools-and-sandbox.md) §1.5 |
| `插件 {} 尝试注册生命周期节点 {} 的 order={} 落入临界段 [420,850]，已拒绝`（WARN，TaskLifecycleRegistry.java:46） | `TaskLifecycleNode` 的 order 落进洋葱保留段（worker 自用临界段，注册时拒绝） | 对照 Lifecycle order 分配表 | 挪出 [420,850]（用空档段），重启 worker | [任务与 RPC](../backend/task-and-rpc.md) |
| 前端 `ctx.sdk.rpc` 报错或无响应；worker 日志见 `rpc {} 执行异常`（ERROR，RpcDispatcher.java:107）或 `rpc 请求缺 reqId/method: {}`（WARN，:77） | 方法名两边不一致（没注册 / 拼错）；参数形状不对；handler 抛异常 | 先 grep 这两条（执行异常带完整堆栈） | 逐字核对 `registerRpcMethod` 与 `ctx.sdk.rpc` 的方法名 | [调试与测试](debugging-and-testing.md) §4 剧本 e、[任务与 RPC](../backend/task-and-rpc.md) |
| `ctx.events.on(...)` 永不触发 | 订阅了当前全仓零 emit 的**死事件**（38 个宿主事件中 24 个；具名 9 个中 `file-content-saved` / `task-created` / `task-deleted` / `task-trace-changed` 4 个）；拼错事件名也不报错 | 对照死事件总表 | 换 14 个活事件，或改走 UI 扩展点 / `ctx.sdk.rpc` | [前端事件](../web/events.md) §3.2 |
| `/` 菜单里没有我的斜杠命令候选 | 插件被禁用 ⇒ `activate` 根本没被调，一个贡献都不会注册（`PluginLoader.java:237-239` 注释自证「"/菜单"里也就没有它的候选」）；或没调 `registerSlashProvider` | 日志 grep `插件已禁用,跳过激活`；读 activate 源码 | 启用插件（重启生效）；补注册调用 | [任务与 RPC](../backend/task-and-rpc.md) |

### 2.6 安装组

入口：`plugin.*` RPC 应答（六个方法参数 / 返回总表见[打包与安装](packaging-and-install.md) §4）与 `~/.everyagent/plugins/` 目录。本组多行都指向同一棵**标准布局树**，先对照它：

```text
~/.everyagent/plugins/          ← worker.plugins-dir（可配置覆盖，默认此路径）
└── my-tool/                    ← 一级子目录；目录名建议 = id（uninstall 按它找目录）
    ├── plugin.json             ← 必须在目录根（扫描器唯一判据，ExternalPluginScanner.java:46）
    ├── lib/
    │   └── my-tool-0.1.0.jar   ← java / full 形态：jar 只认这里（不认 target/）
    └── web/
        └── index.js            ← web / full 形态：前端硬编码加载这个文件
```

| 现象（逐字文案 / 可见症状） | 根因 | 定位 · 取证 | 修复 | 详解 |
| --- | --- | --- | --- | --- |
| `plugin.install` 应答 `NOT_FOUND`：`插件文件不存在: <path>`（PluginRpcMethods.java:94） | `path` 是 **worker 机器**上的路径——传成了浏览器 / 开发机路径，或 `.eap` 没先送到 worker 那台机器 | 在 worker 机器上核对路径 | 先把 `.eap` 传到 worker 机器，再传该机绝对路径 | [打包与安装](packaging-and-install.md) §3.1 |
| `plugin.uninstall` 应答 `NOT_FOUND`：`插件目录不存在: <id>`（PluginRpcMethods.java:122） | uninstall 按 id 找 `<pluginsDir>/<id>` **目录**；手工 zip 顶层目录名 ≠ pluginId ⇒ 解出的目录名与登记 id 不一致，按 id 找不到 | `Get-ChildItem "$HOME\.everyagent\plugins\"` 看实际目录名 | 直接删那个目录（等效卸载）；下次用 `pack`（保证顶层 = id） | [打包与安装](packaging-and-install.md) §4.1 |
| 装完 `.eap` 重启后，`plugin.list` 里仍没有 | 解压布局错：平铺 zip 把文件摊进 plugins 根（扫描器只认一级子目录，等于没装）；或顶层目录里缺 `plugin.json` | 列目录对照标准布局树（`<id>/plugin.json` + `lib/` + `web/index.js`） | 重摆成标准布局，重启 worker | [打包与安装](packaging-and-install.md) §3.2 |
| 外部插件把 plugin.json 写 `"enabled": false` 想禁用，没生效 | `enabled` 字段**只有内置扫描器读**（`ExternalPluginScanner` 全文零读 enabled）——外部插件照样被扫描加载 | — | 用 `plugin.disable` RPC / `.disabled-plugins` 名单，或直接删目录 | [打包与安装](packaging-and-install.md) §4.2 |
| 把开发机的插件目录整目录拷到 `~/.everyagent/plugins/`，重启后 java 部分没加载（WARN `[plugins] 插件 {} 声明了入口类但无可用 jar`，PluginLoader.java:287） | 外部插件 jar 约定在 `<id>/lib/`，**不认** `target/` 布局（jar 还留在 `target/` 里） | 查该目录有没有 `lib\*.jar` | `pack` 成 `.eap` 再解压，或手工把 jar 摆进 `lib/` | [打包与安装](packaging-and-install.md) §3.2 |

## 3. 日志在哪里（取证要点）

worker 日志**双路同时输出**（`every-agent-worker/src/main/resources/logback-spring.xml:63-65`，root 级别 INFO，CONSOLE + FILE 双 appender）：

- **启动控制台**：开发时最直接的第一现场——控制台与文件是同一 root 双写，内容一致，控制台翻不动时直接查文件；
- **日志文件**：`<home>/logs/worker.log`，每日滚动成 `worker.<yyyy-MM-dd>.log`、保留 7 天（`logback-spring.xml:15,18-22`）。`<home>` 解析优先级：`EVERYAGENT_HOME` 环境变量 → `worker.home-dir` 配置（默认 `~/.everyagent`，`application.yml:19`；dev profile 为 `~/.everyagent-dev`，`application-dev.yml:11`）。

过滤命令（PowerShell；dev profile 换 `$HOME\.everyagent-dev\logs\worker.log`）：

```powershell
# 全部插件相关行（[plugins] / [plugins-builtin] / [plugins-external] 一网打尽）
Select-String -Path "$HOME\.everyagent\logs\worker.log" -Pattern "\[plugins"
# 只盯一个插件
Select-String -Path "$HOME\.everyagent\logs\worker.log" -Pattern "my-tool"
# 只看失败类行（一张表覆盖 §2.1 的大部分异常）
Select-String -Path "$HOME\.everyagent\logs\worker.log" -Pattern "激活失败|未构建|不可读|无可用 jar|未实现 EveryAgent|跳过重复|跳过激活"
# 只看启动汇总
Select-String -Path "$HOME\.everyagent\logs\worker.log" -Pattern "扫描到|加载完成|目录扫描完成"
```

日志行格式是 `%d{…} %-5level [%thread] %logger{36} - %msg%n`（`logback-spring.xml:26,46`）——插件相关 logger 是 `…plugin.loader.PluginLoader` / `…plugin.scanner.BuiltInPluginScanner` 等，但文案自带 `[plugins]` / `[plugins-builtin]` / `[plugins-external]` 前缀，所以 grep `\[plugins` 一网打尽、最稳。

找不到日志文件时按解析链倒查（优先级从高到低）：`$env:EVERYAGENT_HOME` → 启动参数 / 配置里的 `worker.home-dir`（默认 `~/.everyagent`）→ 都没有则确认 worker 是不是用 dev profile 启动的（那会写 `~/.everyagent-dev\logs\`）。

前端日志在**浏览器 F12 Console**：插件只有成功 `[plugins] 插件已激活: <id> (<name>)`（pluginLoader.ts:406）与失败 `[plugins] 插件 <id> 加载失败:`（:408）两条；被过滤的插件零日志（§2.3 首行）。

日志级别不够时：起点在 `application.yml:163`（`dev.everyagent` 默认 INFO），临时调自己插件的包用 `--logging.level.dev.everyagent.plugin.<你的包>=DEBUG` 覆盖。全部插件日志文案的对照总表（15 条 `PluginLoader` 日志逐条带行号）在[调试与测试](debugging-and-testing.md) §3.2。

## 4. 一分钟的五步体检

新插件从生成到上屏，按序走完这五步——多数「没生效」会停在第二步（没构建）或第三步（没重启）：

| 步骤 | 动作 / 命令 | 健康判据（预期输出） |
| --- | --- | --- |
| ① 就位 | 目录放进正确扫描位置：内置 = 仓库 `every-agent-plugins/`，外部 = `~/.everyagent/plugins/`；目录根必须有 `plugin.json` | 这是唯一无法用日志验证的一步——没有 plugin.json 的目录被**静默跳过、零日志**（§2.2 首行），只能直接看目录 |
| ② 构建 | java/full：`mvn -f every-agent-plugins\<id>\pom.xml package`；web/full：`cd every-agent-web; npm.cmd run build:plugins` | Maven 末尾 `BUILD SUCCESS`；web 侧 `[build-plugins] 完成，共构建 N 个插件。`（build-plugins.mjs:183）；产物 `target\*.jar` / `web\index.js` 就位 |
| ③ 重启 | 重启 worker（启停 / 装卸 / 换文件没有热路径）；web 侧改完**还要刷新页面** | — |
| ④ 看日志 | `Select-String -Path <日志> -Pattern "\[plugins"`（日志取法见 §3） | 有 `[plugins] 插件已激活: id=<id>`（或声明式插件那行），且 `加载完成: N/N` 分子分母相等；出现 WARN 就回 §2.1 对号 |
| ⑤ 验目录 | `plugin.list`（前端扩展管理面板，或手工 RPC 帧） | id 在列、`hasMain` / `hasWebMain` 与形态一致、不在 `disabledIds`；注意 `active: true` **不代表**激活成功（§2.2 第二行） |

第 ② 步的 Maven 受限环境配方（离线 + 指定本地仓库）与第 ⑤ 步的手工 RPC 帧示例分别在[调试与测试](debugging-and-testing.md) §5 与[打包与安装](packaging-and-install.md) §3.1。

## 下一步读

- [调试与测试](debugging-and-testing.md)——本表背后的方法论：测试桩、typecheck、日志文案全集、五幕剧本；
- [构建与运行](build-and-run.md)——构建矩阵、worker 运行事实（cwd/home-dir/hub 凭据）与本表 §2.4 的详解源；
- [前端总览](../web/overview-and-loading.md) §1——加载链路每一步的失败症状表（§2.3 的流程图版）；
- [持久化与状态](../backend/persistence-and-state.md)——`active=true` 陷阱、禁用双机制、plugin.json 双份不对称的完整解释；
- [打包与安装](packaging-and-install.md)——六个 `plugin.*` RPC 与 `.eap` 布局约定（本表 §2.6 的详解源）；
- [已知问题](../reference/known-issues.md)——排查前先对一眼：23 条已知陷阱里可能就有你撞上的这堵墙；
- [plugin.json 参考](../plugin-manifest.md) §7——「常见错误」表与本表 §2.1 互为镜像：那边按**字段写错**分类，这边按**日志行**对号。
