# file-change 实现细节

> 本文内容提取自 [docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) §7.15.2。文中 §x.x 引用均指向 ARCHITECTURE.md 对应章节。

## 文件变更（file-change 插件）

每一轮 agent 执行中对工作区的文件写操作由 **file-change 插件自管**(task 核心零 fileChanges 概念,§14.11):

- **写侧(文件写监听一阶段记账,失败的写不回调)**:update_file/create_file **写盘成功的当下**,由 worker 文件工具(`FileTools`,经共享层 `FsToolSupport`)在**写锁(pathLock)内**快照「写前/写后**全文**」并回调 `FileWriteListener`(plugin-api SPI;插件经 `WorkerServices.addFileWriteListener` 注册,file-change 的 `FileChangeRecorder` 即其实现)——回调点在 `fs.writeText` 落盘之后、锁释放之前,同文件并发写按 pathLock 串行,天然保证**回调序=落盘序**,聚合取「首条 before 全文 + 末条 after 全文 + saveCount」不错位。失败(文件已存在/oldcontent 不唯一/授权拒绝等)即不回调不记账;listener 抛异常由 FsToolSupport 隔离(记 warn,不影响工具结果)。**旧「per-call 链解析入参记账」已退役**:update_file 的入参 oldcontent/content 只是被替换的**片段**而非文件全文,同文件多次写时聚合出「首条 before=首次入参片段、末条 after=末次入参片段」,两段不相干片段拼 diff,轮末面板呈错误变更(真实故障:同轮 4 次 update_file,分片 diff 显示 -11/+1,而实际文件只局部变化)。Entry.source 按 `agentId==mainAgentId` 标注 MAIN/SUB_AGENT(回调自带 agentId),子 agent 的文件改动不丢失。collector 由 provider 维护为**任务级共享实例**(activeCollectors,key=taskId),主 agent 与全部子 agent(经 `subjectId()` 定位任务)共写。主体已收口(`terminal()`)不再记账——轮已闭合,记账只会造孤儿 collector(宁漏记)。
- **收口与落盘**:`FileChangeAdvisor`(order=HIGHEST+301,工具 advisor 内层)只剩收口侧——主 agent 最后一轮(无 toolCalls,run 完成)收口暂存共享 collector 并退役活跃实例(下一 run 重新建,切断跨轮串扰;暂存的是实例引用,写盘时读最新状态,主 agent 流收口后才落定的迟到子 agent 记录不丢);流被取消/截断的 run 不收口,残留活跃实例由轮闭合回调清理、该轮不写分片(宁漏记,与既往口径一致)。`RoundIndexStore` 闭合轮后经 `RoundClosedListener` 回调按 roundId 写全文分片 `file-changes/<roundId>.json`(轻量摘要与全文同在该文件,不落 rounds.jsonl 行),回调同时清理该任务残留的活跃/暂存 collector。
- **读侧**:唯一取数口是插件自己的 `task.fileChanges` RPC——`roundId` 必填返回该轮全文;省略 `roundId` 返回全任务各轮的轻量摘要(`{rounds:[{roundId, changes:[{filePath,fileName,changeType,saveCount}]}]}`),供前端轮末面板一次拉全。
- **直推与刷新(§7.15.2 数据直推模型)**:`RoundClosedListener` 落盘成功后立即经 stream 频道直推瞬态事件 **`plugin.file-change.roundChanges`**(REPLACE,data `{roundId, changes:[轻量摘要]}`,不落盘;任务已驱逐时跳过推送只记日志);宿主 `taskStream` 对 `plugin.*` 前缀任务流事件一律不折入线程、透传领域事件 **`plugin-task-event`**(通用名,payload `{taskId, workerId, event, data}`,宿主不解释语义),插件 web 端订阅滤出本插件事件后按 roundId **upsert** 内存摘要缓存——不再走「作废→重拉」间接失效,轮末面板也无需过期缓存自愈补丁。时序:直推在分片落盘之后、`round.closed`(前端 refreshRounds 让轮末面板挂载)之前发出,面板挂载时缓存必已含本轮;推送失败/重连间隙错过属可接受降级,由初次打开的 `task.fileChanges` 全量拉取兜底——真相始终可从磁盘反推,旧任务(重构前落盘的分片)同样可显示,不依赖轮行内联字段。历史回放不经该路径(`plugin.*` 为瞬态直推专用,task.poll 折叠侧遇到即跳过不重发)。
