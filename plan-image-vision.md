# image-vision 图片识别插件

## 目标
新增插件 `image-vision`，让 AI 支持图片识别（依赖视觉模型）：输入框支持粘贴图片 / `@` 引用图片文件 → 显示 token 胶囊 → 点击胶囊打开文件标签页预览；图片一律以 base64（data URL）传给大模型。超限图片自动压缩，无法压缩到合法大小时报错提示用户。临时文件（截图粘贴）落工作区 `.everyagent/attachments/`；「随任务删除」语义由 worker 侧后续承接（见审查记录 #7）。

## 架构总览
- **核心统一处理文件粘贴**（前端）：任何文件粘贴统一转为 `@` 引用胶囊。临时文件（截图/无路径文件）存放：
  - 统一写工作区内隐藏目录 `<workspace>/.everyagent/attachments/`（用现成 `fs.write`，零新 RPC，
    workspace_file token，AI 零摩擦；老任务与草稿态同路径）。~~原方案「老任务直写任务数据目录、
    草稿态建任务后 fs.move 迁移」因 fs.* jailed 与提交后引用不可改写而不可行，见审查记录 #7。~~
  - **工作区外文件**：`system.external_file` 胶囊（经 ExternalFileTokenResolver 授权）。
- **worker 核心提供两级通用机制**（插件只注册 handler，不做 Advisor/节点）：
  - `FileReferenceHandler` SPI：按扩展名注册处理器，`process()` 返回 `{ replacementText, attachments }`。
  - `FileReferenceProcessNode`（TaskLifecycleNode，order≈395.4，consume.input=396 之前）：解析 `ctx.rawContent()` 中的文件引用 token → 按扩展名分发 handler → 用 `replacementText` 改写 `ctx.input()`，把 `attachments` 合并进 `TaskEntry.metadata`。
  - `FileAttachmentAdvisor`（核心统一 Advisor，`BaseAdvisor.before()`）：读 `TaskEntry.metadata.attachments`，把末位 `UserMessage` 重建为带 Spring AI `Media` 的多模态消息（data URL base64）。通用机制，不感知"图片"业务，未来 PDF/Word 插件复用同一 Advisor。
- **image-vision 插件只做一件事：注册 `ImageReferenceHandler`**（图片扩展名处理器，含压缩逻辑），符合「Advisor 单功能、复用 Spring AI」红线。

## 关键调研结论（已核实）
- 粘贴入口：`every-agent-web/src/components/taskComposer/InlineComposer.tsx` `handlePaste`(L419) 当前仅处理纯文本，文件/图片被丢弃。
- **token 只在 rawContent 中完整保留**：提交时 `replaceComposerTokensForSubmission` 把已注册 resolver 的 kind 替换成明文（`system.workspace_file` → ` 相对路径 `）；UserMessage 由 input 构造（`consumeInput` `new UserMessage(text)`），**不含 token**。因此"解析 token"必须在能拿到 rawContent 的**生命周期节点**（`TaskLifecycleContextImpl.rawContent()` getter/setter 已存在），Advisor 层拿不到 rawContent。
- user.message 事件同时落盘 text + rawContent（`TaskLifecycleContextImpl.consumeInput` L123-136），前端回放用 rawContent 还原胶囊，改写 input 不影响回放。
- **任务数据目录**：`~/.everyagent/workspaces/<workspaceId>/tasks/<taskId>/`（在 homeDir 下，与用户工作区是兄弟目录）；**建目录在 order=100**（`persistence.track`），晚于前端拿到 taskId（order=70 `ResponseAckNode`）；**删除是递归删除整个目录**（`TaskStore.delete` → `deleteRecursively(dir)`）。
- **前端无法写系统临时目录**：`fs.write` jailed 到工作区根 + 已授权 externalRoots；浏览器/Electron 都无写工作区外文件的通道。草稿态必须写工作区内隐藏目录。
- worker 已有同模式先例：`SlashTokenResolveAdvisor` 统一扫描正文 opaque token 按 kind 分发 resolver；`ExternalFileTokenResolver` 处理 `system.external_file`（自动注册 externalRoot，不弹窗）。
- worker 端文件读取：`FsService.read()` 直接 `Files.readAllBytes(file)`，jailed 只放行工作区根 + 已授权 externalRoots。
- 前端写工作区文件有现成 `fs.write` rpc 通道（经 hub 管道，jailed 到工作区根）。
- Advisor 注入范式：`SkillAdvisor`/`SystemInfoAdvisor` 在 `before()` 里重建 prompt + `mutate()`；Advisor 经 `AdvisorProviderRegistry` 装配（`AgentBuilder.build`）。
- `buildWorkspaceFileToken(entry)` 是核心工厂（payload: `{path, fileName, fullPath}`）；`buildExternalFileToken(entry)` 同理（payload: `{absolutePath, fileName, kind}`）。
- 浏览器（非 Electron）粘贴的文件 `File` 无 `path` 属性 → 统一走"写入工作区再引用"分支。

