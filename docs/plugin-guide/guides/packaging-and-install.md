---
title: 打包与安装
nav_order: 14
parent: guides
has_children: false
---

**一句话定位**：本篇讲怎么把写好的插件**变成可分发的产物**（`pack` 命令与 `.eap` 包内布局），以及怎么把它**装到目标 worker 上**——`plugin.*` 六个 RPC 的真实现状、手工安装目录布局、卸载/启停与升级口径。构建产物本身怎么来（jar / bundle）看[构建与运行](build-and-run.md)，这里只管「打包 → 分发 → 装机 → 生效」这一段。

## 1. 为什么要打包

插件有两条分发路径，差异一句话：**内置插件**随仓库源码分发（`BuiltInPluginScanner` 扫 worker 启动目录下的 `every-agent-plugins/`，改代码即改分发），**外部插件**装在独立的插件目录（`ExternalPluginScanner` 扫 `worker.plugins-dir`，默认 `~/.everyagent/plugins/`）——`.eap` 就是为后一条路准备的搬运格式。

`.eap` 的定位要认清：**它只是一个 zip 文件约定**，不是包管理体系——

- **没有包仓库**：不存在 `eap install <name>` 这种按名拉取；你得自己把文件送到目标机器。
- **没有版本解析**：worker 不读 `.eap` 文件名，也没有「装 1.1 自动替换 1.0」的逻辑；版本只存在于两处——产物文件名 `<id>-<version>.eap` 与 `plugin.json` 的 `version` 字段（[plugin.json 字段参考](../plugin-manifest.md)）。
- **没有依赖解析**：`.eap` 不声明也不解决依赖（见 §5 的冲突提示）。
- **没有签名，只有校验和旁文件**：`pack` 会顺手产出 `<id>-<version>.eap.sha256`（§2，sha256sum 兼容格式），但它只服务于分发时的人工核对——worker 安装侧既不验签也不消费它（[已知问题](../reference/known-issues.md) #21）。

另外记住现状：`@everyagent/plugin-api` **双侧未发布**（npm 与 Maven Central 均 404，详见[已知问题](../reference/known-issues.md)）——仓库外开发的插件工程必须自带类型副本 / 本地 `mvn install` 的 API jar，`.eap` 分发的是**你的插件产物**，不含 API 包。

## 2. `pack` 命令详解

```powershell
node create-everyagent-plugin pack <pluginDir> [-o <输出目录>] [--verify]
node create-everyagent-plugin pack --help      # 子命令自己的帮助
```

### 2.1 参数表

| 参数 | 说明 |
| --- | --- |
| `pluginDir` | 插件工程根目录（含 `plugin.json`），唯一位置参数 |
| `-o, --output <目录>` | `.eap` 输出目录，缺省当前目录；目录不存在自动创建，同名文件直接覆盖 |
| `--verify` | 打包后用 CLI 自带的 zip 读侧把包解回内存自检（见 §2.4），不调用任何外部解压工具 |
| `-h, --help` | 显示 pack 帮助 |

产物固定命名 `<id>-<version>.eap`，id / version 取自 `plugin.json`（`create-everyagent-plugin/lib/zip.mjs` 的 `packPlugin`：`path.join(absOutDir, `${manifest.id}-${manifest.version}.eap`)`）。

**同时产出校验和旁文件 `<id>-<version>.eap.sha256`**（known-issues #21 的最小方案，`pack` 自动生成）：内容为一行 **sha256sum 兼容格式**——`64 位小写十六进制摘要` + **两个空格** + `.eap` 文件名 + 换行，摘要对 `.eap` **全文件**计算。zip 自带的 CRC32 只防传输意外损坏，不防篡改；分发 `.eap` 时把旁文件一起带走，安装前人工核对：

```powershell
# Windows（Get-FileHash 输出全大写，比对时忽略大小写）
Get-FileHash .\my-tool-0.1.0.eap -Algorithm SHA256
# Linux / macOS（旁文件与 .eap 同目录时直接校验）
sha256sum -c my-tool-0.1.0.eap.sha256
```

注意：worker 侧 `plugin.install` / 手工解压**目前都不消费**该旁文件（安装侧自动校验见[已知问题](../reference/known-issues.md) #21），它服务于「自行分发、人工核对」的场景。

