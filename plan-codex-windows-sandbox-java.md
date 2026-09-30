# 仿 Codex 实现 Windows 原生沙箱 Java 独立插件

## 目标
下载 GitHub 上 OpenAI Codex 开源的 Windows 原生沙箱（Rust）源码，完全仿照其机制实现 Java 版本，落成独立插件 `every-agent-plugins/sandbox-windows-codex`，向 worker 提供沙箱后端。

## 步骤

- [x] 步骤 1：子 agent 下载 codex 仓库源码并定位 Windows 沙箱相关代码
    - 状态：已完成
    - agent：`sub_o3y43`
    - 依赖：无
    - 验收标准：openai/codex 浅克隆落盘到 `.everyagent/tmp/codex/`（临时目录，不进 git）；定位出 Windows 沙箱全部相关目录/文件（如 `codex-rs/windows*`、sandbox 相关 crate）并列出清单文件 `.everyagent/tmp/codex/FILES.md`
    - 产物：源码 `.everyagent/tmp/codex/repo/`（main@92bc601a）；核心 crate `codex-rs/windows-sandbox-rs`（117 .rs）+ `codex-rs/windows-sandbox-service`（29 .rs）+ 集成点；清单 `.everyagent/tmp/codex/FILES.md`（242 行）
- [x] 步骤 2：通读 codex Windows 沙箱源码，产出机制分析文档（细分为 2a~2e 五个并行分片 + 主 Agent 合并；原单 agent 长文写入多次失败，已废弃）
    - 状态：已完成
    - agent：2a `sub_o3y45` / 2b `sub_o3y46` / 2c `sub_o3y47` / 2d `sub_o3y48` / 2e `sub_o3y49` / 2f 主 Agent 合并；原 `sub_o3y44` 已停止
    - 依赖：步骤 1
    - 验收标准：分析文档 `every-agent-plugins/sandbox-windows-codex/docs/codex-analysis.md` 覆盖：沙箱账户/SID 机制、工作区 ACL 授权、elevated/受限令牌处理、Job Object 用法、agent 进程模型、命名管道协议（请求/响应格式）、exec 生命周期（启动/超时/终止）
    - 产物：`docs/codex-analysis.md`（1206 行，合并 5 分片 parts/01~05，共 1192 行分片）；关键结论已抽查源码验证（无 AppContainer、WRITE_RESTRICTED=0x08、runner 管道命名）
- [x] 步骤 3：子 agent 设计 Java 版插件的映射方案，产出设计文档
    - 状态：已完成
    - agent：`sub_o3y4a`
    - 依赖：步骤 2
    - 验收标准：设计文档 `every-agent-plugins/sandbox-windows-codex/docs/design.md` 给出：模块与类清单、JNA 需补齐的 Win32 原语清单、agent/broker 双进程 Java 方案、与 `SandboxProvider`/`SandboxBackend`/`CommandExecutor`/`ToolProvider` 的对接方式、与现有 `sandbox-windows-mic` 的差异表
    - 产物：`docs/design.md`（262 行）。关键决策：仅做 elevated 后端（双账户+组+cap SID+WRITE_RESTRICTED+防火墙/WFP+DPAPI），暂缓 SCM 服务/registered runtime/ConPTY/env 分块；runner=CreateProcessWithLogonW 启动当前 JVM java.exe + 物化 runner.jar；帧协议对齐 IPC v6 全 9 消息
- [x] 步骤 4：子 agent 搭建插件骨架 + Win32 绑定层
    - 状态：已完成
    - agent：`sub_o3y4b`
    - 依赖：步骤 3
    - 验收标准：`every-agent-plugins/sandbox-windows-codex/` 下有 pom.xml（加入 every-agent-plugins 父模块）、plugin.json、Plugin 入口类；JNA 扩展接口（AppContainer、Job Object、命名管道、受限令牌等原语）编译通过
    - 产物：pom/plugin.json/CodexSandboxPlugin + win/ 绑定层 10 文件（Kernel32Ex/Advapi32Ex/NetApi32Ex/User32Ex/UserenvEx/Fwpuclnt/WinErr/struct×5，均 ≤400 行）；every-agent-plugins/pom.xml 已加 module；`mvn compile` 通过（主 Agent 复核 MVN_EXIT=0）。偏差均有据：CreateDesktopW 归 User32Ex、Ole32 复用不重映射、FWP_ACTRL_MATCH_FILTER 数值不臆造留待后续断言
