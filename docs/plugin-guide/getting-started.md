---
title: 快速上手
nav_order: 2
has_children: false
---

# 快速上手

> 本篇目标：用仓库自带的脚手架 `create-everyagent-plugin` 在 5 分钟内生成并跑通第一个插件，再顺着三个最小实验建立「改哪里 → 跑什么 → 看到什么」的手感。命令全部为 Windows PowerShell 口径、可直接复制；预期输出来自真机验证（仓库计划步骤 4/6 的 Maven 构建与步骤 9 的前端构建实测），未实测处明确标注。

## 1. 前置条件

### 1.1 工具链

| 工具 | 要求 | 只在什么时候需要 | 自检命令 |
|---|---|---|---|
| Node.js | ≥ 20（实测环境 v24.15.0） | 总是（脚手架与前端构建） | `node --version` |
| npm | 随 Node 附带（实测 11.12.1） | 总是 | `npm.cmd --version` |
| JDK 25 + Maven | `JAVA_HOME` 指向 JDK 25，`mvn` 在 PATH | **仅 java / full 形态** | `mvn -version` |
| Every Agent 仓库 | 已克隆到本地 | builtin 形态（生成进 `every-agent-plugins/`）；standalone 形态只需能跑到脚手架目录 | — |

- 脚手架 CLI 就在仓库根的 `create-everyagent-plugin/` 目录，**零运行时依赖**（只用 `node:*` 内置模块），不需要先 `npm install` 就能跑。
- java / full 形态首次构建前需要在仓库根执行一次 `mvn -pl every-agent-plugin-api -am install -DskipTests`（把插件唯一允许的实现依赖装进本地 Maven 仓库；正常环境一次性，详见 §5）。

### 1.2 Windows PowerShell 的两个坑

1. **`npm` 可能被执行策略拦截**：`npm` 命令解析到 `npm.ps1` 垫片，在默认执行策略下会报「无法加载文件 …npm.ps1，因为在此系统上禁止运行脚本」。解决办法是不走垫片、直接用 `npm.cmd`。本篇所有命令一律写 `npm.cmd`；`npx` 同理（被拦时用 `npx.cmd`）。
2. **npm cache 不可写（受限 / 沙箱环境）**：`npm install` 写默认缓存目录可能遇到 `EPERM`。给它指一个可写目录即可：
   ```powershell
   npm.cmd install --cache "$env:TEMP\npm-cache"
   ```
   只有需要真正装依赖的场合（standalone 形态的 `npm install`、every-agent-web 首次装依赖）才涉及；脚手架本身零依赖，不受影响。

## 2. 一条命令生成插件工程

CLI 用法与全部 flag 以 [`create-everyagent-plugin/README.md`](../../create-everyagent-plugin/README.md) 为准（本文命令与它逐字一致）：

```text
node create-everyagent-plugin [dir] [选项]
node create-everyagent-plugin pack <pluginDir> [-o <输出目录>] [--verify]    # .eap 打包
```

常用 flag：`--id` / `--name` / `--desc` / `--author` / `--kind <java|web|full>` / `--mode <builtin|standalone>` / `--yes, -y` / `--force, -f` / `--dry-run` / `-h, --help` / `-v, --version`。

在**仓库根**执行（下同）：

```powershell
node create-everyagent-plugin
```

### 2.1 交互式：逐项问答

不带 `--yes` 且 stdin 是 TTY 时，按 id → kind → mode → name → desc → author 逐项问答。下面是与 CLI 的真实对话（提问文案逐字还原自 `create-everyagent-plugin/lib/prompts.mjs:99-164`；`>` 后是键入内容，行尾直接回车即取缺省）：