### 2.2 包内布局

**zip 顶层目录名 = pluginId**，内部布局（与 worker 端解包约定逐字对齐，见 §3.1 的 `extractEap`）：

```text
<pluginId>/
├── plugin.json                    恒有
├── lib/                           清单含 main（java/full 形态）：target/ 下非 sources/javadoc 的 *.jar 全收
│   └── <任意名>.jar
└── web/                           清单含 webMain（web/full 形态）：web/ 递归收入 index.js / index.css / *.map
    └── index.js
```

外部 java 插件的 jar 约定就是 `<id>/lib/*.jar`（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/scanner/ExternalPluginScanner.java:13-14` 类注释「jar 产物约定放在各插件目录的 `lib/` 下」，加载端为 `PluginLoader`），`pack` 的 `lib/` 布局与之逐字对齐。

**包里只有产物，没有源码**：`pack` 按上面的白名单收集——`plugin.json`、`target/` 下的 jar、`web/` 下的 bundle；`src/`、`pom.xml`、`web/index.ts`、`node_modules/`、`tsconfig.json` 一概不进包。所以 `.eap` 不能拿来重建工程，只能装机；要分发源码请走 git 仓库。

### 2.3 形态判定规则

打包形态**由 `plugin.json` 判定**，不看目录里实际有什么（`create-everyagent-plugin/lib/zip.mjs` 的 `collectFiles`）：

| 清单字段 | 判定 | 缺产物时的报错（原文） |
| --- | --- | --- |
| 有 `main` | 收 `target/` 下全部非 sources/javadoc 的 `*.jar` 进 `lib/` | `清单声明了 main（java/full 形态），但 target/ 下没有可打包的 jar（sources/javadoc jar 不算）：<targetDir>`，提示先跑 `mvn -f "<pom.xml 路径>" package`（standalone 工程还提示先在宿主仓库根执行 `mvn -pl every-agent-plugin-api -am install -DskipTests`） |
| 有 `webMain` | 必须存在 `web/index.js`；`web/` 递归收 `index.js` / `index.css` / `*.map`（跳过 `node_modules`） | `清单声明了 webMain（web/full 形态），但 web/index.js 不存在：<web/index.js 路径>`，提示先跑 `npm run build:plugins`（PowerShell 被执行策略拦截时用 `npm.cmd`）或 standalone 的 `node scripts/build.mjs` |
| 两者都无 | 直接拒绝 | `plugin.json 既无 main 也无 webMain，无法判定打包形态` |

注意两点：宿主前端**硬编码加载 `web/index.js`**（`plugin.json` 里 `webMain` 的值不被消费），所以 web/full 形态必须先出 bundle；纯 web 插件没有 `target/` 属正常——只打 `plugin.json` + `web/`。

**退出码**（沿用主命令 EXIT 体系，`create-everyagent-plugin/lib/zip.mjs` 顶部注释）：

| 码 | 场景 |
| --- | --- |
| 1 | 用法错误：缺 `pluginDir`、多个位置参数、未知 flag |
| 2 | 不是插件工程（目录不存在 / 缺 `plugin.json` / JSON 解析失败 / 缺 id、version）；缺构建产物（缺 jar、缺 `web/index.js`） |
| 4 | 写盘失败（输出目录创建 / `.eap` 写入）或 `--verify` 自检不过 |

zip 由 `node:zlib` 手写（零 npm 依赖）：local file header + central directory + EOCD 三段齐全；条目 DEFLATE 压缩（压缩无收益回退 store）；文件名 UTF-8 且置通用标志 bit 11；条目路径一律 `/` 分隔、**不写目录条目**（worker 按条目流解包，不需要）；不做 zip64（单条目/整包 < 4 GiB、条目数 < 65536，插件包是 KB 级碰不到）。

### 2.4 `--verify` 自检六项

`--verify` 用 CLI 自带的读侧把刚写的 `.eap` 解回内存自证，打印六项结果（实现于 `create-everyagent-plugin/lib/zip.mjs` 的 `verifyZipFile`）：

1. **EOCD 与中央目录可定位**，条目数与收集清单一致；
2. **每条目 CRC32 与解压大小核对一致**（`readZip` 内置，对不上直接抛错）；
3. **条目路径安全**（`/` 分隔、无 `..`、无绝对路径、无空段）；
4. **顶层目录全部 = `<id>`**（与 `plugin.json` 的 id 一致）；
5. **`<id>/plugin.json` 可解析**且 id / version 与源清单一致；
6. **`.eap.sha256` 旁文件与盘上 `.eap` 复算摘要一致**（known-issues #21；顺带兜住写盘截断）。

任一项失败即退出码 4（`打包自检失败：.eap 与预期不符（见上方 [失败] 行）`）。发布前跑一次 `pack --verify` 是最便宜的保险。

`pack` 成功后还会打印安装提示（原文）：`安装：把该文件复制到 worker 机器后调用 RPC plugin.install {"path":"<该文件在 worker 机器上的绝对路径>"}，重启 worker 生效。`

## 3. 安装的三条路

先给结论总表（可操作性评价以「今天装一个外部插件」为准）：

| 路线 | 操作 | 前提 | 可操作性结论 |
| --- | --- | --- | --- |
| a. `plugin.install` RPC | 插件管理面板「安装…」选定 `.eap`（面板自动上传并代发 RPC）；或手工往 cmd 频道发一帧 | 面板路线：worker 在线且当前工作区可写；手工路线：能直连 worker 的 RPC 频道，文件已在 worker 本机 | 接口齐备且**前端已接线**（known-issues #19 修复前零调用点）；交互场景用面板，脚本场景用手工 RPC |
| b. 手工解压 / 复制目录 | `.eap` 解到 `~/.everyagent/plugins/<id>/`，或整目录拷贝 | 能访问 worker 机器文件系统 | 无 UI 访问 / 要脚本化时最可靠的一条路，零依赖 |
| c. builtin 路径 | 插件目录进仓库 `every-agent-plugins/`，随仓库分发 | 拿得到目标部署的仓库（发 PR / 自部署） | 适合团队内长期维护；终端用户装不了 |

三条路殊途同归：**最终都是让一个含 `plugin.json` 的目录出现在某个扫描器面前，然后重启 worker**。生效边界没有例外——启停/装卸/换文件全部重启后生效（前端 webMain 插件另需刷新页面）。

### 3.1 路线 a：`plugin.install` RPC（面板已接线）

worker 侧方法表齐备（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginRpcMethods.java:104-128`）：

