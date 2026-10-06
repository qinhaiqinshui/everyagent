---
title: 构建与运行
nav_order: 13
parent: guides
has_children: false
---

**一句话定位**：本篇是**日常构建与运行的完整参考**——三形态（java / web / full）的构建矩阵、Maven 与 npm 两侧的机制与配方、worker 的启动姿势与 cwd / home-dir / hub 凭据三个关键运行事实、改代码后的生效边界，以及本机多实例与桌面版的避坑。与[快速上手](../getting-started.md)的分工：那篇讲「第一次生成 + 5 分钟跑通」（脚手架交互、六组合选型、三个最小实验），本篇不再复述，一律交叉引用；这里假设你已经有插件工程，要把它**天天构建、跑在 worker 上**。

## 1. 构建矩阵总表

### 1.1 形态 × 动作 → 命令 → 产物 → 耗时

命令均为 Windows PowerShell 口径（`npm` 被执行策略拦截时用 `npm.cmd`，见[快速上手](../getting-started.md) §1.2）；`<id>` 换成你的插件 id。耗时为真机实测口径（本站计划步骤 4/8/9）：单插件 `mvn package` 约 10 秒量级、`build:plugins` 全量不足 1 秒、`typecheck` 约 4 秒——数字随机器与插件数浮动，量级可当预期。

| 形态 | 动作 | 命令（执行位置） | 产物 | 耗时口径 |
| --- | --- | --- | --- | --- |
| java | 编译 + 测试（不出 jar） | 仓库根：`mvn -f every-agent-plugins\<id>\pom.xml test` | `target/test-classes`、`target/surefire-reports` | ~10s 量级 |
| java | 打包（先跑同一套测试） | 仓库根：`mvn -f every-agent-plugins\<id>\pom.xml package` | `target\<id>-<版本>.jar` + `target/classes/plugin.json` | ~10s 量级（步骤 4/8 实测） |
| web | 类型检查 | `every-agent-web` 下：`npm.cmd run typecheck` | 无产物（`tsc --noEmit`） | ~4s（3.8s 实测） |
| web | 构建 bundle | `every-agent-web` 下：`npm.cmd run build:plugins` | `every-agent-plugins\<id>\web\index.js`（+ `.js.map`，有 CSS import 时再出 `index.css`） | <1s 全量（0.4s / 10 入口实测） |
| web | 监听重编（常驻） | `every-agent-web` 下：`npm.cmd run watch:plugins` | 同上，改动自动重编对应插件 | 首次同全量，之后单插件秒级 |
| full | 全量 | java 的 `package` **与** web 的 `build:plugins` 两条都要 | jar + bundle 两份 | 两者相加 |
| 任意 | `.eap` 打包自检 | 仓库根：`node create-everyagent-plugin pack every-agent-plugins\<id> --verify` | `<id>-<版本>.eap`（当前目录） | 秒级（纯 zip 操作） |
| web × standalone | 自带工具链构建 | 插件目录：`npm.cmd install` 一次 → `npm.cmd run build` | `web/index.js` | 秒级（esbuild 单入口；步骤 9 实测通过） |
| java × standalone | 同 java | 在插件自己的目录 `mvn package`（依赖准备见 §2.2） | 同 java | 同 java |

`test` 与 `package` 的取舍、JUnit 范式与沙箱受限配方详见[调试与测试](debugging-and-testing.md) §5 的速查表；`.eap` 的内部布局与安装见[打包与安装](packaging-and-install.md)。

### 1.2 一次性准备（按序）

1. **java / full 形态**：仓库根先执行一次 `mvn -pl every-agent-plugin-api -am install -DskipTests`（为什么必须：见 §2.2）。
2. **web / full 形态（builtin）**：`cd every-agent-web; npm.cmd install` —— `build:plugins` / `typecheck` 分别依赖 `node_modules` 里的 esbuild 与 typescript（`every-agent-web/package.json:42-48` 的 devDependencies），没装过跑不起来。npm cache 不可写（受限环境）时加 `--cache "$env:TEMP\npm-cache"`。
3. 之后进入日常循环：改代码 → §1.1 对应命令 → 按 §5 的生效边界重启 worker / 刷新页面。

