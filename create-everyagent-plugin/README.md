# create-everyagent-plugin

Every Agent 插件工程脚手架 CLI —— create-react-app / create-vite 式的一条命令体验：交互问答（或全 flag 传参）生成**可直接构建**的插件工程，java / web / full 三种形态 × builtin / standalone 两种模式，共 6 种组合；自带 `pack` 子命令把构建产物打成 `.eap` 安装包。

- **零运行时依赖**：只用 `node:*` 内置模块（见下文「零依赖硬约束」）。
- **模板可叠加**：kind / mode 的内容差异全部靠模板层表达，无分支语法（见下文「模板目录契约 v1」）。
- Node ≥ 20。

## 快速开始

```powershell
# 开发期主用法（仓库内，node 直跑）
node create-everyagent-plugin my-tool --kind full --yes

# 发布到 npm 后等价于（bin 链接，参数完全相同）
npm create everyagent-plugin my-tool
```

不带 flag 时进入交互问答（id → kind → mode → name → desc → author，逐项给缺省值）；`--yes` 全取缺省。生成结束后 CLI 打印「下一步」命令块：构建后端 jar（`mvn -f … package`）→ 构建前端 bundle（`npm run build:plugins` / `node scripts/build.mjs`）→ 重启 worker 生效 → `.eap` 打包。

## 命令行用法

```
node create-everyagent-plugin [dir] [选项]
node create-everyagent-plugin pack <pluginDir> [-o <输出目录>] [--verify]    # .eap 打包（pack --help 看详情）
```

### 位置参数

| 参数 | 说明 |
| --- | --- |
| `dir` | 输出目录。给了就用它（目录名会当作 `--id` 的缺省值）；缺省时按 mode 推导：builtin → `<仓库根>/every-agent-plugins/<id>/`，standalone → `./<id>/`。仓库根 = 含 `every-agent-plugins/` 的最近祖先目录（从 cwd 与 CLI 包目录向上找） |

### 选项（flags）

| 选项 | 说明 |
| --- | --- |
| `--id <id>` | 插件 id：`^[a-z0-9][a-z0-9-]{1,38}$` 且首尾不能是连字符（2~39 字符） |
| `--name <显示名>` | plugin.json 的 name，缺省 = id |
| `--desc <描述>` | plugin.json 的 description，缺省 = `"<name> 插件"` |
| `--author <作者>` | plugin.json 的 author，缺省 everyagent |
| `--kind <java\|web\|full>` | java=只有后端；web=只有前端 UI；full=前后端都要（缺省 full） |
| `--mode <builtin\|standalone>` | builtin=建在仓库内 `every-agent-plugins/` 下，复用宿主依赖与 typecheck；standalone=仓库外独立工程，自带 plugin-api 类型副本 + tsconfig + 构建脚本（缺省 builtin） |
| `--yes, -y` | 非交互：全部取缺省值（必须能确定 --id，否则报错） |
| `--force, -f` | 目标目录已存在且非空时允许覆盖（只写同名文件，不删除其他既有文件） |
| `--dry-run` | 只打印将生成的文件树与每个文件的作用，不写盘 |
| `-h, --help` | 显示帮助 |
| `-v, --version` | 显示版本 |

文本字段（name/desc/author）的安全化：控制字符与换行直接拒收；直双引号 → 全角 `”`、反斜杠 → 全角 `＼`（保证 plugin.json 的 JSON 合法性与 Java 注释安全），连续空白压成单空格。

### 退出码

| 码 | 含义 |
| --- | --- |
| 0 | 成功 |
| 1 | 用法错误 |
| 2 | 参数校验失败 |
| 3 | 目标目录冲突 |
| 4 | 写盘失败 |
| 5 | 模板错误 |
| 130 | 用户取消（交互中 Ctrl+C） |

## 交互流程

stdin 是 TTY 且未给 `--yes` 时，按序逐项问答；**flag 已给过的问题不再问**，回车取缺省值，无缺省值的（id）重新提问：

1. **插件 id**（缺省 = 位置参数目录名）——规则与示例直接印在提示里；
2. **插件形态 kind**（缺省 full；也接受 `1/2/3` 速选）——java / web / full 三选一，每个选项附一句差异说明；
3. **工程形态 mode**（缺省 builtin；接受 `1/2` 速选）——builtin / standalone 二选一；
4. **插件显示名 name**（缺省 = id）；
5. **插件描述 desc**（缺省 = `<name> 插件`）；
6. **作者 author**（缺省 everyagent）。