- 参数：`path` —— **worker 机器上的 `.eap` 文件路径**（`:87`）。不是浏览器机器的路径；worker 跑在远端时文件得先送过去。
- 缺参：`BAD_PARAMS` `缺少参数 path`（`:89`）；文件不存在：`NOT_FOUND` `插件文件不存在: <path>`（`:94`）；解包 IO 异常：`INTERNAL` `安装失败: <原因>`（`:102`）。
- 成功返回：`{ "installed": true, "pluginId": "<id>", "message": "插件已安装，重启 worker 后生效" }`（`:105-109`，文案逐字）。

解包逻辑 `extractEap`（`PluginRpcMethods.java:327-350`）的三条判定，与 CLI README 逐字对齐：

1. **顶层目录判定**：取「第一个带 `/` 的条目」的首段当 pluginId，条目原样解到 plugins 根（默认 `~/.everyagent/plugins/`，`PluginRegistry.getPluginsRoot()` → `WorkerProperties.resolvePluginsDir()`，`every-agent-worker/src/main/java/dev/everyagent/worker/config/WorkerProperties.java:247-251`；home 默认 `<user.home>/.everyagent`，`:213-215`）。
2. **zip-slip 防御**：每条路径先 `normalize` 再校验仍在 plugins 根内，越界条目直接跳过（`:226-229`）。
3. **平铺回退**：若所有条目都不带 `/`（平铺 zip），pluginId 回退成 zip 文件名去掉 `.eap`，文件被直接摊进 plugins 根——`ExternalPluginScanner` 只扫**一级子目录**（`ExternalPluginScanner.java:44-48`），这种包等于装了个寂寞。`pack` 产出的条目恒为 `<id>/…` 前缀，不会出现这种形态。