```text
插件 id
  规则：小写字母/数字/连字符，2~39 字符，首尾不能是连字符
  例如 empty-response-retry > sample-web
插件形态 kind：
  1) java —— 只有后端（Java 工具 / Advisor / RPC，产出 jar，无前端）
  2) web —— 只有前端 UI（web/index.ts 导出 PluginModule，无 worker 端代码）
  3) full —— 前后端都要（一份 plugin.json 同时含 main 与 webMain）
选择插件形态 [java/web/full]（回车取 full）> web
工程形态 mode：
  1) builtin —— 生成到仓库内 every-agent-plugins/<id>/，复用宿主依赖与 typecheck
  2) standalone —— 仓库外独立工程，自带 plugin-api 类型副本 + tsconfig + 构建脚本
选择工程形态 [builtin/standalone]（回车取 builtin）> builtin
插件显示名 name（回车取 "sample-web"）>
插件描述 desc（回车取 "sample-web 插件"）>
作者 author（回车取 "everyagent"）>
```

交互细节：

- 带目录参数启动（如 `node create-everyagent-plugin sample-web`）时，id 问题会显示缺省提示：`插件 id （回车取 "sample-web"）`，直接回车即可。
- kind / mode 也接受 `1/2/3` 速选（数字对应列表序号）；**flag 已给过的问题不再问**；无缺省值的 id 输错会重新提问并打印不合规原因。
- `Ctrl+C` → 退出码 130，**不写任何文件**。
- stdin 不是 TTY 且没有 `--yes` 时：能确定 id 就继续（全部取缺省），否则直接报错，不会挂起。
- 目标目录已存在且非空时拒绝生成（退出码 3）；`--force` 才允许覆盖，且只写同名文件、不删除其他既有文件。

### 2.2 非交互：全 flag 一条命令

CI / 脚本场景用 `--yes` 取全部缺省、其余项用 flag 给齐（README 示例原文形态：`node create-everyagent-plugin my-tool --kind full --yes`）：

```powershell
# 本篇主线：纯前端示例插件，builtin 形态自动落到 every-agent-plugins/sample-web/
node create-everyagent-plugin --id sample-web --kind web --yes

# 全 flag 形态（name/desc/author 都自定义）
node create-everyagent-plugin --id sample-web --name "示例插件" --desc "我的第一个 Every Agent 插件" --author everyagent --kind web --mode builtin --yes

# 发布到 npm 后等价于（参数完全相同）
npm create everyagent-plugin my-tool
```

注意：给了位置参数 `[dir]` 就用该目录（目录名当作 id 缺省值）；**不给** `[dir]` 时才按 mode 推导缺省位置——builtin → `<仓库根>/every-agent-plugins/<id>/`，standalone → `./<id>/`。要让插件被 worker 内置扫描自动发现，请像上面第一条那样省略 `[dir]`，或显式写全 `every-agent-plugins/sample-web`。

### 2.3 `--dry-run`：先预览不落盘

```powershell
node create-everyagent-plugin --id sample-web --kind web --yes --dry-run
```

真实输出（仅 `模板根` / `绝对路径` 两行按本机仓库根展开，其余逐字）：

```text
插件 id      sample-web
形态         kind=web  mode=builtin
入口类       dev.everyagent.plugin.sampleWeb.SampleWebPlugin
Java 包      dev.everyagent.plugin.sampleWeb
目标目录     every-agent-plugins/sample-web
模板根       <仓库根>\create-everyagent-plugin\templates
命中模板层   common → web
文件数       4

将生成的文件树（绝对路径 <仓库根>\every-agent-plugins\sample-web）：

sample-web/
├── web/
│   └── index.ts  前端插件入口：默认导出 PluginModule，activate() 里经 ctx.ui.registerSidebarItem 注册侧边栏
├── .gitignore    插件级忽略规则：只挡构建产物，与仓库根 .gitignore 的内置插件条目同口径
├── plugin.json   插件清单（id/name/version/main/webMain/enabled），宿主扫描与加载的唯一依据
└── README.md     插件根 README：本插件的构建 / 验证 / 生效 / 打包命令速查（按 kind 与 mode 索引对应小节）

--dry-run：以上文件未写入。
```

