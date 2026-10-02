# agent 层统一台账 + agentMetadata + 自动注册收编

## 目标

将 agent 台账（agents.json 持久化 + 生命周期事件投影 + 冷启动恢复）从 subagent 插件收编到 worker agent 层核心；为 `AgentContext` 新增 `agentMetadata` 槽位（含 `creator` 标记）；`AgentBuilder.build()` 自动注册 agent 进 `ctx.agents()` 并发射 `agent.started` 事件（含 agentMetadata）。插件**只管创建与配置**——注册、事件发射、台账投影、持久化全部由 agent 层核心统一承担。

## 设计

### 核心变化：build() 自动注册

```
现状:
  插件 → ctx.agentFactory().create(id).build() → 拿到 Agent
  插件 → ctx.agents().put(id, agent)          ← 手动注册
  插件 → ctx.emitter().emit("agent.started")   ← 手动发事件
  插件 → SubAgentLedger 投影台账                ← 手动维护台账

改后:
  插件 → ctx.agentFactory().create(id)
           .agentMetadata(Map.of("creator","subagent"))
           .build()                            ← build() 内部自动完成下面三步 ↓
           ├─ exec.agents().put(id, agent)     ← 自动注册
           ├─ exec.emitter().emit("agent.started", payload含 metadata)  ← 自动发事件
           └─ AgentLedger 投影台账             ← 台账自动更新
  插件拿到已注册的 Agent，直接 run/stop       ← 插件只管执行
```

### 职责划分

| 职责 | 现状（归属） | 改后（归属） |
|---|---|---|
| agent 创建 | 插件（agentFactory.build()） | 不变 |
| 注册进 ctx.agents() | 插件手动 put | **build() 自动** |
| 发射 agent.started 事件 | SubAgentManager 手动 | **build() 自动**（payload 含 agentMetadata） |
| 台账事件投影 | SubAgentLedger（插件） | **AgentLedger（worker core）** |
| agents.json 读写 | SubAgentLedger（插件） | **AgentLedger（worker core）** |
| 冷启动恢复 | SubAgentLedger（插件） | **AgentLedger（worker core）** |
| agent.done / agent.status 终态事件 | SubAgentManager 手动 | **保留插件手动**（终态由执行体控制） |
| agentMetadata（creator） | 无 | **plugin-api AgentContext 新槽位** |
| list_agents/task.agents 过滤 | 无 | **读侧按 creator 过滤（消费方决策）** |

### 复用场景处理

SubAgentManager 的复用路径（`ctx.agents().get(id)` → `resetForRerun()` + `conversation().add()`）不经过 build()，不触发自动注册。但需要重新发射 `agent.started` 事件让台账更新。方案：SubAgentManager 在复用路径显式调 `exec.emitter().emit("agent.started", ...)`（或封装为核心 helper），台账自动投影。

## 步骤

- [x] 步骤 1：plugin-api — AgentContext/AgentBuilder 新增 agentMetadata 槽位
    - 状态：已完成
    - agent：sub_o3y4y
    - 依赖：无
    - 验收标准：`AgentContext` 新增 `default Map<String, Object> agentMetadata() { return Map.of(); }`；`AgentBuilder` 新增 `agentMetadata(Map<String, Object>)` 方法；编译通过
    - 产出：`every-agent-plugin-api/.../agent/AgentContext.java` + `AgentBuilder.java` 各加 agentMetadata 方法；提交 `feat: AgentContext/AgentBuilder 新增 agentMetadata 槽位`

- [x] 步骤 2：worker — AgentEntity/AgentBuilderAdapter 实现 agentMetadata + build() 自动注册
    - 状态：已完成
    - agent：sub_o3y4z
    - 依赖：依赖步骤 1
    - 验收标准：① `AgentEntity` 持有 `agentMetadata` 字段（构造注入）；② `AgentBuilderAdapter` fluent 透传 agentMetadata；③ **`build()` 末尾自动执行 `exec.agents().put(agentId, agent)` + `exec.emitter().emit("agent.started", payload含agentMetadata)`**；④ 插件调用方不再需要手动 put / emit agent.started；编译通过
    - 产出：`AgentEntity.java`（agentMetadata 字段+构造参数+接口实现）、`AgentBuilder.java`（Build.agentMetadata fluent + build() 自动注册+发事件）、`AgentFactoryImpl.java`（AgentBuilderAdapter 透传）；提交 `feat: AgentEntity/AgentBuilderAdapter 实现 agentMetadata + build() 自动注册`

- [x] 步骤 3：worker — 新建 AgentLedger（收编 SubAgentLedger 全部职责）
    - 状态：已完成
    - agent：sub_o3y50
    - 依赖：依赖步骤 2
    - 验收标准：worker core 新建 `AgentLedger`（订阅 EventLog 的 agent.started/done/status/usage/message/error 事件，维护 per-subject 内存台账，30s 定时 + 终态时写 agents.json，冷启动恢复）；`agent.started` 事件 payload 中的 `agentMetadata` 投影进台账条目；SubAgentLedger 的功能被完整替代；编译通过
    - 产出：`every-agent-worker/.../agent/AgentLedger.java`（374 行，完整收编 SubAgentLedger 功能 + metadata 投影）；编译通过