另外 `__MACOSX` 条目与 `.DS_Store` 会被跳过（`:218`），同路径文件覆盖写（`REPLACE_EXISTING`，`:231`）——即**重复 install 同一个包 = 覆盖安装**。

**产品化入口**：插件管理面板（侧边栏「扩展」）顶栏的「安装…」按钮已对接该 RPC（known-issues #19 修复前面板只调 `plugin.list` / `plugin.enable` / `plugin.disable`，`plugin.install` 前端零调用点）。因为 `{path}` 收的是 **worker 机器本地路径**，浏览器选中的文件先经 `fs.write` 以 base64 上传到当前工作区的暂存目录 `.everyagent/plugin-install/`（fs.* 按工作区 jailed，worker 侧自动建父目录），再用暂存文件的机器绝对路径调 `plugin.install` 解包，最后尽力清理暂存文件并刷新列表。边界要知道三条：上限 32 MB（base64 后约 43 MB wire 载荷）；worker 跑在远端时装到的是 **worker 机器**的插件目录（设计意图如此）；新插件**重启 worker 后**才出现在 `plugin.list`（面板成功提示即 RPC 原文「插件已安装，重启 worker 后生效」）。脚本/调试场景仍可直接手工发 RPC：

调用示例（wire 帧结构，信封定义见 `every-agent-contract/src/main/java/dev/everyagent/contract/rpc/Rpc.java:35` 的 `RpcRequest(reqId, method, params)`；worker 在 cmd 频道上只认 `event = "rpc"`，`every-agent-worker/src/main/java/dev/everyagent/worker/rpc/RpcDispatcher.java:70-77`）：

```jsonc
// 发布到 worker 的 cmd 频道（频道名构造见 every-agent-plugin-api/.../event/Channels.java:16-18）
// channel: "u.<ownerKey>.worker.<workerId>.cmd", event: "rpc"
{
  "reqId": "req-install-1",
  "method": "plugin.install",
  "params": { "path": "C:/Downloads/my-tool-0.1.0.eap" }   // worker 机器上的路径
}

// 应答广播到 evt 频道（u.<ownerKey>.worker.<workerId>.evt），event: "rpc.ok"
// Rpc.wireOk → { "reqId": "req-install-1", "result": { ... } }
{ "reqId": "req-install-1",
  "result": { "installed": true, "pluginId": "my-tool",
              "message": "插件已安装，重启 worker 后生效" } }
```

浏览器侧不必手拼帧：前端已有 `hubSession.rpcTo(workerId, method, params)` 通道（`every-agent-web/src/plugin/pluginLoader.ts` 即用它调 `plugin.list` / `plugin.webSource`），插件面板正是经 `ctx.sdk.rpc` 以同一姿势代发 `plugin.install` 与 `{ path }`（上传暂存路径见上文）。

### 3.2 路线 b：手工解压（无面板访问/脚本化时最可靠的一条路）

面板安装入口面向交互场景（worker 在线、当前工作区可写）；没有 UI 访问、或要把安装写进脚本时，**把 `.eap` 当 zip 手工解压**仍是最可靠的方式：

```text
~/.everyagent/plugins/            ← worker.plugins-dir，可配置覆盖（WorkerProperties.java:35）
└── my-tool/                      ← 一级子目录，目录名建议 = plugin.json 的 id
    ├── plugin.json               ← 必须在目录根（扫描器唯一判据）
    ├── lib/
    │   └── my-tool-0.1.0.jar
    └── web/
        ├── index.js
        └── index.js.map
```

扫描器的判定极简：`ExternalPluginScanner.scan()` 对插件目录下每个**一级子目录**只查一件事——根下有没有 `plugin.json`（`ExternalPluginScanner.java:44-48`），有就纳入（`source="external"`）。由此两条推论：

- 目录名**不要求**等于 id（登记以 `plugin.json` 为准），但**强烈建议相等**——`plugin.uninstall` 按目录名找（见 §4 的 NOT_FOUND 陷阱）；
- **直接把插件目录复制过去**也可以（跳过 `.eap` 这一步）：开发机上 `every-agent-plugins/my-tool/` 整目录拷到目标机 `~/.everyagent/plugins/my-tool/`，只要 jar 在 `lib/`、bundle 在 `web/index.js`、`plugin.json` 在根。甚至把未打包的源码目录放过去（jar 还在 `target/`）**不行**——外部扫描只认 `lib/` 约定，先 `pack` 再解，或手工摆成 `lib/` 布局。