stdin 不是 TTY 且没有 `--yes` 时，若连 id 都无法确定则直接报错（不会挂起）；给了 `--yes` 则全部取缺省值。Ctrl+C → 退出码 130，不写任何文件。

## kind × mode 六组合产物清单

每个生成插件的根目录都带 `README.md`（本插件的构建/验证/生效/打包命令）与 `.gitignore`（`target/`、`web/index.js(.map)`、`web/index.css(.map)`、`node_modules/`、`dist/`）。

| 组合 | 文件数 | 产物清单（相对插件根） |
| --- | --- | --- |
| java × builtin | 7 | `plugin.json`（含 main）、`pom.xml`（parent=every-agent-parent，relativePath `../../pom.xml`）、`README.md`、`.gitignore`、`src/main/java/<包路径>/<入口类>.java`、`src/main/resources/.gitkeep`、`src/test/java/<包路径>/<入口类>SmokeTest.java` |
| java × standalone | 7 | 同上，唯 `pom.xml` 为无 parent 版（自带 groupId / 编码 / Java 25 属性与显式版本） |
| web × builtin | 4 | `plugin.json`（仅 webMain）、`web/index.ts`、`README.md`、`.gitignore` —— 无 pom、无 npm 文件（类型检查与构建复用宿主） |
| web × standalone | 8 | web × builtin 的 4 项 + npm 工具链 4 件：`package.json`、`tsconfig.json`、`scripts/build.mjs`、`vendor/@everyagent/plugin-api/index.d.ts`（无 pom） |
| full × builtin | 8 | java × builtin 全套 + `web/index.ts`，其中 `plugin.json` 被替换为**同时含 main 与 webMain** 的合并版（overlay/full 覆盖 web 基础层那份，覆盖不增文件数）；无 npm 文件 |
| full × standalone | 12 | java × standalone 全套（pom 为无 parent 版）+ `web/index.ts` + npm 工具链 4 件，`plugin.json` 同为 main + webMain 合并版 |

命名推导（`lib/naming.mjs` 唯一事实源）：`{{package}}` = `dev.everyagent.plugin.<camelCase(id)>`；入口类简名 = PascalCase(id) + `Plugin`；`main` = 二者拼接。示例：id `pdf-viewer` → 包 `dev.everyagent.plugin.pdfViewer` → 入口 `dev.everyagent.plugin.pdfViewer.PdfViewerPlugin`。

## 模板目录契约 v1

> **以 [`lib/render.mjs`](lib/render.mjs) 顶部注释为唯一事实源**：下面这段是它的逐字誊抄（去掉行注释前缀）。两处必须同步改——改契约先改 render.mjs，再誊抄到这里，不允许只改一边。