说明：`入口类` / `Java 包` 两行对 web 形态只是命名推导的展示（id 推导规则：`pdf-viewer` → 包 `dev.everyagent.plugin.pdfViewer` → 入口 `PdfViewerPlugin`），web 形态不会生成任何 Java 文件。六种组合都能这样预览，覆盖关系会标注「（覆盖 <来源模板>）」。

### 2.4 退出码速查

| 码 | 含义 |
|---|---|
| 0 | 成功 |
| 1 | 用法错误 |
| 2 | 参数校验失败 |
| 3 | 目标目录冲突 |
| 4 | 写盘失败 |
| 5 | 模板错误 |
| 130 | 用户取消（交互中 Ctrl+C） |

## 3. kind × mode：六种组合怎么选

文件数为实测值（步骤 6 六组合矩阵真跑核对，含 `README.md` 与 `.gitignore`）：

| 组合 | 文件数 | 生成什么 | 适合谁 | 下一步构建命令 |
|---|---|---|---|---|
| java × builtin | 7 | `plugin.json`（含 `main`）、`pom.xml`（parent=every-agent-parent）、入口类 + 冒烟测试 + 空 resources、README、`.gitignore` | 要给 agent 加后端能力（工具 / Advisor / RPC），且在宿主仓库内开发 | `mvn -f every-agent-plugins/<id>/pom.xml package` |
| java × standalone | 7 | 同上，唯 `pom.xml` 为无 parent 版（自带 groupId / 编码 / Java 25 属性与显式版本） | 后端插件想在仓库外独立维护，产物走 `.eap` 分发 | 先 `mvn -pl every-agent-plugin-api -am install -DskipTests`（宿主仓库根，一次），再在插件目录 `mvn package` |
| web × builtin | 4 | `plugin.json`（仅 `webMain`）、`web/index.ts`、README、`.gitignore`——无 pom、无 npm 文件 | 只要界面（侧边栏 / 视图），不动后端；**最快路径，本篇 §4 主线** | `cd every-agent-web` → `npm.cmd run build:plugins` |
| web × standalone | 8 | web × builtin 的 4 项 + npm 工具链 4 件：`package.json`、`tsconfig.json`、`scripts/build.mjs`、`vendor/@everyagent/plugin-api/index.d.ts` | 前端插件在独立仓库开发（`@everyagent/plugin-api` 未发布 npm，自带类型副本） | 插件目录：`npm.cmd install` → `npm.cmd run build` |
| full × builtin | 8 | java × builtin 全套 + `web/index.ts`；`plugin.json` 为**同时含 `main` 与 `webMain`** 的合并版（覆盖不增文件数） | 前后端联动（如前端调自注册 RPC），仓库内一体化开发 | mvn 与 build:plugins 两条都要（§5 + §4 步骤 3） |
| full × standalone | 12 | java × standalone 全套 + `web/index.ts` + npm 工具链 4 件，`plugin.json` 同为合并版 | 完整独立插件工程，前后端工具链都自带 | 先装 plugin-api，再 `mvn package` + `npm.cmd install` + `npm.cmd run build` |

两条横轴的判断口径：

- **kind 决定插件的形态**（`plugin.json` 写 `main` 还是 `webMain`，与内置 `task-queue` / `git` / `pdf-viewer` 三形态一一对应，详见 [plugin.json 全字段](plugin-manifest.md)）。
- **mode 决定工程的位置与工具链**：builtin 生成进仓库 `every-agent-plugins/<id>/`，类型检查与构建**复用宿主**（无需自带 tsconfig / package.json），worker 重启即被内置扫描发现；standalone 生成到仓库外，自带类型副本与构建脚本，产物按外部插件安装（见 [打包与安装](guides/packaging-and-install.md)）。

## 4. 5 分钟跑通第一个插件（web × builtin 主线）

四个步骤：生成 → 类型检查 → 构建 bundle → 重启 worker 刷新页面。以下命令与预期输出均为真机验证口径（计划步骤 9 实测：typecheck 3.8s、build:plugins 0.4s）。