### 1.3 常用变体

```powershell
mvn -f every-agent-plugins\<id>\pom.xml package -DskipTests   # 跳过测试只出 jar（快速迭代）
mvn -f every-agent-plugins\<id>\pom.xml clean package          # 清掉 target/ 全量重建
mvn -f every-agent-plugins\<id>\pom.xml package -q             # 静默模式，只打错误与 WARN
```

日常迭代**一般不需要 `clean`**：`process-resources` 每次构建都会重拷 `plugin.json`、编译器按源码增量重编；只有当 `target/` 里残留了手工实验产物或改名前的旧 jar 干扰扫描判定（§2.4）时再上 `clean`。

## 2. Maven 侧（java / full）

### 2.1 插件不进根 reactor：证据与含义

根 `pom.xml:20-26` 的 `<modules>` 全集只有 5 项：`every-agent-contract`、`every-agent-hub`、`every-agent-plugin-api`、`every-agent-plugins/task-edit-resend`、`every-agent-worker`——**25 个内置插件中仅 `task-edit-resend` 一个在 reactor 内**（它被 worker 的测试依赖，是历史特例），其余插件目录一概不在。三个直接推论：

1. 仓库根裸跑 `mvn package` **不会**构建你的插件，你的插件编译错了也不会让根构建变红；
2. 构建插件必须 `-f every-agent-plugins\<id>\pom.xml` 单独指过去（或 `cd` 进插件目录），这就是 §1.1 矩阵里所有 mvn 命令带 `-f` 的原因；
3. 构建宿主（如 `mvn -pl every-agent-worker -am package`）同样不带任何插件（`task-edit-resend` 除外）——宿主与插件是两条构建线。

插件 pom 的 parent 是 `dev.everyagent:every-agent-parent:1.0.0`（即根 pom，靠 `relativePath ../../pom.xml` 在磁盘上定位），依赖只有 `every-agent-plugin-api` + `spring-boot-starter-test`（test），**零 worker 依赖**——清单与字段逐项见[plugin.json 字段参考](../plugin-manifest.md)与[后端模型总览](../backend/overview.md)。

### 2.2 首次准备：`install` plugin-api（为什么不能省）

`dev.everyagent:every-agent-plugin-api` **未发布到 Maven Central**（repo1 上 `dev/everyagent/` 目录 404，实测）。`-f` 单独构建插件时，Maven 解析这个依赖只会去**本地仓库**找——新 clone 的机器上没有它，构建直接失败（Maven 标准报错形态：`Could not resolve artifact dev.everyagent:every-agent-plugin-api ...`；此前尝试过联网解析的机器还可能残留 `.lastUpdated` 失败缓存，执行下面的 `install` 即一并解决）。所以一次性执行：

```powershell
# 仓库根，一次性：把 plugin-api（连同它依赖的 reactor 上游）装进本地 ~/.m2
mvn -pl every-agent-plugin-api -am install -DskipTests
```

`-pl` 指到 `every-agent-plugin-api`（根 pom.xml:23，它在 reactor 内所以 `-pl` 可用），`-am` 顺带构建其上游。正常环境执行这一次即可；`install` 会写 `~/.m2`，在写受限的环境里见下节替代方案。standalone 形态（仓库外工程）同样依赖这一步——API jar 只能这样本地供给，没有第二来源。

### 2.3 离线 / 受限环境配方（技巧，非必需）

安装级 Maven settings 可能把 `localRepository` 钉到不可写位置，或网络受限。此时给 mvn 显式指一个**内容齐全**的本地仓库并离线跑：

```powershell
mvn -o "-Dmaven.repo.local=$HOME\.m2\repository" -f every-agent-plugins\<id>\pom.xml package
```

口径说明（本站计划步骤 4/8 实测 BUILD SUCCESS）：

- `-o` 离线：不触发任何远程访问，前提是依赖已在该仓库（往届构建过的机器天然满足）；
- `package` 目标只写工作区内的 `target/`，不碰仓库；**禁用 `install` 目标**（要写仓库，受限环境会被拦）；
- 这是**受限环境技巧**，正常机器不需要，也不要写进 CI 的默认配置。