```text
==================================================================================
模板目录契约 v1（步骤 4~6 的模板作者只看这一份；README「模板目录契约」节与此逐字一致）
==================================================================================

一、模板根解析顺序
  1) 环境变量 EA_PLUGIN_TEMPLATES（绝对路径或相对 cwd 的路径）—— 自测与本机调试用
  2) <create-everyagent-plugin 包目录>/templates

二、层目录与叠加顺序（后面的层可以覆盖前面的同相对路径文件）
  L1  common/                 所有 kind × mode 共有（README.md.tpl、gitignore.tpl）
  L2  java/                   kind ∈ {java, full} 时叠加
  L2  web/                    kind ∈ {web,  full} 时叠加
  L3  overlay/<kind>/         kind 整合层：overlay/java | overlay/web | overlay/full
  L4  overlay/<mode>/         mode 通用层：overlay/builtin | overlay/standalone
  L5  overlay/<kind>-<mode>/  最特异层：overlay/web-standalone、overlay/full-builtin …
  不存在的层自动跳过（不必创建全部目录）；模板根不存在或一层都没命中 → 报错。

三、冲突语义
  - L1+L2 是「基础层」：同一相对路径被两个基础层命中 → 直接报错，绝不静默覆盖。
    ⇒ kind=full 时 java/ 与 web/ 不得互相撞名，也不得与 common/ 撞名。
  - L3~L5 是「覆盖层」：允许覆盖基础层与更弱的覆盖层；被覆盖项在 --dry-run 树里标注
    「(覆盖 <来源模板>)」。
    ⇒ full 形态那份「同时含 main + webMain 的 plugin.json」应放 overlay/full/plugin.json.tpl，
      java/ 与 web/ 各放自己单 kind 用的 plugin.json.tpl，靠覆盖层解决，不算冲突。
  - standalone 专属文件（tsconfig.json / package.json / vendor/*.d.ts / scripts/build.mjs）
    应放 overlay/standalone/ 或 overlay/<kind>-standalone/，builtin 形态自然不生成。
  - 模板没有分支语法：不要在 .tpl 里写 if。kind/mode 的内容差异一律靠「把整文件放进对应
    overlay 层」表达；{{kind}}/{{mode}} 只是可替换的文本变量。

四、文件与目录命名
  - 路径里两种占位符形态都支持：{{ident}} 与 __ident__；内容里只支持 {{ident}}。
  - 后缀 .tpl 渲染后去掉；后缀 .raw 表示「内容按字节原样复制、不做替换」（仍渲染路径、
    仍去掉后缀；.raw.tpl 与 .tpl.raw 都接受）。含大量 JSX 双花括号的 tsx 可用 .raw 兜底。
  - 目录名里的 {{packagePath}} 会展开成 dev/everyagent/plugin/fooBar，天然生成多层目录。
    例：java/src/main/java/{{packagePath}}/__className__Plugin.java.tpl
      → src/main/java/dev/everyagent/plugin/fooBar/FooBarPlugin.java
  - 路径里禁止使用 {{package}}（带点会生成单层怪目录），要用 {{packagePath}}。
  - npm 打包会吃掉模板里的 .gitignore，故模板文件名请写 gitignore.tpl（不带前导点）：
    CLI 落盘时会把 gitignore / npmignore / npmrc / editorconfig / gitkeep 自动补回前导点。
  - 需要占位空目录时放一个空的 gitkeep.tpl（渲染成 .gitkeep）。
  - 渲染后路径不得含 ..、绝对路径、Windows 非法字符与控制字符，段尾不得是点或空格。

五、内容占位符（严格识别 {{ident}}，ident = [A-Za-z][A-Za-z0-9_]*）
  {{pluginId}} {{pluginName}} {{description}} {{author}} {{version}} {{kind}} {{mode}}
  {{package}} {{packagePath}} {{className}} {{camelName}} {{entryClass}} {{mainClass}}
  - 未识别的 {{标识符}} → 报错并列出模板相对路径、行号、列号与所在行上下文
    （防止模板漏字段悄悄发布）。
  - {{color:'red'}} 这类非标识符形态不是占位符，按字面保留（JSX 双花括号安全）。
  - 需要字面花括号时写转义：反斜杠 + 双花括号（输出时去掉反斜杠）。

六、文件作用一句话（--dry-run 树右侧的说明）
  - CLI 内置一份按文件名/路径前缀匹配的作用表（lib/tree.mjs）；模板若要覆盖它，
    在文件前 10 行内、用注释写一行 ea: 一句话作用（必须是注释前缀 // # ; -- /* <!-- ，裸写会污染产物），例：
        // ea: 插件入口类，activate() 里注册扩展点

七、写盘约定
  - 内容一律 UTF-8 无 BOM（读模板时剥 BOM）、换行一律 LF（读时 CRLF→LF，写时按 LF 落盘）。
```

`ea:` 标记补充约定（与本仓库其余 `ea:` 用法一致）：**plugin.json 这类 JSON 产物内禁用 `ea:`**（裸文本会污染 JSON）；pom.xml 可用 XML 注释承载 `ea:`；Markdown 用 `<!-- ea: … -->`，.gitignore 用 `# ea: …`。

## 模板层现状图