### 步骤 1：生成工程（仓库根执行）

```powershell
node create-everyagent-plugin --id sample-web --kind web --yes
```

预期输出：先打印 §2.3 那份文件树，随后：

```text
已生成 4 个文件 → <仓库根>\every-agent-plugins\sample-web
（位置：仓库内 every-agent-plugins/sample-web/，worker 重启后即被内置扫描发现）

下一步：

1) 构建前端 bundle（改 web/ 下任何文件都要手工重跑，它不在 dev/build 任何流水线里）
   cd every-agent-web
   npm run build:plugins
   （PowerShell 若被执行策略拦下 npm，请改用 npm.cmd run build:plugins）

最后一步（两种形态都要）：重启 worker —— 插件没有热重载，且内置插件要求 cwd 在仓库根。
验证是否加载：看 worker 日志「插件已激活: id=...」，或前端调用 plugin.list RPC。

打包分发（.eap，重启验证前的最后一步可选）：
   node create-everyagent-plugin pack every-agent-plugins/sample-web --verify
```

（「下一步」块为 CLI 打印原文；`npm run` 在 PowerShell 下请按本文口径用 `npm.cmd run`。纯 web 插件没有后端 `activate()`，worker 会把它登记为声明式插件，验证以 `plugin.list` 与页面侧边栏为准。）

### 步骤 2：类型检查（宿主 tsconfig 已覆盖插件）

```powershell
cd every-agent-web
npm.cmd run typecheck
```

预期输出：**无任何输出**即通过（该脚本就是 `tsc --noEmit`，实测约 4 秒）。宿主 `every-agent-web/tsconfig.json` 的 `include` 含 `"../every-agent-plugins/*/web"`，所以这一步已经同时检查了刚生成的 `sample-web/web/index.ts`——这就是 builtin 形态「复用宿主 typecheck」的含义。

### 步骤 3：构建前端 bundle

```powershell
npm.cmd run build:plugins
```

预期输出（数字随仓库插件数变化；该脚本无增量判断，每次**全量重编**所有插件入口，实测 < 1 秒）：

```text
[build-plugins] 发现 10 个插件入口:
  - …（逐个列出插件 id，含 sample-web）

[build-plugins] 构建 sample-web ...

[build-plugins] 完成，共构建 10 个插件。
```

产物落在 `every-agent-plugins/sample-web/web/index.js`（带 `.js.map`；模板没有 CSS import，不会产 `index.css`）。该脚本固定扫描 `every-agent-plugins/<id>/web/index.ts`，**不在 dev / build 任何流水线里**——以后每改一次 `web/` 都要手工重跑这一步（详见 [构建与运行](guides/build-and-run.md)）。

### 步骤 4：重启 worker，刷新页面

1. 重启 worker 进程，**cwd 必须在仓库根**（内置扫描按 `user.dir` 找 `every-agent-plugins/`）。插件没有热重载，这一步不能省。
2. 刷新浏览器页面。

预期看到：左侧活动栏最末（缺省 `order: 100`，排在全部内置项之后）出现一个 `◆` 图标，tooltip 为 `sample-web`；点击展开面板，标题 `sample-web`、正文「面板已就绪：编辑 web/index.ts 替换这里的内容，然后在 every-agent-web 下重跑 npm run build:plugins 并刷新页面。」（面板文案为模板原文；前三步的命令与耗时为步骤 9 实测，页面表现按 [前端总览与加载链路](web/overview-and-loading.md) §7 的验证口径给出）。

图标不出现 / 面板空白 → 逐条对照 [故障排查](guides/troubleshooting.md)；加载链路每一步的失败症状见 [前端总览与加载链路](web/overview-and-loading.md)。

## 5. java / full 形态的构建（Maven）

java 与 full 的后端部分完全一致（full 只是多了 `web/index.ts` 与合并版 `plugin.json`）。要点：