### 2.4 「构建过没有」由扫描器判定：未构建只 WARN 不炸

worker 的内置扫描器把「java 插件」判定为 `target/classes/plugin.json` 存在 **且** `target/` 下有非 sources/javadoc 的 jar（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/scanner/BuiltInPluginScanner.java:32` 类注释口径）。没跑 `mvn package` 时：

- 有清单无 jar → `WARN [plugins-builtin] 内置插件未构建,请先 mvn package: <id>`（`BuiltInPluginScanner.java:95`），该插件被跳过；
- 加载端还有一条兜底：`WARN [plugins] 插件 <id> 声明了入口类但无可用 jar`（`PluginLoader.java:287`）。

两条都不阻塞 worker 启动——忘记构建的代价是「插件缺席」而不是「worker 起不来」。全部插件相关日志文案的一览表在[调试与测试](debugging-and-testing.md) §3.2。

## 3. npm 侧（web / full 的前端部分）

### 3.1 scripts 全集与分工

`every-agent-web/package.json:6-12` 全部 6 个 script：

| script | 命令 | 管什么 | 包含插件构建？ |
| --- | --- | --- | --- |
| `dev` | `vite --host 0.0.0.0` | 宿主页面开发服务器 | ✗ |
| `build` | `tsc && vite build` | 宿主页面生产构建 | ✗ |
| `build:plugins` | `node scripts/build-plugins.mjs` | **插件 bundle 的唯一构建入口** | ✓ |
| `watch:plugins` | `node scripts/build-plugins.mjs --watch` | 常驻监听重编 | ✓ |
| `preview` | `vite preview` | 宿主产物本地预览 | ✗ |
| `typecheck` | `tsc --noEmit` | 宿主 + 全部 builtin 插件的类型检查 | —（不产产物） |

记住这条分界：**宿主自身的 dev / build / preview 都不碰插件**；插件 bundle 永远走 `build:plugins` / `watch:plugins`（唯一例外是桌面打包链会带上它，见 §7）。改完 `web/index.ts` 只跑 `npm.cmd run build` 是常见白跑——那编译的是宿主页面。

### 3.2 `build:plugins`：全量重编 + 自动发现，产物不入库

行为三点（`every-agent-web/scripts/build-plugins.mjs:2-6` 头注释与实现口径）：

1. **固定扫描** `every-agent-plugins/<id>/web/index.ts`（跳过名为 `target` 的目录）。**新插件零注册自动发现**——脚手架把目录生成进去，下一次构建自然纳入；同理删目录即出列。
2. **无增量判断，每次全量重编**所有入口。别嫌浪费：全量 10 个入口实测 0.4s，代价可忽略，这也是脚本不做 `--only` 之外增量机制的理由。
3. **产物写回各插件目录**（`<id>/web/index.js`），由 worker 侧 `plugin.webSource` RPC 按需读给前端——所以产物位置不能挪。

产物**永远不入库**，双层证据：仓库根 `.gitignore` 第 1 行 `target/`（jar）与第 25-28 行 `every-agent-plugins/*/web/index.js(.map)`、`index.css(.map)`（bundle）兜住全部内置插件；脚手架为每个生成的插件再放一份插件级 `.gitignore` 同口径兜底（对 standalone 工程尤其重要——它在仓库根的覆盖范围之外）。推论：**新 clone 的仓库跑完 worker 也不会有任何插件前端**，先 `build:plugins` 一次。

### 3.3 `--only` 与 `watch:plugins`（已是实态）

两者均已落地（本站计划步骤 10），用法注释逐字见 `build-plugins.mjs:18-22`：

```powershell
cd every-agent-web
npm.cmd run watch:plugins                                  # 常驻：首次全量构建后监听，Ctrl+C 干净退出
node scripts\build-plugins.mjs --only git,subagent         # 只构建指定 id（逗号分隔多个）
node scripts\build-plugins.mjs --only <你的id> --watch     # 组合：只盯自己的插件
```

- `--only` 给不存在的 id 会报错并列出可用清单（防拼错静默成功）；
- esbuild 已**显式**声明为 devDependency（`every-agent-web/package.json:48`，版本 0.21.5）——此前靠 vite 传递依赖，现在 watch / build 都不再受 vite 依赖树变动影响；
- watch 省的只是「手动跑构建」这一步：宿主页面加载的是快照，改完仍要**手动刷新浏览器**（没有热替换），见 §5.2。

### 3.4 typecheck 为什么「免费」覆盖你的插件

宿主 `every-agent-web/tsconfig.json:32` 的 `include` 含 `"../every-agent-plugins/*/web"`（所有 builtin 插件的 `web/` 都进编译范围），`:22` 的 `paths` 又把 `@everyagent/plugin-api` 指到真源码 `../every-agent-plugin-api/js/index.ts`。所以 builtin 形态不需要给插件配 tsconfig，`npm.cmd run typecheck` 一次检查宿主 + 全部插件；standalone 插件不在 `include` 内，用脚手架自带的 `tsconfig.json` + `vendor/` 类型副本自己查（原理与命令见[调试与测试](debugging-and-testing.md) §2.1、[快速上手](../getting-started.md) §6）。

