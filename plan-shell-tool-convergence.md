# Shell 工具与命令执行器收敛实施计划

## 目标
按 `docs/design-shell-tool-convergence.md` 完成落地：plugin-api 新增 shell 包收敛公共契约，rg 归属下放各沙箱插件，shell 工具注册权交还后端。

## 步骤

- [x] 步骤 1：plugin-api 新增 `dev.everyagent.plugin.api.shell` 包
    - 状态：已完成
    - agent：`sub_o3y4n`（已提交 d633dd3；实现偏差：因注解需编译期常量，ShellCommand 拆为 PowerShellCommand/BashCommand 两个 record，语义等价）
    - agent：-
    - 依赖：无
    - 验收标准：`ShellExecutor` / `ShellTool` / `ExecResults` 三个类创建完成；`ExecResults` 含 format(ExecResult)/带标志重载/truncate/POWERSHELL_PREFIX/MAX_OUTPUT_CHARS；`ShellTool` 含 powershell(exec)/bash(exec) 工厂、默认基线描述、description/appendDescription 覆盖接口、callback()；`every-agent-plugin-api` 模块 `mvn compile` 通过。
- [x] 步骤 2：worker 核心改造
    - 状态：已完成
    - agent：`sub_o3y4o`（已提交 18fa148；决策：CommandExecutor 保留 rgBinDir 与 PATH 注入供 direct 用，DirectShellToolProvider 自建带 rg 的执行器；ToolContextImpl.shellExecutor 装不带 rg 的执行器服务外部插件）
    - agent：-
    - 依赖：依赖步骤 1
    - 验收标准：①`ToolContext` 增加 `shellExecutor()` default 方法；②`ToolContextImpl` 装配 `CommandExecutor::execute`；③`CommandExecutor` 改用 `ExecResults`（删重复 format/truncate/常量）、删 rg 注入逻辑；④删 `PowerShellTool`/`BashTool`/`PowerShellToolProvider`/`BashToolProvider`；⑤新增 `DirectShellToolProvider`（appliesTo 仅 sandbox==null，按 OS 选 bash/powershell，追加 rg/UTF-8 提示，rg 由 worker `RipgrepBinary` 注入）；⑥`BuiltInToolProviders` 相应调整；⑦worker 模块 `mvn compile` 通过。
- [x] 步骤 3：codex 插件改造
    - 状态：已完成
    - agent：`sub_o3y4p`（已提交 4a606b2；新增 CodexRg 解析 <pluginDir>/bin/rg.exe，bin/rg.exe 已入库）
    - agent：-
    - 依赖：依赖步骤 1（shell 包）；与步骤 2 可并行（不同模块）
    - 验收标准：①`CodexCommandExecutor` 改用 `ExecResults`；②`CodexBashToolProvider` 改用 `ShellTool`（删内嵌 `CodexPowerShellTool`）；③rg 改由插件自带（`<pluginDir>/bin/rg.exe`），activate 时检查 PATH 是否已含 rg、未含则注入（经 CodexCommandExecutor 的 rgBinary 参数传入插件自己的 rg 路径，不再用 `ctx.rgBinary()`）；④模块 `mvn compile` 通过。
- [x] 步骤 4：wsl-ubuntu 插件改造
    - 状态：已完成
    - agent：`sub_o3y4q`（已提交 3b48014）
    - agent：-
    - 依赖：依赖步骤 1；与步骤 2/3 可并行
    - 验收标准：①`WslUbuntuCommandExecutor` 改用 `ExecResults`；②`WslUbuntuBashToolProvider` 改用 `ShellTool`（删内嵌 `WslUbuntuBashTool`），追加描述含 rg 提示（发行版自带 rg）；③模块 `mvn compile` 通过。
- [x] 步骤 5：windows-mic 插件新增工具提供者
    - 状态：已完成
    - agent：`sub_o3y4r`（已提交 4da2eef + f03cda3；为支持插件自带 rg 注入，ToolContext 增加了 shellExecutor(extraBinDir) 重载）
    - 依赖：依赖步骤 2（需要 ctx.shellExecutor()）
    - 验收标准：①新增 `WindowsMicShellToolProvider`（appliesTo=windows-mic，用 `ctx.shellExecutor()` 注册 `ShellTool.powershell(...)`，追加 rg/UTF-8 提示）；②`WindowsMicSandboxPlugin.activate` 注册该 provider 并做 rg 检查/注入（`<pluginDir>/bin/rg.exe`）；③模块 `mvn compile` 通过。
- [x] 步骤 6：全量构建与测试修复
    - 状态：已完成
    - agent：主 agent
    - 结果：①plugin-api + worker 编译通过；②worker 测试失败均为预存（基线 8c8fa8d 同样 12 个 RoundIndexStore/PermissionGate 失败 + E2E 环境性超时/git UNKNOWN_METHOD），本次改动未引入新失败；③三个插件测试全绿（wsl-ubuntu 6/6、codex 166 pass、mic 无测试编译通过）。
- [x] 步骤 7：提交
    - 状态：已完成
    - agent：主 agent
    - 结果：6 个 feat 提交（d633dd3 → f03cda3）+ 文档提交 8ef7140。每个模块独立提交，一次一事。

## 备注
- 步骤 1 是全部后续步骤的基础（shell 包 API），必须先完成。
- 步骤 3/4/5 分别改三个独立插件模块，互不读写同一文件，可并行；步骤 2 改 worker 模块，与 3/4 并行无冲突，但步骤 5 依赖步骤 2 的 `shellExecutor()`。
- rg 二进制实体：`runtime/bin/` 已有 rg/rg.exe（不入 git）。codex/mic 插件代码按 `<pluginDir>/bin/rg.exe` 解析；二进制放入插件目录属打包事项，本次代码实现解析+注入逻辑，并在开发环境把 runtime/bin 的 rg.exe 复制到两插件的 bin/ 目录（确认 .gitignore 不拦截二进制）。
- 回退方案：每步独立提交前均可 `git checkout` 回退；模块间通过 plugin-api 新包解耦，单插件失败不影响其他模块。
