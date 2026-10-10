# 抽离用户交互为公共能力

## 目标
将 ask_user 与授权弹窗共享的交互管道从 task 包中抽离为独立的公共能力：接口放 plugin-api，实现放 worker，协议放 contract schema，前端删除所有 kind/responseMode 语义分支，统一为 option.type 驱动渲染。

## 步骤

- [x] 步骤 1：contract schema 改 askCreate payload
    - 状态：已完成
    - agent：主 Agent
    - 依赖：无
    - 验收标准：`events.schema.json` 中 askCreate 删除 kind 必填字段，options 从 string[] 改为 {label, value, type} 对象数组；type 枚举 radio|input

- [x] 步骤 2：plugin-api 新建 interaction 包（接口 + 数据类型）
    - 状态：已完成
    - agent：sub_o3y2f
    - 依赖：步骤 1
    - 验收标准：新建 `dev.everyagent.plugin.api.interaction` 包，含 AskOption/AskQuestion/AskResult/InteractionService 四个类型；InteractionService 含同步 ask() 与异步 askAsync()；WorkerServices 和 ToolContext 各加 interaction() 方法 ✓

- [x] 步骤 3：worker 实现 InteractionServiceImpl + 改造原 PendingAsks
    - 状态：已完成
    - agent：sub_o3y2f
    - 依赖：步骤 2
    - 验收标准：新建 `dev.everyagent.worker.interaction.InteractionServiceImpl`，实现 InteractionService 接口（原 PendingAsks 核心逻辑迁入，ask() 删 kind 参数，emit payload 不含 kind，options 为对象数组）；原 PendingAsks 引用点改为注入 InteractionService 接口；TaskManager.onAskReply 调用改为 Impl 的 resolve() ✓

- [x] 步骤 4：worker 改造调用方（AskUserTool + HumanAuthorizationHandler + GrantRegistry + WorkerServicesImpl + ToolContextImpl + EventPayloads）
    - 状态：已完成
    - agent：sub_o3y2f
    - 依赖：步骤 3
    - 验收标准：AskUserTool 用 AskOption 构造选项并自行追加 input 类型「其他」；HumanAuthorizationHandler 用 radio + token value 构造选项，调用删 kind；GrantRegistry 调用 ask 删 kind；WorkerServicesImpl 暴露 interaction()；ToolContextImpl 暴露 interaction()；EventPayloads.questionsToJson 适配新结构 ✓

- [x] 步骤 5：前端 askStore.ts 删分支 + 统一映射
    - 状态：已完成
    - agent：sub_o3y2g
    - 依赖：步骤 1
    - 验收标准：toInteractionRequest 删除所有 kind/responseMode 分支，纯映射 options→渲染数据（label/value/type）；toAnswerText 删除 responseMode 分支，统一返回「题干：value」✓

- [x] 步骤 6：前端 UserInteractionHost.tsx 统一渲染 + types 清理
    - 状态：已完成
    - agent：sub_o3y2g
    - 依赖：步骤 5
    - 验收标准：删除 isConfirmMode/isAuthorizationMode/responseMode 分支，统一按 option.type 渲染 radio 或 input；types/index.ts 删除 UserInteractionResponseMode、responseMode、confirmed 等字段，UserInteractionOption 加 value/type ✓

- [x] 步骤 7：编译验证（worker mvn + web typecheck）
    - 状态：已完成
    - agent：主 Agent
    - 依赖：步骤 4 + 步骤 6
    - 验收标准：worker `mvn compile` 通过；web `npx tsc --noEmit` 通过 ✓

## 备注
- 步骤 1、2、3、4 为 Java 侧串行依赖链（1→2→3→4）
- 步骤 5、6 为前端侧串行依赖链（5→6），但步骤 5 只依赖步骤 1（schema 定义），可与 Java 侧步骤 2-4 并行
- 步骤 7 依赖两侧全部完成
- 并行策略：步骤 2+3+4 合并为一个子 Agent（Java 侧连续改造），步骤 5+6 合并为一个子 Agent（前端侧连续改造），两路并行