Windows 下解压一条命令（`.eap` 就是 zip，任何解压工具都行）：

```powershell
# 假设 my-tool-0.1.0.eap 在当前目录；目标 ~\.everyagent\plugins\
Expand-Archive -Path .\my-tool-0.1.0.eap -DestinationPath "$HOME\.everyagent\plugins\" -Force
# 解完确认：~\.everyagent\plugins\my-tool\plugin.json 存在（顶层目录名 = id）
```

> macOS 手工 zip 注意：`extractEap` 会跳过 `__MACOSX` 与 `.DS_Store` 条目（`PluginRpcMethods.java:333`），但 Finder「压缩」产出的顶层目录名是**所选目录名**——务必让目录名 = id 再压缩，否则踩 §4.1 的卸载陷阱。

### 3.3 路线 c：builtin 路径（仓库内开发即分发）

把插件目录放进仓库的 `every-agent-plugins/`，随整个仓库重新分发——这就是所有 26 个内置插件的姿势。要点：

- 目录位置：`worker.builtin-plugins-dir` 配置为空时取**启动目录（user.dir）下**的 `every-agent-plugins/`（`WorkerProperties.java:257-266`）——从哪里启动 worker 决定能不能扫到，详见[构建与运行](build-and-run.md)的 cwd 一节。
- java 插件要求 `target/classes/plugin.json` + `target/` 下的 jar；未构建只会得到 WARN 日志（内置插件未构建），不阻塞启动——文案与排查见[构建与运行](build-and-run.md)与[故障排查](troubleshooting.md)。
- 内置插件**不能 uninstall**（`plugin.uninstall` 删的是 plugins 目录下的目录，内置不在那里），只能 disable。

## 4. 卸载 / 启停

先把七个 `plugin.*` RPC 一张表看清（全部注册于 `PluginRpcMethods.java:47-55`，方法名常量在 `every-agent-worker/src/main/java/dev/everyagent/worker/proto/RpcMethods.java:89-101`）：

| 方法 | 参数 | 返回（result） | 行号 |
| --- | --- | --- | --- |
| `plugin.list` | 无 | `{ plugins: [...], disabledIds: [...] }` | `:58-101` |
| `plugin.install` | `{ path }`（worker 机器上的 .eap 路径） | `{ installed: true, pluginId, message }` | `:104-128` |
| `plugin.uninstall` | `{ pluginId }` | `{ uninstalled: true, pluginId, message }` | `:131-154` |
| `plugin.enable` | `{ pluginId }` | `{ enabled: true, pluginId, message }` | `:157-169` |
| `plugin.disable` | `{ pluginId }` | `{ disabled: true, pluginId, message }` | `:172-184` |
| `plugin.webSource` | `{ pluginId, path }` | `{ pluginId, path, content }` | `:187-214` |
| `plugin.asset` | `{ pluginId, path }` | `{ pluginId, path, mime, contentBase64 }` | `:217-256` |

四个 message 全部以「重启 worker 后生效」收尾——这不是免责声明，是当前架构的事实（无热重载；`deactivate` 仅在 worker 优雅关闭时调用，运行期不触发）。

### 4.1 `plugin.uninstall`

参数 `{ "pluginId": "<id>" }`（`PluginRpcMethods.java:131-154`）：校验 `<pluginsRoot>/<pluginId>` 是目录后递归删除（`deleteRecursive`，`:352-361`）。

- 缺参：`BAD_PARAMS` `缺少参数 pluginId`（`:116`）；
- 目录不存在：`NOT_FOUND` `插件目录不存在: <id>`（`:122`）；
- 成功：`{ "uninstalled": true, "pluginId": "<id>", "message": "插件已卸载，重启 worker 后生效" }`（`:131-135`，文案逐字）。

