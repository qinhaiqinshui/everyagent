# unattended 实现细节

> 本文内容提取自 [docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) §7.9（无人值守部分）。文中 §x.x 引用均指向 ARCHITECTURE.md 对应章节。

## 无人值守（unattended 插件）

当需要人工授权(PermissionGate 拦到工作区外路径/危险命令)时,除人工弹窗外提供可选的任务级自动路径——无人值守是其一。

- **无人值守(`/无人值守`,kind=unattended.mode)**:开启时**联动**开启 AI 审议——选中处理器(selectHandler)只返回单条结果,并在该条目上声明 `chainIds=["ai-review:on"]`;worker 斜杠层据此逐个 chainId 用 `SlashCommandRegistry.itemById(id)` 取被联动条目**自身**的 `insertText` 作为额外 bottom 结果(token 由归属插件构造,跨插件零耦合;条目未注册 = 被联动插件未启用 → 静默跳过、降级为单胶囊)。前端(`TaskComposerSurface`)按多结果逐个 await apply,故一次选中即挂上「无人值守」+「AI 审议」两个底部胶囊。AI 仍可看到并调用 `ask_user` 工具,但 `UnattendedToolInterceptor`(工具执行拦截链节点,§7.14.3)在工具执行瞬间拦截该调用、代替人工逐题选择第一个选项,以「题干：首选项」格式回传作答文本(与前端真实作答格式一致;不创建 ask、不挂起等待);拦截器每次工具执行实时经 `WorkerServices.slashTokens().hasKind(subjectId, "unattended.mode")` 查任务级 slash token(以磁盘 `slash-tokens.json` 为准,运行中/终态/重启后一致),运行中点胶囊开/关即时生效。两胶囊 ✕ 独立,开启时联动、事后可拆分。

## 授权拦截链（无人值守环节）

`PermissionGate.ensureGranted` 内、发起人工弹窗前短路,本环节为链中第 ② 环:

| 拦截链环节 | 判定依据 | 行为 |
|---|---|---|
| ② 无人值守 | 任务级 `unattended.mode` slash token | 开启 → 授权直接拒绝(无人工可弹);未开 → 正常人工弹窗 |

> 注:人工弹窗为链终结节点(第 ③ 环),从不 PASS。详见 ARCHITECTURE.md §7.9。