## 步骤
- [x] 步骤 1：核心前端增强——统一文件粘贴 + 点击打开文件标签页
    - 状态：已完成
    - agent：子 agent（本任务）
    - 依赖：无
    - 改动点（实现后与原文有两处修正，见备注审查记录 #7）：
      - `every-agent-web/src/services/pastedFileService.ts`（新增）：粘贴文件统一转 token——
        无宿主路径（截图/浏览器粘贴）→ 写 `<workspace>/.everyagent/attachments/paste-{timestamp}-{seq}.{ext}`
        后构造 `system.workspace_file` token；有路径在工作区内 → `system.workspace_file` 直接引用；
        有路径在工作区外 → `system.external_file` token（`buildExternalFileToken`）。
      - `every-agent-web/src/components/taskComposer/InlineComposer.tsx` `handlePaste`：新增
        `clipboardData.items` 遍历，识别 `kind === 'file'` 的项（图片/文档/任意类型统一处理）。
        文件分支是异步的（读文件/写 rpc），与现有同步文本分支并存；多个文件逐个插入；
        写入失败经 `onFilePasteError` 上报 toast 并跳过该文件，不阻断文本粘贴。
        新增 props：`workspaceRoot` / `onOpenWorkspaceFile` / `onFilePasteError`。
      - 胶囊点击：`system.workspace_file` 点击时打开文件标签页（对接 `buildWorkspaceFileTabTarget`
        现有语义）——输入框（`InlineComposer.handleRootClick`）与用户消息回放
        （`AgentMessageThread.UserMessageReplay`）两处都接线；`system.external_file` 保持现有弹详情行为。
      - `every-agent-web/src/components/taskComposer/TaskComposerSurface.tsx`：向 InlineComposer
        透传 `workspaceRoot`（显式值优先，缺省回退注册表首选根）与两个回调。
      - **临时文件落点修正（原"任务数据目录直写 + 建任务后迁移"不可行，见审查记录 #7）**：
        截图/无路径文件统一写工作区内 `.everyagent/attachments/`（老任务与草稿态同路径），
        不做事后迁移（迁移会弄断已提交 rawContent 中的路径引用，且 fs.* jailed 够不到任务数据目录）。
        「随任务删除」语义留待 worker 侧后续实现（如 FileReferenceProcessNode 落盘时搬迁）。
    - 验收标准：粘贴截图/图片/文档都出现 `@` 引用胶囊；截图统一写工作区 `.everyagent/attachments/`；
      点击胶囊（输入框 + 回放）打开文件标签页；纯文本粘贴行为不变；`npm run typecheck` 通过。