**顶层目录 ≠ 清单 id 的 NOT_FOUND 陷阱**：uninstall 拿 `pluginId` 去 `pluginsRoot.resolve(pluginId)` 找**目录**（`:120`）。如果你用手工 zip 打的包顶层目录名 ≠ `plugin.json` 的 id（extractEap 解出来的目录名 = 顶层目录名，但登记的 id 以 `plugin.json` 为准），之后按 id 卸载就会 `插件目录不存在`。**`pack` 已保证顶层 = id**，自打 zip 时务必对齐。

### 4.2 `plugin.enable` / `plugin.disable`

参数同为 `{ "pluginId": "<id>" }`（`PluginRpcMethods.java:157-184`）。两者都经 `PluginRegistry` 委托 `PluginStateStore` **改内存 + 立即落盘**到 `<pluginsDir>/.disabled-plugins`（默认 `~/.everyagent/plugins/.disabled-plugins`；每行一个插件 id，支持 `#` 注释；`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/registry/PluginStateStore.java:38,89-98`）。成功文案逐字：

- enable：`{ "enabled": true, "pluginId": "<id>", "message": "插件已启用，重启 worker 后生效" }`（`:146-150`）
- disable：`{ "disabled": true, "pluginId": "<id>", "message": "插件已禁用，重启 worker 后生效" }`（`:161-165`）

**重启生效的边界**（`PluginStateStore.java` 类注释自证）：插件系统没有运行期停用——`deactivate` 仅在 worker 优雅关闭时调用，已激活插件的贡献留在当前进程的注册表里；名单变更对**下一次 worker 启动**完全生效——加载期被禁用的插件核心不调 `activate()`，一个贡献都不会注册。`plugin.list` 的 `active` 字段即时反映名单（`PluginRpcMethods.java:70`：`active = !isDisabled(id)`），但那是「名单状态」，不是「当前进程里贡献已被摘除」。

`.disabled-plugins` 长这样（每行一个 id，`#` 开头是注释，落盘时按字典序排序——`PluginStateStore.java:89-98`）：

```text
# ~/.everyagent/plugins/.disabled-plugins
my-tool
sandbox-wsl-ubuntu
```

注意这个文件住在**外部插件目录**下，管的却是**全部插件**（含内置）的禁用名单；手工编辑它再重启同样有效（enable/disable RPC 只是「改内存 + 落盘」的便捷通道）。

**`enabled:false` 对外部插件不生效**：plugin.json 的 `enabled` 字段只有内置扫描器读；`ExternalPluginScanner` 内 `enabled|isEnabled` 零命中——外部插件想禁用只能 `.disabled-plugins` 或删目录。详见[plugin.json 字段参考](../plugin-manifest.md)与[持久化与状态](../backend/persistence-and-state.md) §3「禁用双机制」。

### 4.3 `plugin.list` 与 `plugin.webSource`（验证与前端加载用）

- `plugin.list`（`:58-101`）：无参数，返回 `{ plugins: [ { id, name, version, description, author, source, active, status, hasMain, hasWebMain, webMain, icon, repository, license, homepage, categories } ], disabledIds: [...] }`（`active` = 不在禁用名单的旧语义，`status` = 加载期实际状态「已激活 / 激活失败: … / 已禁用(未激活)」，插件面板据此标「加载失败」；`icon/repository/license/homepage/categories` 为展示元数据，供扩展面板 VSCode 风格列表与详情页渲染，见 [plugin.json 字段参考](../plugin-manifest.md) §2.1）——装机后验证就看这里（§6 清单第 6 步）。
- `plugin.webSource`（`:187-214`）：参数 `{ pluginId, path }`，返回 `{ pluginId, path, content }`；`path` 经 `resolvePluginFile`（`:282-307`）normalize 并校验仍在插件目录内（越界报 `NOT_FOUND` `文件不存在或越界: <path>`）；精确路径未命中时按**同目录大小写不敏感**回退匹配一次（Linux 上 `readme.md` 也能读到 `README.md`，供扩展详情页取 README）。前端硬编码用它取 `web/index.js` / `web/index.css`（`pluginLoader.ts:421-429,452`），与安装相关的点只有一条：**解包后的 web 产物路径必须是 `web/index.js`**。
- `plugin.asset`（`:217-256`）：参数 `{ pluginId, path }`，返回 `{ pluginId, path, mime, contentBase64 }`——插件目录内**二进制资源**（扩展图标等）的 base64 出网通道；与 webSource 同款 jail 校验（`resolvePluginFile`，normalize + 不许逃逸插件目录），扩展名限 png/jpg/jpeg/gif/svg/webp/bmp/ico（其余报 `BAD_PARAMS` `不支持的资源类型`），单文件上限 2 MB（超限报 `FRAME_TOO_LARGE`）。图标缺失/越界报 `NOT_FOUND`，前端回退默认扩展图标。