```text
templates/
├── common/                                    L1 所有组合共有
│   ├── README.md.tpl                          每个插件根的 README（构建/验证/生效/打包，按 kind×mode 索引）
│   └── gitignore.tpl                          渲染成 .gitignore（target/、web 产物、node_modules/、dist/）
├── java/                                      L2 kind ∈ {java, full}
│   └── src/
│       ├── main/java/{{packagePath}}/{{entryClass}}.java.tpl      入口类（15 个注册方法目录 + 注释版 ToolProvider 示例）
│       ├── main/resources/gitkeep.tpl                             空资源目录占位 → .gitkeep
│       └── test/java/{{packagePath}}/{{entryClass}}SmokeTest.java.tpl  JUnit5 冒烟测试（仅依赖 plugin-api）
├── web/                                       L2 kind ∈ {web, full}
│   ├── plugin.json.tpl                        仅 webMain 的清单
│   └── web/index.ts.tpl                       前端入口（PluginModule + 侧边栏项示例）
└── overlay/
    ├── java/plugin.json.tpl                   L3 含 main 的清单
    ├── full/plugin.json.tpl                   L3 同时含 main + webMain（覆盖 web/ 基础层那份）
    ├── java-builtin/pom.xml.tpl               L5 parent=every-agent-parent（relativePath ../../pom.xml）
    ├── java-standalone/pom.xml.tpl            L5 无 parent，自带属性与显式版本
    ├── web-standalone/                        L5 npm 工具链 4 件
    │   ├── package.json.tpl
    │   ├── tsconfig.json.tpl
    │   ├── scripts/build.mjs.tpl              esbuild 选项与宿主 build-plugins.mjs 逐项一致
    │   └── vendor/@everyagent/plugin-api/index.d.ts.raw   类型副本（.raw 逐字节复制）
    ├── full-builtin/pom.xml.tpl               L5 = java-builtin 版逐份拷贝
    └── full-standalone/                       L5 = java-standalone 版 pom + web-standalone 版 npm 4 件拷贝
        ├── pom.xml.tpl
        ├── package.json.tpl / tsconfig.json.tpl / scripts/build.mjs.tpl
        └── vendor/@everyagent/plugin-api/index.d.ts.raw
```

pom 的四份（java-builtin / java-standalone / full-builtin / full-standalone）与 npm 的两套（web-standalone / full-standalone）是**逐字节拷贝**关系，文件内注释互相指向；改公共段落时必须同步改所有拷贝。

## `.eap` 打包（`pack` 子命令）

```powershell
node create-everyagent-plugin pack <pluginDir> [-o <输出目录>] [--verify]
node create-everyagent-plugin pack --help      # 子命令自己的帮助
```

| 参数 | 说明 |
| --- | --- |
| `pluginDir` | 插件工程根目录（含 `plugin.json`），唯一位置参数 |
| `-o, --output <目录>` | `.eap` 输出目录，缺省当前目录；目录不存在自动创建，同名文件直接覆盖 |
| `--verify` | 打包后用 CLI 自带的 zip 读侧把包解回内存自检（见下），不调用任何外部解压工具 |
| `-h, --help` | 显示 pack 帮助 |

产物固定命名 `<id>-<version>.eap`（id/version 取自 `plugin.json`），并**同时产出校验和旁文件 `<id>-<version>.eap.sha256`**（known-issues #21 最小方案）：内容为一行 sha256sum 兼容格式「64 位小写十六进制摘要 + 两个空格 + `.eap` 文件名 + 换行」，对 `.eap` 全文件计算。分发时两件一起带走，接收侧核对：

```powershell
# Windows
Get-FileHash .\my-tool-0.1.0.eap -Algorithm SHA256        # 与旁文件第一列比对
# Linux / macOS
sha256sum -c my-tool-0.1.0.eap.sha256                     # 旁文件与 .eap 同目录时直接校验
```

**zip 顶层目录名 = pluginId**，内部布局：

```text
<pluginId>/
├── plugin.json                    恒有
├── lib/                           清单含 main（java/full 形态）：target/ 下非 sources/javadoc 的 *.jar 全收
│   └── <任意名>.jar
└── web/                           清单含 webMain（web/full 形态）：web/ 递归收入 index.js / index.css / *.map
    └── index.js
```

打包形态由 `plugin.json` 判定：有 `main` 就必须有 `target/*.jar`（纯 sources/javadoc 不算），有 `webMain` 就必须有 `web/index.js`（宿主前端硬编码加载这个路径，`webMain` 的值不被消费）。纯 web 插件没有 `target/` 属正常——只打 `plugin.json` + `web/`。

失败与退出码（沿用主命令 EXIT 体系，三类各自区分）：

| 退出码 | 场景 |
| --- | --- |
| 1 | 用法错误：缺 `pluginDir`、多个位置参数、未知 flag |
| 2 | 不是插件工程（目录不存在 / 缺 `plugin.json` / JSON 解析失败 / 缺 id、version）；缺构建产物（缺 jar、缺 `web/index.js`，错误信息附对应构建命令 `mvn -f … package` / `npm run build:plugins`） |
| 4 | 写盘失败（输出目录创建 / `.eap` 或 `.eap.sha256` 写入）或 `--verify` 自检不过 |

