# ripgrep-tool

schema 化 ripgrep（`rg`）搜索工具插件：向模型提供独立 `rg` 工具与自带 rg 二进制，并对 shell 里裸 rg 的空结果追加引导 hint。四个沙箱后端（codex / mic / wsl-ubuntu / worker DIRECT）已彻底剥离 rg（不自带二进制、不注入 PATH、描述不提 rg），rg 能力整体收敛到本插件。

| 项 | 值 |
| --- | --- |
| 插件 id | `ripgrep-tool` |
| 形态 kind | `java`（仅后端） |
| 工程形态 mode | `builtin` |
| 版本 / 作者 | 0.1.0 / everyagent |
| 后端入口类 | `dev.everyagent.plugin.ripgrepTool.RipgrepToolPlugin` |

## 能力（activate() 的两个注册）

1. **ToolProvider（`RgToolProvider` + `RgTools`）——schema 化 `rg` 工具**：
   - 参数：`pattern` 必填；`path` 省略即默认搜索**工作区根**（调用侧 `normalizePath` 归一化为工作区根**绝对路径**，argv 恒以显式 path 终结）；`glob` 文件名过滤 / `case_insensitive` 忽略大小写 / `files_with_matches` 只列含匹配的文件 / `max_results` 每文件匹配行上限。
   - 执行链：拼 argv（纯函数）→ `CommandGate.authorize`（path 引用工作区外路径走授权链，拒绝抛异常回灌模型）→ `NativeExec.spawnNative`（argv 直传、30s 超时、每流输出上限内建，不自建 ProcessBuilder）。
   - 退出码语义：0=有匹配；1=无匹配，结果显式标注「[rg] 无匹配」（区分「没搜到」与「执行坏了」）；2=错误，输出 stderr。
   - **appliesTo 排除 wsl-ubuntu 后端**：该后端下任务工作区挂在 Linux 侧路径语义，宿主直跑的 rg.exe 既读不到发行版内路径、也看不见发行版内 rg；wsl 语境的内容搜索归 wsl 后端自己的 bash 工具。三档解析皆无 rg 时**不注册本工具**（描述承诺了 rg 能力，注册即谎报可用）。
2. **ToolExecutionInterceptor（`RgHintInterceptor`，order=0）——结果侧兜底**：模型若仍习惯在 shell 里敲裸 `rg pattern`，按「powershell/bash 工具 + 结果文本恰为 `[exit code: 1]` + 命令串含独立 rg token」三条件（逐条保守：stdout 有内容 / exit 2 正则错误均不命中）识别，在工具结果尾部追加一行 hint 引导改用 rg 工具；显式带路径的真·无匹配同样会命中，hint 措辞条件化，仅信息性、无害。

## rg 二进制三档解析（`RgResolver`）

activate() 里 `RgResolver.resolve(pluginDir, runtimeDir)` 一次解析存字段：

1. 插件根 `bin/rg.exe`（本插件自带，git 跟踪；desktop 构建链 `copy-plugins.mjs` 整体 staging 插件 `bin/`）；
2. 程序根 `runtime/bin/rg.exe`（`WorkerConfig.resolveRuntimeDir()`——desktop 打包态的实际兜底位，构建链漏搬插件 `bin/` 时仍有 rg；与 worker 核心 `RipgrepBinary` 同一位置）；
3. 系统 PATH（`where rg` 语义，遍历各目录）。

与旧 `CodexRg` 的关键差异：命中档**始终返回绝对路径**（系统 PATH 档也一样）——`rg` 工具以绝对路径委托执行，全程不做 PATH 注入；三档皆无 → 判不可用（WARN，不阻断启动），不注册 rg 工具。

## 背景：rg 的 stdin 语义陷阱（path 省略为何显式归一化）

ripgrep 的文档化行为：**无 path 参数且 stdin 可读（管道 / 普通文件）时搜索 stdin 而非当前目录**。本环境 shell 工具经沙箱执行，stdin 无输入可用：在 **stdin 为管道**的后端（codex 的「关闭的管道」）→ shell 里裸 `rg pattern` 把该空管道当输入源、零匹配 exit 1，被模型误读为「无结果」；在 **stdin 为字符设备**的后端（DIRECT 的 `NUL`、wsl 的 `/dev/null`）→ rg 回退搜当前目录，**无此问题**（口径订正见 `docs/ARCHITECTURE.md` §7.10「命令 stdin 契约」）。训练语料里的 rg 几乎全跑在 tty 环境（= 无 path 就搜当前目录），模型先验稳固，工具描述压不过训练先验（实测描述带「必须显式带上路径参数」仍漏传路径），故在**调用契约层**根治：schema 的 path 省略 = 调用侧显式携带工作区根绝对路径，argv 恒以 path 参数终结、与 stdin 完全无关；`RgHintInterceptor` 是结果侧最后防线。完整留档见仓库 `docs/ARCHITECTURE.md` §7.10「rg 的 stdin 语义陷阱」与 §7.17「程序附属文件」。

## 构建与生效

```powershell
# 仓库根执行（插件不进根 reactor，必须 -f 单独构建；clean 才能清掉旧版本 jar，防类遮蔽）
mvn -f every-agent-plugins/ripgrep-tool/pom.xml clean package
# 首次构建若报找不到 dev.everyagent:every-agent-plugin-parent，先在仓库根执行一次：
mvn -pl every-agent-plugin-parent,every-agent-plugin-api -am install -DskipTests
```

任何改动无热重载，重启 worker 生效。验证加载：worker 日志出现「[ripgrep-tool] 已激活（pluginDir=…，rg=…）」，或前端调用 `plugin.list` RPC。打包分发：`node create-everyagent-plugin pack every-agent-plugins/ripgrep-tool`。

## 更多文档

插件开发全量文档见仓库 `docs/plugin-guide/`；rg 归属与 stdin 陷阱的架构留档见 `docs/ARCHITECTURE.md` §7.10 / §7.17。