- [x] 步骤 2：worker 核心暴露 `FileReferenceHandler` SPI + `FileReferenceProcessNode` + `FileAttachmentAdvisor`
    - 状态：已完成（commit 83072f4）
    - agent：子 agent（本任务）
    - 依赖：无（与步骤 1 无文件交集，可并行）
    - 改动点：
      - `every-agent-plugin-api` 新增 SPI（契约类型）：
        ```java
        public interface FileReferenceHandler {
            String pluginId();
            Set<String> extensions();                    // 小写含点，如 {".png", ".jpg"}
            FileReferenceResult process(FileReferenceContext ctx, FileReference ref);
        }
        public record FileReference(String path, String fileName, String fullPath,
                                    boolean external /* system.external_file 为 true */) {}
        public record FileReferenceResult(String replacementText,
                                          List<Map<String, Object>> attachments) {}
        // attachments 项约定结构：{type: "image", dataUrl, fileName, mimeType}
        ```
      - `WorkerPluginContext` 新增 `registerFileReferenceHandler(FileReferenceHandler)`；worker 侧加注册表。
      - worker 核心新增 `FileReferenceProcessNode implements TaskLifecycleNode`（order≈395.4，consume.input=396 之前，与 EditResendNode=395.5 错开）：解析 `ctx.rawContent()` 中的 `system.workspace_file` / `system.external_file` opaque token（4 连符号定界 + base64url payload，与前端 `composerOpaqueToken` 同格式）→ 按扩展名查注册表 → 命中则调 `process()`：用 `replacementText` 改写 `ctx.input()` 中对应的路径文本；把 `attachments` 追加进 `TaskEntry.metadata.attachments`（持久化，跨轮可读）。未命中/无 handler 时不改 input（路径文本保持现有行为）。
      - worker 核心新增 `FileAttachmentAdvisor implements BaseAdvisor`（注册进内置 AdvisorProvider 链）：`before()` 读 `TaskEntry.metadata.attachments`，把 `type=image` 项转为 Spring AI `Media`（`MimeType` + data URL），重建末位 `UserMessage` 并 `mutate()`；无附件时原样放行。
    - 验收标准：`mvn -q compile`（plugin-api + worker）通过；SPI 可注册、节点可分发、Advisor 可注入；未命中处理器的 `@` 文件行为完全不变（路径文本进 AI）。
- [x] 步骤 3：image-vision 插件（纯后端：注册 ImageReferenceHandler，含压缩）
    - 状态：已完成（commit f137aa1）
    - agent：-
    - 依赖：依赖步骤 2（FileReferenceHandler SPI + FileReferenceProcessNode）
    - 改动点：新建 `every-agent-plugins/image-vision/`。
      - `ImageReferenceHandler implements FileReferenceHandler`（extensions: .png/.jpg/.jpeg/.gif/.webp/.bmp）：`process()` 中经 jailed 路径读取文件（workspace_file 走工作区根；external_file 走 PermissionGate 授权链，未授权则降级为纯路径文本并记日志）→ **压缩** → 转 base64 dataURL → 返回 `FileReferenceResult(replacementText="（附图：{fileName}）", attachments=[{type:"image", dataUrl, fileName, mimeType}])`。
      - **图片压缩逻辑**：合法上限 = 单图 base64 后 **≤ 10MB**（约 750 万像素 PNG / 1500 万像素 JPEG，插件 setting 可配）。
        - 原图 ≤ 10MB：直接使用。
        - 原图 > 10MB：`ImageIO` + `BufferedImage` 逐步缩放（每次缩 10%，最多 10 次，最小 100×100），缩到 ≤ 10MB 为止；headless 环境注意只用 ImageIO/BufferedImage、不碰 Swing/AWT 组件。
        - 压到最小尺寸仍超限：返回 `replacementText="（图片过大，无法处理：{fileName}，请缩小后重试）"` + 空 attachments，记 warn 日志——错误文本随 input 进会话，由 AI 转告用户，不阻断任务。
      - `ImageVisionPlugin implements EveryAgentPlugin`（id=`image-vision`）：activate 仅注册 `ImageReferenceHandler`。
      - 插件 setting：注入开关、单图大小上限（默认 10MB）、支持的图片扩展名列表。
      - `plugin.json`（只含 `main`，无 `webMain`）、`pom.xml`；在 `every-agent-plugins/pom.xml` `<modules>` 加 `<module>image-vision</module>`。
    - 验收标准：`mvn -q compile`（plugins 模块）通过；图片 token 被节点分发到 handler，`metadata.attachments` 正确写入且 Advisor 注入 Media；>10MB 图片被压缩到 ≤10MB；无法压缩时 input 出现错误提示文本；读取失败/未授权时降级不阻断。