## 4. 运行 worker

### 4.1 启动方式

| 方式 | 命令 / 入口 | 说明 |
| --- | --- | --- |
| 终端跑 exec jar | 仓库根：`java -jar every-agent-worker\target\every-agent-worker-1.0.0-exec.jar` | 可执行 jar（classifier=`exec`，`every-agent-worker/pom.xml:88-107` repackage 配置）；文件名版本随根 pom 演进，以 `target/` 下实际 `*-exec.jar` 为准 |
| Maven 直跑 | 仓库根：`mvn -f every-agent-worker\pom.xml spring-boot:run` | 免手动先 package；工作目录 = 执行目录 |
| IDE | 主类 `dev.everyagent.worker.WorkerApplication`（声明于 `every-agent-worker/pom.xml:94`） | 务必核对运行配置的**工作目录**（§4.2） |
| 桌面版 | Electron 拉起 | cwd = 安装包 resourcesPath，见 §7 |

默认配置连 `ws://localhost:6101/ws`（`every-agent-worker/src/main/resources/application.yml:10-14`，api-key / hub-key 都有开发缺省值），**hub 没起也不影响插件加载**（§4.5 口径②第 1 条）；管理端口 6102 仅绑 127.0.0.1（`application.yml:135-137`）。冷启动到全部插件激活约 10 秒量级（25 内置 + 1 样例实测 10.3s，计划步骤 8）。

启动后 30 秒自检——三条日志定成败（同时落在 `<home>\logs\worker.log`，日志目录口径见[调试与测试](debugging-and-testing.md) §3.1）：

```powershell
Select-String -Path "$HOME\.everyagent\logs\worker.log" -Pattern '扫描到.*插件|加载完成|插件已激活: id=<你的id>'
```

1. `[plugins] 扫描到 N 个插件(来自 M 个扫描器)`（`PluginLoader.java:180`）——builtin 与 external 两个扫描器合计扫到多少；
2. `[plugins] 加载完成: N/M 个插件成功加载`（`PluginLoader.java:186`）——分母≠分子时，按 §2.4 的 WARN 文案逐个对号；
3. `[plugins] 插件已激活: id=<你的id>`（`PluginLoader.java:337`）——你的插件真正就位的唯一标志。

全部插件相关日志文案的一览表在[调试与测试](debugging-and-testing.md) §3.2。

### 4.2 cwd 决定内置插件目录