- [x] 步骤 5：子 agent 实现沙箱内 agent 进程（管道服务端 + 子进程执行）
    - 状态：已完成
    - agent：`sub_o3y4c`
    - 依赖：步骤 4
    - 验收标准：agent 主类可在沙箱账户内运行：命名管道服务端、exec 请求解析、子进程启动/输出回传/超时终止、按 codex 协议对齐
    - 产物：runner/ 包 9 主类 + 4 测试类；`mvn test` BUILD SUCCESS（32 通过/3 Linux 跳过）；协议 v6 全 9 消息、令牌 flags 0x01|0x04|0x08、Job/HANDLE_LIST 属性、超时 192、管道命名/SDDL 均与 codex 源码核对一致；resize 有意 no-op（ConPTY 暂缓）

- [x] 步骤 6：宿主侧 broker（细分为 6a/6b 并行 + 6c 串行）
    - 状态：已完成
    - agent：6a `sub_o3y4d` ✅；6b `sub_o3y4e` ✅；6c `sub_o3y4f` ✅
    - 依赖：步骤 5（绑定层与 runner 协议）；6c 依赖 6a+6b
    - 验收标准（总）：broker 能创建/复用沙箱账户、为工作区目录授权 ACL、以 CreateProcessWithLogonW 启动 runner、经命名管道转发 exec 请求并收取结果
    - 产物（6a）：accounts/ 3 类 + setup/ 10 类 + fw/ 2 类 + win/NetFwCom（IDispatch 按名调用）
    - 产物（6b）：acl/ 15 类（ProvisioningAcl/RootPolicy/DenyRead 系列/AclDaclView 等）
    - 产物（6c）：session/ 4 类（RunnerMaterializer 哈希去重物化/RunnerPipe 15s 限时连接+PID 校验/RunnerClient CreateProcessWithLogonW+1056/1326 分类/CodexSandboxSession exec 门面）+ setup/AclApplierImpl + ServiceLoader 注册；模块累计 134 测试通过/10 Windows 跳过
    - 遗留 TODO：PrivateDesktop 未接（SpawnRequest 恒 null）、WFP provider 注册待绑定层补 flags 字段、windows-admin 端到端冒烟需管理员环境
- [x] 步骤 7：子 agent 对接插件 SPI 与测试、README
    - 状态：已完成
    - agent：`sub_o3y4g`
    - 依赖：步骤 6
    - 验收标准：`SandboxProvider`（id=`sandbox-windows-codex`）、`SandboxBackend`、`CommandExecutor`/`ToolProvider` 注册齐全；单元测试（Windows 专属用例 `assumeTrue` 守卫，WSL 下可跳过通过）+ README；`mvn -pl every-agent-plugins/sandbox-windows-codex -am compile test` 通过
    - 产物：Provider（id=codex，就绪 priority 8，绝不自动 UAC）/Backend（恒等挂载+Manager 登记根）/CodexCommandExecutor（三个可注入 seam）/cmd 工具 + codex_sandbox_setup|status 显式工具；plugin.json contributes.config 6 键；README 156 行；模块 165 测试全绿（14 Windows 跳过）；design.md 已同步修订（先改文档后改码）

- [x] 步骤 8：主 Agent 验收 + git 提交
    - 状态：已完成
    - agent：主 Agent（EveryAgent）
    - 依赖：步骤 7
    - 验收标准：按验收标准逐项复查（编译、测试、结构与 codex 机制对齐度），通过后以 `feat:` 前缀中文提交信息提交本次全部修改
    - 产物：主 Agent 复核 `mvn -pl every-agent-plugins/sandbox-windows-codex -am compile test` MVN_EXIT=0；依赖红线合规（仅 plugin-api+JNA+Jackson）；提交 `6db9fae`（feat: 前缀，107 文件 16,657 行，仅含本任务产物；工作区中 docs 两个删除与 subagent/ 两个修改为任务前既有改动，未纳入）

## 备注
- 依赖链 1→2→3→4→5→6→7→8 全串行：后续步骤均读写前一步落盘产物，不可并行。
- 网络回退：若浅克隆失败，依次尝试 GitHub codeload tarball / API 拉取子树；完全无网络则停下报告，由用户决定（不做任何离线杜撰源码）。
- 运行环境为 WSL Linux 沙箱：Windows API 相关代码只要求**编译通过 + 单测按平台守卫**，真实运行验证由 Windows 宿主承担。
- 版本与惯例对齐：JNA 5.16（jna + jna-platform，扩展现有平台接口补原语的惯用法）、Java 25、插件仅依赖 `every-agent-plugin-api`，不依赖 worker/hub。
- 临时源码放 `.everyagent/tmp/codex/`，不进入 git；分析文档与设计文档进插件 `docs/` 随代码提交。
- 遵守 ARCHITECTURE.md 红线：沙箱插件不写业务逻辑，越界 IO 仍由 PermissionGate 责任链兜底。