- [x] 步骤 4：worker — TaskEntry 装配 AgentLedger + 注册生命周期节点
    - 状态：已完成
    - agent：sub_o3y51
    - 依赖：依赖步骤 3
    - 验收标准：新建 3 个 worker 内置生命周期节点（AgentLedgerTrackNode/UntrackNode/PersistNode）；BuiltInTaskLifecycleNodes 注入 AgentLedger 并注册 3 个节点；编译通过
    - 产出：`AgentLedgerTrackNode.java`（order=150 下行）、`AgentLedgerUntrackNode.java`（order=340 上行）、`AgentLedgerPersistNode.java`（order=860 上行）；`BuiltInTaskLifecycleNodes.java` 注册 3 节点；`AgentLedger.java` 加 @Component

- [ ] 步骤 5：subagent 插件 — 移除 SubAgentLedger，SubAgentManager 改设 agentMetadata + 移除手动注册/事件
    - 状态：进行中
    - agent：sub_o3y52；② `SubAgentManager` 不再引用 ledger；③ `buildSubAgent` 创建时 `.agentMetadata(Map.of("creator", "subagent"))`，不再手动 `ctx.agents().put()` 和 `emit("agent.started")`/`emit("agent.status","running")`（由 build() 自动完成）；④ 复用路径仍调 `resetForRerun()` + 显式 emit `agent.started`（让台账更新）；⑤ `agentsJsonMerged` 改为只遍历 `ctx.agents().values()`（不再合并台账基底，台账已由 AgentLedger 统一维护）；⑥ `SubAgentPlugin` 不再注册台账相关生命周期节点（track/untrack/persist 由 TaskEntry/AgentLedger 内部处理）；⑦ 终态事件 `agent.done`/`agent.status` stopped 仍由 SubAgentManager 发射（执行体控制）；编译通过

- [ ] 步骤 6：ai-review 插件 — 设 agentMetadata + 移除手动注册
    - 状态：进行中
    - agent：sub_o3y53；不再手动 `ctx.agents().put()`（由 build() 自动注册 + 自动发 agent.started + 台账自动更新）；复用路径仍 `resetForRerun()` + 显式 emit `agent.started`；编译通过

- [ ] 步骤 7：list_agents / task.agents — 按 creator 过滤（消费方决策）
    - 状态：待执行
    - agent：-
    - 依赖：依赖步骤 5、6
    - 验收标准：`SubAgentManager.agentsJsonMerged` 遍历 `ctx.agents()` 时只保留 `agentMetadata().get("creator") == "subagent"` 的条目（消费方自行决定展示哪些 creator 的 agent）；`SubAgentRpcHandler` 读 agents.json 台账时同理按 creator 过滤；`list_agents` 返回结果中不包含 ai-review 的 review agent；`task.agents` RPC 同理；编译通过

- [ ] 步骤 8：文档同步 + 编译验证
    - 状态：待执行
    - agent：-
    - 依赖：依赖步骤 7
    - 验收标准：`docs/ARCHITECTURE.md` §7.20/§14.11 相关段落更新（agentMetadata 槽位、build() 自动注册、台账收编 agent 层、creator 过滤语义）；`docs/design-exec-context.md` 同步；`mvn compile` 全模块通过；提交

## 备注

- 步骤 1→2→3→4 串行（plugin-api 先行，worker 实现 + AgentLedger + TaskEntry 装配逐层依赖）
- 步骤 5、6 可并行（两个插件独立改动，均依赖步骤 4 的 build() 自动注册 + AgentLedger 就绪）
- 步骤 7 依赖 5、6 完成
- 步骤 8 收尾

关键设计决策：
1. **build() 自动注册**：`AgentBuilderAdapter.build()` 末尾自动 `exec.agents().put(agentId, agent)` + `exec.emitter().emit("agent.started", payload含agentMetadata)`——插件**永远不碰** put 和 agent.started 事件
2. **台账收编 agent 层**：SubAgentLedger（插件）→ AgentLedger（worker core），agents.json 读写、事件投影、冷启动恢复全部 worker 承担
3. **agentMetadata 随事件持久化**：`agent.started` 事件 payload 携带 `agentMetadata` → AgentLedger 投影进台账条目 → 随 agents.json 落盘
4. **creator 标记（唯一过滤依据，无 listed）**：`creator=subagent`/`creator=ai-review` 标识 agent 来源插件；**是否可见是消费方决策**——`list_agents`（subagent 插件的工具）只展示 `creator=subagent` 的条目，不设 `listed` 布尔（与 creator 冗余且耦合方向反了）
5. **复用路径**：不经过 build() 的复用场景（resetForRerun + conversation.add），由调用方显式 emit `agent.started` 让台账更新
6. **终态事件保留**：`agent.done`/`agent.status`(stopped) 等终态事件仍由执行体（SubAgentManager）发射——核心管"出生"，执行体管"死亡"