## 5. 升级与版本

**没有升级命令，升级 = 替换文件 + 重启 worker**：

- `plugin.install` 对同 id 重复调用就是覆盖（`REPLACE_EXISTING`，`:231`）；手工路径则直接覆盖 `~/.everyagent/plugins/<id>/` 下的文件。
- ⚠️ **运行中的 worker 可能锁着旧 jar**（Windows 上 `URLClassLoader` 打开过的文件删除/覆盖未必成功；未实测）。稳妥顺序：先 `plugin.disable` 或停 worker → 替换文件 → 重启。

**版本的两套来源**：`.eap` 文件名 `<id>-<version>.eap` 取自 `plugin.json` 的 `version`；jar 文件名（`lib/<artifactId>-<pomVersion>.jar`）取自 pom 的 `<version>`。两者**互不校验、可以不一致**——worker 加载时不做任何版本比较或选择，`version` 字段只是给人看与 `plugin.list` 显示用。发布前自己保证一致（builtin 模板的 pom 版本已与清单同用 `{{version}}` 占位）。

**无依赖解析 ⇒ 同名冲突风险**：`.eap` 不携带依赖元数据，worker 也不做解析。java 插件的隔离靠**每插件一个 `URLClassLoader`**（parent = worker 自身类加载器，插件之间互不可见，同名类冲突、依赖版本互踩在插件之间被物理隔开——见[后端总览](../backend/overview.md) §7.1）。两个例外要知道：① 插件与 worker 之间的同名类走 parent 委派，worker 侧优先；② **同名插件 id** 冲突时内置优先、外部被去重跳过（`PluginLoader` 收集结果按 source 稳定排序）——外部插件别取与内置重名的 id。

## 6. 完整发布清单（checklist）

```text
[ ] 1. plugin.json 字段齐：id 匹配 ^[a-z0-9][a-z0-9-]{1,38}$、version 就位、
        main/webMain 与实际形态一致（字段逐项见 plugin-manifest.md）
[ ] 2. 构建产物齐：java/full 形态 target/ 下有 jar；web/full 形态 web/index.js 已生成
        （构建命令矩阵见 build-and-run.md）
[ ] 3. node create-everyagent-plugin pack <dir> --verify —— 六项自检全绿；.eap 与
        同名 .eap.sha256 旁文件（sha256sum -c 兼容）一起分发
[ ] 4. 目标机安装（三选一）：
        a. 插件管理面板「安装…」选定 .eap（自动上传工作区暂存并代发 plugin.install）；
           或把 .eap 送到 worker 机器 → 手工 RPC plugin.install { "path": "<worker 机器路径>" }
           （安装前用 .eap.sha256 人工核对完整性，worker 侧不自动校验）
        b. 解压 .eap 到 ~/.everyagent/plugins/，确认顶层目录名 = id
        c. builtin：进仓库 every-agent-plugins/ 并随仓库分发（需 mvn package）
[ ] 5. 重启 worker（一切装卸/启停的生效边界）
[ ] 6. 验证：plugin.list 里能找到该 id —— source="external"（外部路线）、active=true、
        hasMain/hasWebMain 与形态一致
[ ] 7. 前端刷新页面：webMain 插件经 plugin.list → webSource 链路加载
        （加载条件 active && hasWebMain && !disabled，见 web/overview-and-loading.md）
```

## 7. 下一步读

- [构建与运行](build-and-run.md)——三形态构建矩阵、cwd 如何决定内置插件被扫到、`build:plugins` 的手工节奏；
- [故障排查](troubleshooting.md)——装了没生效 / 图标不出现 / WARN 文案逐条对号；
- [已知问题与现状偏差](../reference/known-issues.md)——API 包未发布、`.eap` 无签名校验等现状登记。