内置插件目录的解析（`every-agent-worker/src/main/java/dev/everyagent/worker/config/WorkerProperties.java:257-266`）：`worker.builtin-plugins-dir` 配置为空时，取**字面相对路径 `every-agent-plugins` 的绝对化**——即相对 JVM 工作目录（`user.dir`）解析，等价于 `<启动时所在目录>\every-agent-plugins\`。配置非空时支持 `~` 开头。由此：

| 从哪启动 | `user.dir` 通常是 | 默认能扫到？ | 处置 |
| --- | --- | --- | --- |
| 终端，cd 到仓库根再执行 | 仓库根 | ✓ | 无（推荐姿势） |
| IDE 运行配置 | 取决于配置（IDEA 常默认项目根或模块根） | 多数 ✓ 但须核对 | 把工作目录显式设为仓库根 |
| 脚本 / 服务 / 任意目录 | 该目录 | ✗ | 要么 cd 到仓库根，要么 `-Dworker.builtin-plugins-dir=<仓库根>\every-agent-plugins` 显式指过去 |
| 桌面安装包 | resourcesPath | ✓（copy-plugins 预摆布局） | 见 §7 |

扫不到时的日志（排查第一现场）：`WARN [plugins-builtin] 内置插件源码目录不存在,跳过扫描: <路径>(桌面安装包出现此行 = 打包未含 every-agent-plugins)`（`BuiltInPluginScanner.java:60`），随后 `INFO [plugins] 未扫描到任何插件`（`PluginLoader.java:172`）。**插件全没了先查 cwd，再查别的。**

目录覆盖除了 `-D` 系统属性，跟在 `java -jar` 后面的程序参数同样生效（Spring 命令行参数优先级更高，适合临时指定）：

```powershell
java -jar every-agent-worker\target\every-agent-worker-1.0.0-exec.jar --worker.builtin-plugins-dir=<仓库根>\every-agent-plugins
```

### 4.3 home-dir：正确覆盖方式（⚠️ 修正口径①）

系统目录（home）的配置键是 `worker.home-dir`——`@ConfigurationProperties("worker")` 绑定前缀 `worker`（`WorkerProperties.java:20`），默认值链在 `application.yml:19`：`home-dir: ${EVERYAGENT_HOME:${user.home}/.everyagent}`。生效的覆盖方式两种：

```powershell
# 方式一：环境变量（yml 占位符直读）
$env:EVERYAGENT_HOME = '<专用目录>'
java -jar every-agent-worker\target\every-agent-worker-1.0.0-exec.jar