- **插件不进根 reactor**：根 `pom.xml` 的 `<modules>` 不包含 `every-agent-plugins/*`，所以必须 `-f` 单独构建，不能在仓库根裸跑 `mvn package` 指望带上网罗。
- **首次需要装 plugin-api**：`dev.everyagent:every-agent-plugin-api` 未发布到 Maven Central（实测 404），本地仓库里得先有它。正常环境在仓库根执行一次即可：

```powershell
# ① 仓库根，一次性：把插件唯一允许的实现依赖装进本地仓库
mvn -pl every-agent-plugin-api -am install -DskipTests

# ② 单独构建插件（-f 指向插件 pom；冒烟测试随 package 一起跑，报告在 target/surefire-reports/）
mvn -f every-agent-plugins/sample-java/pom.xml package

# ③ 验证 jar 内带了清单（预期输出一行：plugin.json）
jar tf every-agent-plugins\sample-java\target\sample-java-0.1.0.jar | findstr plugin.json
```

- **产物与机制一句话**：`target/sample-java-0.1.0.jar`（版本缺省 `0.1.0`，也即 `.eap` 命名的版本源）；pom 内联的 `maven-resources-plugin` 在 `process-resources` 阶段把根目录 `plugin.json` 复制进 `target/classes` 并随 jar 打包——而 worker 的内置扫描器正是以 `target/classes/plugin.json` 的存在来判定「这是个 java 插件」的（无 target 产物则按纯 web 插件纳入，详见 [后端模型总览](backend/overview.md)）。
- **生效**：重启 worker（cwd 在仓库根），日志出现 `[plugins] 插件已激活: id=sample-java name=… v0.1.0 entry=… source=builtin` 即激活成功（日志格式取自 `every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginLoader.java:337`；插件编译与 jar 内含清单已真机验证，worker 内激活这一环本篇按源码口径给出、未单独复跑）。
- full 形态在 ② 之外还要做 §4 步骤 3 的 `build:plugins`，两条都完成再重启。

## 6. standalone 形态：仓库外独立工程

适用于插件不进 Every Agent 仓库的场景。在目标父目录执行（CLI 在宿主仓库里，用路径指过去；发布到 npm 后可用 `npm create everyagent-plugin my-tool`）：

```powershell
# 在你自己的工作目录执行；缺省生成到 .\my-tool\
node <宿主仓库根>\create-everyagent-plugin --id my-tool --kind web --mode standalone --yes
```

生成 8 个文件（web × standalone）：§3 表中 web × builtin 的 4 项 + `package.json`、`tsconfig.json`、`scripts/build.mjs`、`vendor/@everyagent/plugin-api/index.d.ts`。然后在插件目录：

```powershell
cd my-tool
npm.cmd install                  # 受限环境 cache 不可写时：npm.cmd install --cache "$env:TEMP\npm-cache"
npx.cmd tsc --noEmit             # 类型检查：开箱全绿，无输出即通过
npm.cmd run build                # 即 node scripts/build.mjs，产物 web/index.js（esbuild，选项与宿主逐项一致）
```

三点说明：

- **`tsc --noEmit` 开箱全绿**是修复后的状态：早期模板的 tsconfig 缺 react 类型映射会报错（计划步骤 9 发现的 P1），现已把 `react` / `react-dom` / `react/jsx-runtime` 的 paths 指向 `package.json` 预置的 `@types/react(-dom)`（web-standalone 与 full-standalone 两份模板同步修复），`npm install` 后零配置通过。被 PowerShell 拦截时用 `npx.cmd`。
- **vendor 类型副本的升级方式**：`@everyagent/plugin-api` 未发布 npm，副本逐字节复制自宿主 `every-agent-plugin-api/js/index.ts`；宿主 API 升级后，重新把该文件复制覆盖 `vendor/@everyagent/plugin-api/index.d.ts` 即可。
- **standalone 插件不会被内置扫描发现**（在仓库外），分发安装走 [打包与安装](guides/packaging-and-install.md)：`node <宿主仓库根>\create-everyagent-plugin pack .` 打出 `<id>-<version>.eap`，或手工把产物放进 worker 机器的 `~/.everyagent/plugins/<id>/`（jar 在 `lib/`、bundle 在 `web/index.js`、`plugin.json` 在顶层），重启 worker 生效。java × standalone 的 Maven 部分见 §5（同样需要先 `mvn -pl every-agent-plugin-api -am install -DskipTests` 装好本地依赖）。

