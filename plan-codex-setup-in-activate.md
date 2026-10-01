# 将 codex 沙箱 setup 从 AI 工具改为 activate() 同步执行

## 目标
删除 `CodexSandboxSetupToolProvider`（AI 工具），将 setup 逻辑移入 `CodexSandboxPlugin.activate()` 中同步执行。用户安装插件即表示接受功能，setup 在插件激活时自动完成（幂等：已完成则短路）。setup 失败则插件激活失败（不影响其他插件）。

## 步骤
- [x] 步骤 1：新建 `SetupStatus` 工具类，迁移 `statusSummary` 纯函数
    - 状态：已完成
    - agent：-
    - 依赖：无
    - 验收标准：`SetupStatus.java` 包含 `statusSummary` 静态方法，签名与原 `CodexSandboxSetupToolProvider.statusSummary` 一致

- [x] 步骤 2：修改 `CodexSandboxPlugin.activate()`，加入同步 setup
    - 状态：已完成
    - agent：-
    - 依赖：步骤 1
    - 验收标准：activate() 中 Windows 平台 + marker 不完整时调用 `SetupOrchestrator.ensureSetup()`；非 Windows 跳过；不再注册 `CodexSandboxSetupToolProvider`；Javadoc 更新

- [x] 步骤 3：删除 `CodexSandboxSetupToolProvider.java`
    - 状态：已完成
    - agent：-
    - 依赖：步骤 1、2
    - 验收标准：文件删除，无编译引用残留

- [x] 步骤 4：更新 `CodexSandboxProvider.java` Javadoc
    - 状态：已完成
    - agent：-
    - 依赖：无
    - 验收标准：Javadoc 不再提及"显式的 codex_sandbox_setup 工具"，改为"activate() 中同步完成 setup"

- [x] 步骤 5：更新 `CodexCommandExecutor.java` 错误提示
    - 状态：已完成
    - agent：-
    - 依赖：无
    - 验收标准：未完成 setup 时的错误消息不再引用"codex_sandbox_setup 工具"，改为提示重新激活插件

- [x] 步骤 6：更新测试文件
    - 状态：已完成
    - agent：-
    - 依赖：步骤 1、2、3
    - 验收标准：`CodexSandboxSetupToolProviderTest.java` 改为 `SetupStatusTest.java` 测试 `SetupStatus.statusSummary`；`CodexCommandExecutorTest` 中引用 codex_sandbox_setup 的断言更新

- [x] 步骤 7：更新 README 和设计文档
    - 状态：已完成
    - agent：-
    - 依赖：步骤 1-5
    - 验收标准：README 和 design.md 中不再描述 setup/status 为 AI 工具，改为描述 activate() 自动完成

- [x] 步骤 8：编译验证
    - 状态：已完成
    - agent：-
    - 依赖：步骤 1-6
    - 验收标准：`mvn compile -pl every-agent-plugins/sandbox-windows-codex -am` 编译通过

- [x] 步骤 9：测试验证
    - 状态：已完成
    - agent：-
    - 依赖：步骤 8
    - 验收标准：`mvn test -pl every-agent-plugins/sandbox-windows-codex` 测试通过（Windows 专属测试跳过）

## 备注
- statusSummary 是纯函数，跨平台可单测，迁移到独立类后测试基本只改 import 和方法引用
- activate() 中 setup 会弹 UAC，这是预期行为（用户选择了安装此插件）
- setup 是幂等的（marker 双闸门短路），重复激活不会重复弹 UAC