# 方式二：系统属性（规范键 worker.home-dir）
java -Dworker.home-dir=<专用目录> -jar every-agent-worker\target\every-agent-worker-1.0.0-exec.jar
```

> ⚠️ **修正口径①（步骤 8 真机实测）**：`-Deveryagent.worker.home-dir=...` **不绑定**——不存在 `everyagent.worker.*` 这个命名空间，该写法被静默忽略，worker 继续用默认 `~/.everyagent`。同理，所有 worker 配置（`sandbox.*`、`plugins-dir`、`builtin-plugins-dir`……）的键都是 `worker.` 前缀，别给它再包一层 `everyagent.`。

home 的解析顺序与派生（`WorkerProperties.java:213-216,221-251`）：`worker.home-dir` 配置（已含 `EVERYAGENT_HOME` 占位）为空则取 `<user.home>/.everyagent`；**workspaces / skills / sandbox / plugins 四个目录全部由它派生**——改 home 一处，四处跟着走（外部插件目录见 §4.4）。dev profile 默认 `~/.everyagent-dev`（`application-dev.yml:11`）。home 下还可放 `application-worker.yaml` 覆盖模型等配置（`application.yml:152` 的 `spring.config.import`）。验证实例要隔离时，把 home 指到专用目录是最干净的一招（§6）。

### 4.4 外部插件目录

外部（external）插件目录 = `<home>/plugins`（`WorkerProperties.java:247-251`，默认即 `~/.everyagent/plugins/`），扫描约定与目录布局（`<id>/plugin.json` + `lib/*.jar` + `web/index.js`）见[打包与安装](packaging-and-install.md) §3.2——本篇只强调与构建相关的两点：它同样只在 **worker 启动时扫描一次**（改动需重启），且与内置目录互相独立（同 id 冲突时内置优先、外部被去重跳过，见[打包与安装](packaging-and-install.md) §5）。

### 4.5 hub 凭据校验在插件加载之前（⚠️ 修正口径②）

这条口径必须整体记住，两半都不能写错（均为计划步骤 8 真机实测 + 源码取证）：

1. **hub 连接失败可容忍**：hub 连接是异步的——`HubPool` 先就绪再逐条 `start()`（`every-agent-worker/src/main/java/dev/everyagent/worker/hub/HubPool.java:98-101`），连不上按 1s→30s 退避重连（`WorkerProperties.java:46-47`）。**hub 没起、端口不通，worker 照常完成插件扫描与激活**——「插件加载不依赖 hub 连接」仅指这一情形，用来做纯插件验证时可以不起 hub。
2. **hub 凭据校验失败终止启动，且发生在插件加载之前**：`worker.hubs` 条目 url 有效但 api-key / hub-key 任一为空时，`resolveHubs()` 直接抛 `IllegalStateException`（`WorkerProperties.java:201-204`，文案逐字：`worker.hubs[0](ws://...) 缺 api-key hub-key:两者均必填(hub 强校验 hubKey,apiKey 定义数据归属),缺配置拒绝启动`）。调用点是 `HubPool` 的 `@PostConstruct`（`HubPool.java:55-57`）——Spring 上下文启动中止，**插件扫描 / 激活根本不会开始**（实测：日志止于该异常，无任何 `[plugins]` 行）。

排查「插件一个都没加载」时，先确认启动没有早夭于凭据校验，再查 §4.2 的 cwd。

## 5. 开发迭代循环

### 5.1 改 java：package → 重启 worker

插件系统**没有热重载**，`deactivate` 仅在 worker 优雅关闭时调用、运行期禁用/卸载不触发（`PluginStateStore.java` 类注释自证；现状登记见[打包与安装](packaging-and-install.md) §4.2）。改完 java 源码的完整动作：`mvn -f every-agent-plugins\<id>\pom.xml package` → 重启 worker → 日志确认 `[plugins] 插件已激活: id=<id> ...`（`PluginLoader.java:337`）。单测先行的话用 `test` 目标快速回归，最后再 `package` 出 jar（取舍见[调试与测试](debugging-and-testing.md) §5）。

### 5.2 改 web：build:plugins → 刷新页面（worker 不重启）

前端宿主**每次页面加载**都经 `plugin.webSource` RPC 现拉 `web/index.js`（`every-agent-web/src/plugin/pluginLoader.ts:421-429`），bundle 就躺在插件目录里由 worker 按需读——所以 web 改动的生效只差两步：重跑 `build:plugins`（或开着 `watch:plugins` 等自动重编）→ **手动刷新浏览器**（页面加载的是构建时的快照，没有热替换）。worker 全程不用重启。加载链路每一步的失败症状见[前端总览与加载链路](../web/overview-and-loading.md)。

### 5.3 生效边界对照表

| 你改了 / 做了什么 | 要跑什么 | worker 重启？ | 页面刷新？ |
| --- | --- | --- | --- |
| java 源码 / pom | `mvn ... package` | ✓ 必须 | ✓（前端可见的插件） |
| `web/*.ts`（含 CSS） | `build:plugins` 或等 watch 自动 | ✗ | ✓ 必须 |
| `plugin.json`（java/full 形态） | `mvn ... package`（清单随 jar 进 `target/classes`，加载以它为准） | ✓ | ✓ |
| `plugin.json`（纯 web 插件） | 无需构建 | ✓（启动扫描读根清单） | ✓ |
| 安装 / 替换 / 删除外部插件目录 | — | ✓ | ✓ |
| `plugin.enable` / `plugin.disable` RPC 或手编 `.disabled-plugins` | — | ✓（名单落盘，下次启动生效） | ✓ |
| 宿主 `every-agent-web/src` | `npm.cmd run dev` 或 `build` | ✗（宿主自己的构建线，§3.1） | ✓（dev 服务器自管） |

一句话记忆：**凡是被 worker 在启动时读的东西都要重启（java 产物、清单、两个插件目录、禁用名单）；只有 `web/index.js` 是页面加载时现拉的，刷新即得。**

## 6. 本机多实例避坑（步骤 8 实测素材）

一台机器上已经有在跑的 Every Agent（桌面版或开发实例）时，再起一个验证用 worker 有两个坑：

**坑一：默认端口冲突。** hub 默认 `ws://localhost:6101/ws`（`application.yml:11`）、worker 管理端口 6102（`application.yml:136`）——被在跑实例占用时新实例起不来（bind 失败即退）。先查占用再起：

```powershell
netstat -ano | findstr 6101    # / 6102；受限环境里 Get-NetTCPConnection 结果不可信，netstat 稳（步骤 8 实测）
```

dev profile 用 8100 / 8200（`application-dev.yml:5,13`），步骤 8 的隔离组合是 **hub 6111 + worker 6112**：验证时给 worker 换独立端口对即可。

**坑二：同 ownerKey + clientId 会「抢占」在跑 worker。** workerId 就是对 hub 握手的 clientId（`HubPool.java:145` 注释口径）；hub 对 worker 角色做连接抢占——**同 ownerKey + 同 clientId 的新连接一来，旧连接先广播 `worker.offline` 再被移除关闭**（`every-agent-hub/src/main/java/dev/everyagent/hub/reg/ConnectionRegistry.java:19-20`、`HubConnection.java:136`）。默认 `WORKER_ID=company-pc`（`application.yml:6`）：不改 id 直接起新 worker，会把你正在用的那个顶下线，表现为「旧实例突然离线」。

**处方：验证实例永远独立 WORKER_ID + 独立端口 + 隔离 home。** 参数形状（步骤 8 同款口径，逐项对应 §4.3 / 坑一 / 坑二）：

```powershell
$env:WORKER_ID       = 'verify-1'        # 别用默认 company-pc（防抢占）
$env:EVERYAGENT_HOME = '<专用目录>'       # 隔离系统目录：日志/外部插件/workspaces 全部与日常实例分家
java '-Dworker.hubs[0].url=ws://localhost:6111/ws' '-Dserver.port=6112' `
     -jar every-agent-worker\target\every-agent-worker-1.0.0-exec.jar
```

（hub 连不上也没关系——§4.5 第 1 条，纯插件验证可以不起 hub。）

**受限 / 沙箱环境的补充坑**（正常机器可忽略，来源同步骤 8）：hub、worker、验证探针要在**同一次调用内**全部完成（进程随调用结束被回收，分次起会互相看不见）；Maven 用 §2.3 的离线配方；查端口用坑一里的 `netstat`。

## 7. 桌面版形态

桌面打包链**包含插件构建**（这是全站 §5.y 修正的口径，别再按旧笔记写「不在任何流水线里」）。事实链（源码取证）：

- `every-agent-desktop/package.json:16` 的 `build:assets` 依次执行 `build:backend && build:plugins && copy:plugins && build:web && build:jre && build:plugin-runtime`；
- 其中 `build:plugins` = `python ../scripts/build-plugins.py`（`every-agent-desktop/package.json:11`），该包装脚本统一驱动 java 侧（`every-agent-plugins/*/pom.xml`）与 web 侧（`:219` 以 `run([node, WEB_BUNDLE_SCRIPT], cwd=WEB_DIR)` 调用 web 的 `build-plugins.mjs`，即 §3 的同一脚本——**唯一事实源，不重复实现**）；
- `copy:plugins`（`every-agent-desktop/scripts/copy-plugins.mjs:1-6` 头注释）把插件产物 staging 到 `every-agent-desktop/resources/every-agent-plugins/`，经 electron-builder `extraResources` 进安装包的 `<resourcesPath>/every-agent-plugins/`；worker 打包态 cwd = resourcesPath 且不传 `builtin-plugins-dir`，§4.2 的默认解析正好命中；jar / manifest / bundle 任缺即报错中止（fail-fast，绝不带病打包）。

**未实测声明**：桌面安装包端到端是否随包分发并在用户机器上加载全部内置插件——本文未跑 `dist` 链验证，按「未实测」登记，不断言；设计意图即上述链路，另一个佐证是 `BuiltInPluginScanner.java:60` 的 WARN 文案专门点名了桌面安装包场景。现状登记见[已知问题与现状偏差](../reference/known-issues.md)。

## 8. 下一步读

| 接下来 | 读 |
| --- | --- |
| 给插件写单测、看日志定位、五个调试剧本 | [调试与测试](debugging-and-testing.md) |
| 把产物打成 `.eap` 分发 / 安装 / 卸载 / 启停 | [打包与安装](packaging-and-install.md) |
| 现状偏差清单（API 未发布、死扩展点、桌面未实测等） | [已知问题与现状偏差](../reference/known-issues.md) |