## 7. 改点什么先试试（三个最小实验）

都在 §4 的 `sample-web` 上做，只动 `every-agent-plugins/sample-web/web/index.ts` 一个文件。前端改动**不需要重启 worker**：重跑 build:plugins + 刷新页面即可（重启 worker 只在后端 / `plugin.json` 改动时需要）。

### 实验 ①：改面板标题

- **改哪行**：`SidebarPanel()` 里的 `React.createElement('h3', { style: { margin: '0 0 8px' } }, 'sample-web')`——把最后的 `'sample-web'` 换成任意文案。
- **跑什么**：`cd every-agent-web; npm.cmd run build:plugins`，然后刷新页面。
- **看到什么**：面板标题变成新文案。这一实验建立最关键的手感：**宿主加载的是产物 `web/index.js`，不是源码 `index.ts`**——只改不构建，页面不会有任何变化。

### 实验 ②：改 `order` 看图标排序

- **改哪行**：`activate()` 里 `sidebarItem` 定义的 `order: 100,` 改成 `order: 4,`。
- **跑什么**：同上，重跑 build:plugins + 刷新页面。
- **看到什么**：图标从活动栏最末挪到 `search`（3）与 `git`（5）之间。侧边栏是 float 升序坐标系：内置 tasks=1 / files=2 / search=3 / git=5 / 扩展管理=9 / settings=10，未声明缺省 100，支持小数（如 4.5）插空。字段全表见 [UI 扩展点](web/ui-extensions.md)。

### 实验 ③：用 `ctx.storage` 存一点跨页面持久的值

- **改哪行**：`activate(ctx: PluginContext) {` 的第一行起加入：

```ts
    // storage：按 pluginId 前缀隔离的 localStorage（宿主 pluginLoader.ts 实现），跨页面持久
    const last = ctx.storage.get<string>('activated-at')
    ctx.storage.set('activated-at', new Date().toISOString())
    console.log('[sample-web] 上次激活：', last ?? '（首次）')
```

- **跑什么**：重跑 build:plugins，刷新页面，再刷新一次。
- **看到什么**：浏览器控制台第一次打印「（首次）」，之后每次刷新都打印上一次的时间戳——证明 `ctx.storage` 在页面重载间持久。API 细节见 [PluginContext API](web/context-api.md)。

## 8. 下一步读

| 你接下来想做什么 | 读 |
|---|---|
| 给 agent 加后端能力（工具 / 沙箱 / Advisor / 任务生命周期 / RPC） | [后端模型总览](backend/overview.md) → [工具与沙箱](backend/tools-and-sandbox.md) → [Advisor 扩展](backend/advisors.md) → [任务生命周期与 RPC](backend/task-and-rpc.md) |
| 写前端界面（侧边栏 / 编辑器 / 工具调用视图 / 事件） | [前端总览与加载链路](web/overview-and-loading.md) → [PluginContext API](web/context-api.md) → [UI 扩展点](web/ui-extensions.md) → [事件](web/events.md) |
| 把插件打包分发给别人安装 | [构建与运行](guides/build-and-run.md) → [打包与安装](guides/packaging-and-install.md) |

不管走哪条路，先过一遍 [plugin.json 全字段](plugin-manifest.md)——清单里 `webMain` 的值不被前端消费（加载路径硬编码 `web/index.js`）、`enabled` 只有内置扫描器读等陷阱都在那一篇。