- [x] 步骤 4：整体构建与端到端验证
    - 状态：已完成（根 `mvn -q -DskipTests install` EXIT 0；web `npm run typecheck` 零错误、`npm run build` 成功。端到端 UI 演示待人工验证。）
    - agent：-
    - 依赖：依赖步骤 1、2、3
    - 改动点：根 `mvn -q -DskipTests install`（worker + plugins）；web `npm run typecheck` + `npm run build`。
    - 验收标准：全仓编译通过；插件被 worker `plugin.list` 发现；端到端「粘贴截图 → 出胶囊 → 点击打开文件标签页 → 提交 → 模型收到 base64 图片」可演示；粘贴/`@` 非图片文件行为不变。

## 备注
- 依赖关系：步骤 1（前端）与步骤 2（worker 核心）无文件交集，可并行；步骤 3 依赖步骤 2；步骤 4 收尾。
- 已修正的方案问题（审查记录）：
  1. ~~Advisor 解析 UserMessage 中的 token~~ → token 提交后已替换为路径文本，Advisor 拿不到；改由生命周期节点解析 rawContent（token 唯一完整载体）。
  2. ~~截图写系统临时目录~~ → worker jailed 读不到系统临时目录；改为写入工作区内 `.everyagent/attachments/`（草稿态）或任务数据目录（老任务）。
  3. ~~粘贴文件一律构造 workspace_file token~~ → 工作区外文件语义不符且 jailed 读不到；工作区外走 `system.external_file`。
  4. ~~每个文件类型一个 Advisor~~ → 核心统一 `FileAttachmentAdvisor` + 插件只注册 handler。
  5. 浏览器粘贴的 File 无 path 属性 → 统一走"写入工作区再引用"。
  6. **新增**：临时文件迁移——草稿态写工作区 `.everyagent/attachments/`，建任务后 `fs.move` 到任务数据目录（跨树：工作区 → homeDir），满足「随任务删除」语义。
  7. **新增（步骤 1 实施时修正）**：~~临时文件写/迁移到任务数据目录~~ → 不可行，有两点硬约束：
     ① `fs.write`/`fs.move` 被 jailed 到「工作区根 + 已授权 externalRoots」，任务数据目录
     `~/.everyagent/workspaces/<wsId>/tasks/<taskId>/` 在 homeDir 下、前端经 fs.* 够不到
     （注册 externalRoot 需路径已存在且会把任务事件日志暴露为可读写，不可取）；
     ② 「拿到 newTaskId 后再迁移并改写 token path」不成立——rawContent 已随 task.run 上行并
     落盘，事后移动文件会弄断已提交引用。故临时文件统一写工作区内 `.everyagent/attachments/`
     （老任务/草稿态同路径、不迁移）；「随任务删除」留待 worker 侧后续实现（如
     FileReferenceProcessNode 消费时把附件搬进任务数据目录并改写 metadata，而非 rawContent）。
- 已知约束：
  - 图片识别能力取决于所用模型是否支持视觉；插件只负责注入，不校验模型能力。
  - 粘贴产生的附件统一落工作区内 `.everyagent/attachments/`（老任务/草稿态同路径，不迁移）；
    「随任务删除」语义由 worker 侧后续承接（见审查记录 #7），当前需定期清理该隐藏目录（可后续加清理策略）。
  - `metadata.attachments` 持久化后多轮持续注入（便于追问），token 消耗随轮次累积；如需「仅当轮」可后续细化。
  - 未来 PDF/Word 等插件只需注册各自的 `FileReferenceHandler`（attachments 可扩展 `type:"text"` 等新类型 + 核心 Advisor 按需支持），无需改核心节点。
  - 任务数据目录在 homeDir 下（`~/.everyagent/workspaces/<wsId>/tasks/<taskId>/`），与用户工作区是兄弟目录；前端预览需经 rpc 读（已有 `fs.read` 通道）。