zip 由 `node:zlib` 手写（零 npm 依赖）：local file header + central directory + EOCD 三段齐全；条目 DEFLATE 压缩（`deflateRawSync` level 6，压缩无收益时回退 store）；文件名 UTF-8 且置通用标志 bit 11（中文路径安全）；CRC32 查表法自实现；条目路径一律 `/` 分隔、不写目录条目（worker 按条目流解包，不需要）。**不做 zip64**：单条目/整包须 < 4 GiB、条目数 < 65536，超出直接报错（插件包是 KB 级，碰不到）。

`--verify` 的自证链路：EOCD 定位 → 遍历 central directory → 逐条 `inflateRawSync` 解回内存 → CRC32 与解压大小逐条核对 → 打印条目树 → 确认顶层目录 = pluginId、确认 `<id>/plugin.json` 可解析且 id/version 与源清单一致 → 重读盘上 `.eap` 复算 sha256 与 `.eap.sha256` 旁文件核对。全程只用 `node:zlib`、`node:crypto` 与自己的读侧代码。

### 安装与 worker 端约定

`.eap` 就是 zip。worker 侧 `plugin.install{"path":…}` 要求 `path` 是 **worker 机器上**的文件路径，解包逻辑见 `every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginRpcMethods.java` 的 `extractEap`：

- 取「第一个带 `/` 的条目」的首段当 pluginId，条目原样解到 plugins 目录（默认 `~/.everyagent/plugins/`）；每条路径先 `normalize` 再校验仍在 plugins 根内（zip-slip 防御）。
- 若**所有条目都不带 `/`**（平铺 zip）：pluginId 回退成 zip 文件名去掉 `.eap`，且文件被直接摊进 plugins 根——`ExternalPluginScanner` 只扫一级子目录，这种包等于装了个寂寞。`pack` 产出的条目恒为 `<id>/…` 前缀，不会出现这种形态。
- 若手工 zip 的**顶层目录名 ≠ plugin.json 的 id**：解出来的目录名 = 那个顶层目录名，但清单 id 仍以 `plugin.json` 为准 ⇒ 目录与 id 不一致，之后 `plugin.uninstall{"pluginId"}` 按清单 id 找目录会返回 NOT_FOUND。所以顶层目录必须 = id，`pack` 已保证。
- 外部 java 插件的 jar 约定 `<id>/lib/*.jar`（`ExternalPluginScanner` 注释 + `PluginLoader` 加载约定），`pack` 的 `lib/` 布局与之逐字对齐。

安装/启停都没有热重载，**重启 worker 后生效**。另注：`plugin.install/uninstall` 目前在前端零调用点（插件管理界面只用 list/enable/disable），实际安装可经 RPC 直连，或手工把解包后的目录放进 `~/.everyagent/plugins/<id>/`（jar 在 `lib/`、bundle 在 `web/index.js`、`plugin.json` 在顶层）。

## 零依赖硬约束

本 CLI **不得引入任何 npm 依赖**（`package.json` 的 `dependencies` 恒为空）：

- 交互问答走 `node:readline/promises`；
- zip（`.eap`）走 `node:zlib` 手写条目，不引 jszip / archiver；校验和旁文件走 `node:crypto` 的 sha256；
- 其余全部使用 `node:fs` / `node:path` / `node:process` / `node:url` 等内置模块。

原因：目标环境可能离线，且部分 Windows 沙箱的 npm cache 有 EPERM 风险——脚手架本身绝不能因为装依赖而失败。

## 开发自测

```powershell
# 语法检查（无依赖，无需 npm install）
node --check index.mjs; node --check lib/render.mjs   # …或 npm.cmd run check 一次查全部

# 用独立模板根调试（不必动包内 templates/）
$env:EA_PLUGIN_TEMPLATES = "<你的模板根绝对路径>"
node create-everyagent-plugin .\fx --id fx --kind full --mode standalone --dry-run
Remove-Item Env:EA_PLUGIN_TEMPLATES

# 六组合树逐一可打印
kind=java,web,full × mode=builtin,standalone → 各跑一次 --dry-run 对照「六组合产物清单」表
```

`--dry-run` 不写盘，树里每个文件右侧有一句话作用（来自模板 `ea:` 标记或 `lib/tree.mjs` 内置表），覆盖关系标注「(覆盖 <来源模板>)」。

## 相关文档

- 插件开发文档站：`docs/plugin-guide/`（从仓库根进入）
- 模板契约实现：`lib/render.mjs`；命名推导：`lib/naming.mjs`；dry-run 树：`lib/tree.mjs`
