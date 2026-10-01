# 授权链域中性化方案

## 1. 问题

授权决议链（`AuthorizationHandler` 责任链 + `GrantRegistry` 宿主）的请求契约
`AuthorizationRequest(TaskInfo task, String agentId, String grantKey, String prompt)`
把任务域类型 `TaskInfo` 焊进了授权 SPI 签名。三个链节点全部从 `req.task()` 取数据，
其中两个还要强转到更具体的任务域类型（`TaskRuntime` / `TaskEntry`）。

后果：未来工作流层（架构 §14.0 已明说"task 层或以后的工作流层"平级编排、
line 646"对 task 层与未来工作流层完全同构"）要走同一条授权链，就得先
`implements TaskInfo` 伪造一个任务——编排层为了复用基础设施，被迫实现任务域接口，
这是域反向依赖。

物理症状：`TaskInfo` 接口住在 `permission` 包，但它既不是授权概念也不是纯任务概念，
是"授权链今天唯一消费者的投影"，哪边都不归属。

## 2. 目标

- 授权链契约（`AuthorizationContext` + `AuthorizationHandler`）**零任务域类型引用**。
- 三个链节点 + `GrantRegistry` 宿主全部域中性化。
- 未来工作流层走同一条授权链，**链代码（节点 + 宿主）零改动**。
- `TaskInfo` 迁回 `task` 包，`permission` 包零 task 类型引用。
- agent 创建能力经**预绑定的 AgentFactory** 从上层往下传（七层模型同构），
  不为审议单独设端口——agent 创建是通用能力，不该为单一场景定制接口。

## 3. 授权链演进史与耦合成因

### 3.1 演进时间线

| 阶段 | commit | 变化 | 耦合状态 |
|---|---|---|---|
| worker 单体 | init (32f643e3) | `AiAuthReviewer.review(TaskEntry t, ...)` 直接接 worker 具体类；`GrantRegistry.resolveScope()` 硬编码三段式（AI 审议 → 无人值守 → 人工弹窗） | 零抽象，TaskEntry 是唯一上下文载体 |
| 抽插件 | d7216d4 | AiReviewAuthHandler 新建（34 行），`applies() + decide()` 两步接口；`AuthorizationRequest` 在 worker 包里，携带 `TaskEntry` | handler 出现，但 reviewer 签名没变 |
| 依赖倒置 | 966835e | `AuthorizationHandler` 移到 plugin-api，`AuthorizationRequest.task` 类型从 `TaskEntry` 改成新建的 `TaskInfo`（窄接口） | **首次出现"接口窄 + 使用处强转宽"的错位**：handler 拿到 `TaskInfo` 后 `(TaskEntry) req.task()` 强转回去 |
| 消除 worker 依赖 | bde6e2a | `AiAuthReviewer` 改用 `AgentFactory` + `WorkerConfig`；签名从 `TaskEntry` 改成 `TaskRuntime`（plugin-api 接口） | 强转从 `TaskEntry` 改成 `TaskRuntime` |
| filter 形态 | b6dc36f | `applies() + decide()` 改成 `invoke(req, next)` filter 形态；`taskFlags` 改成 `metadata` | 今天的形态：`(TaskRuntime) req.task()` 强转保留 |

### 3.2 为什么设计成"窄接口 + 强转"

`AuthorizationRequest.task` 的类型是 `TaskInfo`，只暴露了
`taskId / status / terminal / metadata / taskDir`——这是**所有 handler 的最大公约数**
（Unattended 只要 metadata，Human 只要 taskId）。但 AiReview 需要 `TaskRuntime`（更宽），
因为它要取 `events()` / `snapshot()` / `workspaceRoot()` 来跑审议 agent。

设计者的权衡是：**让请求类型保持最窄，需要更多信息的 handler 自己强转**。
这比"让请求类型暴露所有可能需要的字段"更"干净"——但代价是 handler 里的强转，
且 `TaskInfo` 被迫住进 `permission` 包（它只是"授权链今天唯一消费者的投影"）。

### 3.3 为什么 AiReviewAuthHandler 直接调 `reviewer.review()`

handler 和 reviewer 在**同一个插件模块**里（`ai-review`），是内部协作：

```java
// AiReviewPlugin.activate()
AiAuthReviewer reviewer = new AiAuthReviewer(ctx.services().config(), ctx.services().agentFactory());
ctx.registerAuthorizationHandler(new AiReviewAuthHandler(reviewer));
```

handler 判定 + reviewer 执行，是同一个功能的两个切面。但 reviewer 的签名
`review(TaskRuntime t, ...)` 暴露了审议执行需要任务运行时，这个需求被**塞进了
handler 的 invoke 方法里**（通过强转）。内部依赖泄漏到了跨模块契约。

### 3.4 同样的模式也出现在 SubAgentManager

SubAgentManager 用 AgentFactory 时做的是**完全一样的事**：

```java
// SubAgentManager.buildSubAgent()
String configId = task.snapshot().configId();
Map<String, Object> props = new HashMap<>();
props.put("taskEntry", task);
props.put("taskId", task.taskId());
props.put("workspaceRoot", task.workspaceRoot());
return agentFactory.create(agentId, configId, task.events(), props) ...

// AiAuthReviewer.buildReviewAgent()
Map<String, Object> agentProps = new HashMap<>();
agentProps.put("taskEntry", t);
agentProps.put("taskId", t.taskId());
agentProps.put("workspaceRoot", t.workspaceRoot());
agentProps.put("configId", configId);
return agentFactory.create(reviewAgentId, configId, t.events(), agentProps) ...
```

两者都从 TaskRuntime 里取出 `events()` + 构造 props（含 `"taskEntry"`）+ 取 configId，
然后传给工厂。**这说明问题不在授权链，而在 AgentFactory 本身——它是"通用工厂"，
消费者每次都要手动从 TaskRuntime 里取绑定信息填进去。**

## 4. 设计原则

### 4.1 授权链只管审核对象与权限

授权请求只描述"对什么资源请求什么操作"，决议只回"通过与否"。不携带任务域
或工作流域的类型。

### 4.2 task 层往下传绑定的能力，不传绑定的数据

task 层不感知下游（授权链节点）需要什么。它只提供一个**已绑定 task 上下文的
AgentFactory**——消费者用这个工厂 `create()` 出来的 agent 天然带 task 信息
（事件日志、advisor 链、模型配置），消费者不需要碰 TaskRuntime。

这与 EventEmitter 七层模型同构：插件拿到的 EventEmitter 已被 task 层绑定好
（emit 时自动填 agentId、自动进该任务的日志），插件完全不知道背后是哪个任务。
预绑定的 AgentFactory 同理——工厂内部已闭包 emitter + taskEntry + 默认 configId，
消费者只管 `create(agentId)` 就能拿到带 task 上下文的 agent。

### 4.3 否决项：公共 agent（不与 task 关联）

曾考虑让 AiAuthReviewer 自己从 WorkerServices 获取通用 AgentFactory，创建一个
"公共 agent"（不与 task 关联）。**否决**，三个理由：

1. **审计落盘被击穿**。§7.9 要求 `task.trace(kind=auth.review)` 持久落盘，
   审计轨迹必须进被审议任务自己的 jsonl。公共 agent 没有宿主事件日志，
   审计无处可落。
2. **Advisor 链直接坏掉**。审议 agent 靠 `AgentFactory.create()` 获得重试/压缩/限流
   链，这套链的装配约定是 `agentProps.get("taskEntry")`——`RateLimitAdvisor`、
   `TransientErrorRetryAdvisor`、`ContextCompressionAdvisor` 等 20+ 处都这么取。
   没有 taskEntry 的"公共 agent"要么 NPE 要么静默跳过：审议失去瞬时错误重试 →
   网络抖动直接走 fail-closed DENY，误拒率显著上升。
3. **模型选择断了**。缺省审议模型 = 任务当前 `snapshot().configId()`；公共 agent
   拿不到，只能强制全局 `review-model` 配置，功能退化。

### 4.4 否决项：ReviewExecutor 端口

曾考虑为审议单独设一个 `ReviewExecutor` 域中性端口，由 task 层和工作流层各自实现。
**否决**，理由：

- task 层不应该感知"下游要做审议"——它只提供一个绑定了 task 信息的 AgentFactory，
  下游拿它做什么（审议、子 agent、其他）是下游的事。
- agent 创建是通用能力，不该为审议这一个场景定制接口。
- `ReviewExecutor` 把"审议"这个业务概念塞进了 plugin-api 契约，违反"task 层不感知
  下游需求"原则。

## 5. 逐节点本质分析

### 5.1 UnattendedAuthHandler（插件，order=200）

**全部逻辑**：读 `metadata.get("unattended")`，为 true 就 DENY（无人可弹），否则 PASS。

**本质**：读一个 boolean，判断"主体当前是否处于无人模式"。

**任务域依赖**：零。`metadata` 从哪来、键叫什么，handler 完全不 care——它只要 map
里有个 boolean。工作流也能提供同样的 metadata map。

**域中性化改动**：`req.task().metadata()` → `ctx.metadata()`。一行。

### 5.2 HumanAuthorizationHandler（core，order=300）

**全部逻辑**：把 prompt 原文抛给 `InteractionService.ask()`，收三态 token
（deny/run/task），转 ALLOW/DENY。

**任务域依赖**：唯一一处——`(TaskEntry) req.task()` 取 `t.taskId()`，塞进
`interaction.ask()` 的 context map（`Map.of("taskId", t.taskId, "agentId", ...)`）。
`InteractionService` 是 plugin-api 域中性接口，context 是纯透传 map（不解析），
所以 `taskId` 本质就是 **subjectId**。

**域中性化改动**：`(TaskEntry) req.task()` → `ctx.subjectId()`。一行。
`interaction.ask()` 调用的 context map 里 `"taskId"` 键名保持不变（前端兼容），
值从 `TaskEntry.taskId` 改为 `AuthorizationContext.subjectId()`——今天两者相等。

### 5.3 AiReviewAuthHandler（插件，order=100）

**全部逻辑**：
1. 读 `metadata.get("ai-review")`，为 false 则 PASS（同 Unattended）。
2. 为 true 则调 `reviewer.review((TaskRuntime) req.task(), grantKey, prompt)`，
   按返回的 `ReviewDecision` 映射 ALLOW/DENY/PASS。

**任务域依赖**：前半零依赖（同 Unattended）。后半的耦合**不在授权链节点本身**，
而在 `reviewer.review()` 的**执行载体**。拆开看 `AiAuthReviewer.review(TaskRuntime t, ...)`
从 `t` 取了什么：

| 取了什么 | 用途 | 任务域专属？ |
|---|---|---|
| `t.events()` (EventEmitter) | 发审计 trace | **否**——plugin-api 域中性端口 |
| `t.snapshot().configId()` | 选审议模型 | **否**——字符串 |
| `t.workspaceRoot()` | 注入审议 prompt | **否**——字符串 |
| `t.taskId()` | 审计字段 | **否**——subjectId |
| `"taskEntry"` 作为 agentProp 传给 AgentFactory | 审议 agent 的 advisor 链装配 | **是**——agent 执行域耦合 |

前四项全是值。第五项是真的耦合：审议 agent 走 `AgentFactory.create()`，自动获得全套
Advisor 链（retry/compression/rate-limit），这些 advisor 全从 `agentProps.get("taskEntry")`
取 TaskRuntime/TaskEntry。**这是 agent 执行域的既存耦合（20+ 处 advisor 都这么取），
不是授权域的。**

**域中性化方案**：handler 不感知 TaskRuntime。AuthorizationContext 携带一个
**预绑定的 AgentFactory**（由 task 层构造时注入），AiAuthReviewer 从 ctx 取这个工厂
创建审议 agent——工厂内部已闭包 task 绑定信息，reviewer 不碰任何 task 域类型。

## 6. 预绑定的 AgentFactory（静态代理模式）

### 6.1 问题：消费者手动填绑定信息

当前 AgentFactory 签名：

```java
public interface AgentFactory {
    AgentBuilder create(String agentId, String configId,
                        EventEmitter emitter, Map<String, Object> properties);
}
```

消费者（AiAuthReviewer、SubAgentManager）每次都要手动从 TaskRuntime 里取出
`events()` + 构造 props（含 `"taskEntry"`）+ 取 configId，然后传给工厂。
两个消费者做的是完全一样的事——这说明绑定信息该由 task 层预注入工厂，
不该由消费者手动填。

### 6.2 方案：BoundAgentFactory implements AgentFactory（静态代理）

**不新增接口类型**。`BoundAgentFactory` 是 `AgentFactory` 的一个实现——
静态代理，覆写 `create()` 用预绑定的 emitter/properties 兜底消费者传 null 的参数位。
插件依赖的类型不变（还是 `AgentFactory`），只是拿到的实例内部已绑好 task 信息。

`AgentFactory` 接口加两个 default 便捷方法（短签名）：

```java
public interface AgentFactory {

    // 原方法不变
    AgentBuilder create(String agentId, String configId,
                        EventEmitter emitter, Map<String, Object> properties);

    // 新增便捷重载：emitter/properties 为 null 时由实现用绑定值兜底
    default AgentBuilder create(String agentId, String configId) {
        return create(agentId, configId, null, null);
    }

    default AgentBuilder create(String agentId) {
        return create(agentId, null, null, null);
    }
}
```

worker 侧的静态代理实现：

```java
// worker 内部，不暴露到 plugin-api
// 在构造 AuthorizationContext 时创建
class TaskBoundAgentFactory implements AgentFactory {
    private final AgentFactory delegate;        // 原始 AgentFactoryImpl
    private final EventEmitter boundEmitter;     // = task.events()
    private final Map<String, Object> boundProps;// = {taskEntry, taskId, workspaceRoot, ...}
    private final String boundConfigId;          // = task.snapshot().configId()

    @Override
    public AgentBuilder create(String agentId, String configId,
            EventEmitter emitter, Map<String, Object> properties) {
        // 消费者传 null 的参数位用绑定值兜底；传非 null 则尊重消费者覆盖
        return delegate.create(agentId,
                configId    != null ? configId    : boundConfigId,
                emitter     != null ? emitter     : boundEmitter,
                properties  != null ? properties  : boundProps);
    }
}
```

### 6.3 设计优势

1. **零新接口类型**——插件 import 不变，学的东西不增加。AuthorizationContext 的
   `agentFactory` 字段类型是 `AgentFactory`，不是新类型。
2. **可链式套娃**——task 层包一层、工作流层再包一层、每层往 properties 里塞自己
   的标记，消费者看到的最外层 `AgentFactory` 透明累积了所有层的绑定：

   ```java
   // task 层
   AgentFactory taskFactory = new TaskBoundAgentFactory(rawFactory, taskEmitter, taskProps, taskConfigId);
   // 工作流层再包（如果 agent 在工作流上下文里创建）
   AgentFactory wfFactory = new WorkflowBoundAgentFactory(taskFactory, wfEmitter, wfProps, wfConfigId);
   // → create(id) 时：wfFactory 先填 wf 绑定值 → 如果 wf 没覆盖则透传到 taskFactory 填 task 绑定值
   ```

3. **向后兼容**——SubAgentManager 今天调 `create(id, configId, emitter, props)` 四参数
   版本，明天改成 `create(id, configId)` 两参数版本，中间可以平滑过渡；不改也能跑
   （传非 null 的 emitter/props 时覆盖绑定值，语义合理）。

### 6.4 消费侧变化

AiAuthReviewer 不再直接依赖 AgentFactory + TaskRuntime：

```java
// 当前
public ReviewDecision review(TaskRuntime t, String grantKey, String prompt) {
    ...
    Map<String, Object> agentProps = new HashMap<>();
    agentProps.put("taskEntry", t);
    agentProps.put("taskId", t.taskId());
    agentProps.put("workspaceRoot", t.workspaceRoot());
    agentProps.put("configId", configId);
    return agentFactory.create(reviewAgentId, configId, t.events(), agentProps) ...

// 改后
public ReviewDecision review(AgentFactory factory, String subjectId,
        String workspaceRoot, EventEmitter events,
        String grantKey, String prompt) {
    ...
    String configId = resolveReviewModel();  // review-model 配置，可能为 null
    Agent agent = factory.create(reviewAgentId, configId)  // 绑定信息已在工厂内
            .title("AI 安全审议")
            .tools(List.of(), AgentBuilder.ModifyMode.REPLACE)
            .systemPrompt(reviewSystemPrompt(workspaceRoot))
            .userInput(userPrompt(grantKey, prompt))
            .build();
    ...
```

SubAgentManager 同理（独立改造，不在本次范围）：

```java
// 当前
agentFactory.create(agentId, configId, task.events(), props) ...

// 改后
agentFactory.create(agentId) ...  // 绑定信息已在工厂内
```

## 7. GrantRegistry 宿主域中性化

GrantRegistry 不是链节点，是链宿主（授权状态 + 持久化 + 生命周期）。当前 task 域耦合：

| 方法 | 当前 | 域中性化 |
|---|---|---|
| `authorize(TaskEntry t, ...)` | `t.taskId()` 分区 + `store.dirOf(taskId)` 落盘 | `authorize(AuthorizationContext ctx, List<Path> roots, List<Path> execRoots)`：分区用 `ctx.subjectId()`，落盘目录从 `ctx.grantStoreDir()` 取 |
| `beginRun(taskId)` | 清 run 档 grants | `beginRun(String subjectId)` |
| `untrack(taskId)` | 驱逐内存 | `untrack(String subjectId)` |
| `persistTaskGrants(TaskEntry t, ...)` | `store.dirOf(t.taskId)` | `persistGrants(Path grantStoreDir, ...)` |
| `execRootsSandboxed(TaskEntry t)` | 解析 `t.workspaceRoot` 取 externalRoots | `execRootsSandboxed(String workspaceRoot, String subjectId)`——这本来就该在文件权限链，不该在授权决议链宿主 |

`GrantRegistry` 内部 `TaskGrants` 状态按 `subjectId` 分区（Map<String, TaskGrants>），
语义不变：今天 subjectId = taskId，未来 subjectId = workflowId。

`grantStoreDir` 的来源：任务层在构造 `AuthorizationContext` 时填入
`task.taskDir()`（即 `workspaces/<workspaceId>/tasks/<taskId>/`）；未来工作流层填入
工作流数据目录。GrantRegistry 只消费这个 Path，不感知它是什么。

## 8. 新的授权链契约

### 8.1 AuthorizationContext（域中性 record）

```java
package dev.everyagent.plugin.api.permission;

import dev.everyagent.plugin.api.model.EventEmitter;

import java.nio.file.Path;
import java.util.Map;

/**
 * 授权决议上下文 —— 域中性，任务层与工作流层共用。
 *
 * <p>调用方（任务层 PermissionGate / 未来工作流层 gate）负责构造，
 * 把授权决议需要的最小数据集装入。授权链节点只读消费，不感知数据来源是任务还是工作流。
 *
 * @param subjectId     授权状态分区键（今天=taskId；未来=workflowId）
 * @param agentId       发起授权的 agent ID
 * @param grantKey      授权粒度键（路径类按 realpath、动词类按规范化动词）
 * @param prompt        授权请求原文（弹窗 / 审议输入）
 * @param metadata      主体策略标记（unattended / ai-review 等，由调用方填）
 * @param grantStoreDir grants.json 落盘目录（今天=任务数据目录；未来=工作流数据目录）
 * @param events        审计发射端口（域中性 EventEmitter，已绑定上层主体）
 * @param agentFactory  预绑定的 Agent 工厂（静态代理，已闭包 emitter + properties + configId；
 *                      类型仍为 AgentFactory，消费者调短签名 create(agentId, configId)）
 */
public record AuthorizationContext(
        String subjectId,
        String agentId,
        String grantKey,
        String prompt,
        Map<String, Object> metadata,
        Path grantStoreDir,
        EventEmitter events,
        AgentFactory agentFactory) {}
```

### 8.2 AuthorizationHandler（签名更新）

```java
public interface AuthorizationHandler {

    String id();
    float order();

    AuthorizationDecision invoke(AuthorizationContext ctx, AuthorizationChain next) throws Exception;

    record AuthorizationDecision(Type type, String reason) {
        public enum Type { ALLOW, DENY, PASS }
    }
}
```

`AuthorizationRequest` record 删除，被 `AuthorizationContext` 替代。

### 8.3 AuthorizationChain（参数类型更新）

```java
@FunctionalInterface
public interface AuthorizationChain {
    AuthorizationDecision proceed(AuthorizationContext ctx) throws Exception;
}
```

### 8.4 ReviewDecision（从 ai-review 插件下沉到 plugin-api）

`ReviewDecision` 是授权决议的中间产物（ALLOW/DENY/ESCALATE + fallback 标记），
不是 ai-review 插件私有的。下沉到 `permission` 包（或 plugin-api 顶层）。

## 9. TaskInfo 迁回 task 包

`TaskInfo` 从 `permission.TaskInfo` 迁到 `task.TaskInfo`（或直接合并进 `TaskRuntime`，
因为 `TaskRuntime extends TaskInfo`——如果 TaskInfo 没有其他消费者可以直接消除）。

当前 import `permission.TaskInfo` 的文件：

| 文件 | 用途 |
|---|---|
| `task/TaskRuntime.java` | `extends TaskInfo` |
| `task/TaskService.java` | 文档引用 |
| `unattended/UnattendedToolInterceptor.java` | 从 agentProp 取 TaskInfo |
| `unattended/UnattendedSlashProvider.java` | 从 TaskRuntime 取 metadata |
| `aireview/AiReviewSlashProvider.java` | 同上 |
| `sandbox-wsl-ubuntu/NetworkTaskFlag.java` | 同上 |
| `task-input-queue/RestoredQueueContext.java` | 同上 |
| `task-input-queue/InputQueueTest.java` | 测试桩 |
| `worker/WorkerServicesImpl.java` | WorkerServices 实现 |
| `worker/TaskEntry.java` | `implements TaskRuntime`(间接 implements TaskInfo) |

迁移后全部 import 改为 `task.TaskInfo`。`permission` 包零 task 类型引用。

## 10. 外层权限链适配（PermissionContext）

外层权限责任链（`PermissionCheck` 责任链，§7.8 权限责任链表）的 `PermissionContext`
当前携带 `TaskEntry task`，用于：
1. `t.workspaceRoot()` —— 解析工作区（WorkspaceManager）。
2. 透传给 `grants.authorize(t, ...)`。

域中性化后，`PermissionContext` 的 `TaskEntry task` 改为：
- `String workspaceRoot` —— 路径判定用（WorkspaceManager.resolve）。
- `AuthorizationContext authCtx` —— 透传给 GrantRegistry.authorize()。

或者更简洁：`PermissionContext` 携带一个预构造好的 `AuthorizationContext`，
路径/命令链节点需要 workspaceRoot 时单独携带。这一条链的改造
可以独立于决议链先做或后做，不影响决议链契约。

## 11. 调用方适配

### 11.1 PermissionGate（core 门面）

`requirePath(TaskEntry t, ...)` / `requireCommand(TaskEntry t, ...)` 等方法内部
构造 `AuthorizationContext`：

```java
AgentFactory boundFactory = new TaskBoundAgentFactory(
        agentFactory, t.events(), agentProps(t), t.snapshot().configId());

AuthorizationContext authCtx = new AuthorizationContext(
        t.taskId(), agentId, grantKey, prompt, t.metadata(),
        t.taskDir(), t.events(), boundFactory);
grants.authorize(authCtx, roots, execRoots);
```

生命周期委托方法改名：
- `beginRun(String taskId)` → `beginRun(String subjectId)`（语义不变）。
- `untrack(String taskId)` → `untrack(String subjectId)`。
- `extraRoots(String taskId)` → `extraRoots(String subjectId)`。
- `execRoots(String taskId)` → `execRoots(String subjectId)`。
- `execRootsSandboxed(TaskEntry t)` → `execRootsSandboxed(String workspaceRoot, String subjectId)`。

### 11.2 插件 handler

| 插件 | 当前 | 改后 |
|---|---|---|
| UnattendedAuthHandler | `req.task().metadata()` | `ctx.metadata()` |
| AiReviewAuthHandler | `(TaskRuntime) req.task()` + 直接调 `reviewer.review(t, ...)` | `ctx.metadata()` + 调 `reviewer.review(ctx.agentFactory(), ctx.subjectId(), ctx.events(), ctx.grantKey(), ctx.prompt())`（workspaceRoot 可从 ctx 或单独传） |
| HumanAuthorizationHandler | `(TaskEntry) req.task()` 取 taskId | `ctx.subjectId()` |

### 11.3 AiReviewPlugin.activate()

```java
// 当前
AiAuthReviewer reviewer = new AiAuthReviewer(ctx.services().config(), ctx.services().agentFactory());
ctx.registerAuthorizationHandler(new AiReviewAuthHandler(reviewer));

// 改后：reviewer 不再直接依赖 AgentFactory，从 AuthorizationContext 获取（已绑定的）AgentFactory
AiAuthReviewer reviewer = new AiAuthReviewer(ctx.services().config());
ctx.registerAuthorizationHandler(new AiReviewAuthHandler(reviewer));
```

`AiAuthReviewer` 构造不再需要 `AgentFactory`——它从 `ctx.agentFactory()` 取预绑定工厂。
内部逻辑保持不变，只是 agent 创建从 `agentFactory.create(id, configId, t.events(), props)`
改为 `factory.create(id, configId)`。

### 11.4 AiAuthReviewer 改造

```java
// 当前签名
public ReviewDecision review(TaskRuntime t, String grantKey, String prompt)

// 改后签名
public ReviewDecision review(AgentFactory factory, String subjectId,
        String workspaceRoot, EventEmitter events,
        String grantKey, String prompt)
```

内部变化：
- `agentFactory.create(reviewAgentId, configId, t.events(), agentProps)` →
  `factory.create(reviewAgentId, configId)`（绑定信息已在工厂内）。
- `t.events().emit(...)` → `events.emit(...)`（events 从参数传入）。
- `t.taskId()` → `subjectId`（审计字段）。
- `t.workspaceRoot()` → `workspaceRoot`（审议 prompt 注入）。
- `t.snapshot().configId()` → 由 `resolveConfigId()` 内部逻辑决定（review-model
  配置非空则用它，否则传 null 给 `factory.create(id, null)`，由工厂用绑定的默认 configId）。

## 12. SubAgentManager 改造

SubAgentManager 与 AiAuthReviewer 有**完全同构的 AgentFactory 使用模式**（§3.4 已分析），
本次一并改造。

### 12.1 当前耦合

```java
// SubAgentPlugin.activate()
new SubAgentManager(ctx.services().agentFactory(), ...)

// SubAgentManager.buildSubAgent()
private Agent buildSubAgent(TaskRuntime task, String agentId, String title, String input) {
    String configId = task.snapshot().configId();
    Map<String, Object> props = new HashMap<>();
    props.put("taskEntry", task);
    props.put("taskId", task.taskId());
    props.put("workspaceRoot", task.workspaceRoot());
    props.put("configId", configId);
    return agentFactory.create(agentId, configId, task.events(), props) ...
}
```

SubAgentManager 对 `TaskRuntime` 的使用分两类：

| 用途 | 方法 | 改造范围 |
|---|---|---|
| agent 创建（`buildSubAgent`） | `task.snapshot().configId()` + `task.events()` + 构造 props（含 taskEntry） | **本次改造**：用 `TaskBoundAgentFactory` 替代 |
| 任务运行时操作（run/wait/stop/list/awaitAll 等） | `task.agents()`（Map）、`task.events().emit()`、`task.taskId()` | **不改**：SubAgentManager 本身是任务域编排组件，通过 `TaskService.get(taskId)` 获取 `TaskRuntime` 是其本职依赖 |



### 12.3 TaskBoundAgentFactory 放在 worker 内部

`TaskBoundAgentFactory` 放在 worker 内部即可——只有 `TaskEntry`（worker 类）需要 `new`
它。插件通过 `TaskRuntime.agentFactory()` 获取（返回类型是 `AgentFactory` 接口，
插件不需要知道具体实现类）。

**TaskRuntime 接口新增方法**：

```java
// plugin-api，task 包
public interface TaskRuntime extends TaskInfo {
    // ... 现有方法 ...

    /** 已绑定本任务上下文的 Agent 工厂（静态代理，消费者调短签名即可创建 agent）。 */
    AgentFactory agentFactory();
}
```

**TaskEntry 实现**（worker 内部）：

```java
@Override
public AgentFactory agentFactory() {
    return new TaskBoundAgentFactory(agentFactory, events, agentProps(), snapshot().configId());
}
```

**消费侧**：

- **SubAgentManager**（buildSubAgent 时）：`task.agentFactory().create(agentId, configId)`
  ——从 TaskRuntime 取，不需要自己 new，不需要知道 TaskBoundAgentFactory 的存在。
- **AiAuthReviewer**（review 时）：`ctx.agentFactory().create(agentId, configId)`
  ——从 AuthorizationContext 取（PermissionGate 构造 ctx 时调 `task.agentFactory()`
  获取预绑定工厂放入 ctx）。
- **PermissionGate**（构造 AuthorizationContext 时）：`task.agentFactory()` 获取
  预绑定工厂放入 AuthorizationContext。

## 13. 不改的部分

- `AuthorizationChainExecutor`（链折叠逻辑）：只改 record 引用名，逻辑零改。
- `AuthorizationChain`（functional interface）：参数类型改名，逻辑零改。
- `InteractionService`：已经是域中性，零改。
- `EventEmitter` / `EmitEvent`：已经是域中性，零改。
- 外层权限链节点（`WorkspaceAllowCheck` / `MissingPathCheck` / `SkillsReadAllowCheck` /
  `ExternalRootAllowCheck` / `OverBroadRootCheck`）：不直接调授权决议链，
  它们只在路径判定后由 `AuthorizeCheck` 委托给 `GrantRegistry`。这些节点只需
  `PermissionContext` 适配（§10），自身逻辑零改。
- `ReviewDecision`：从 ai-review 插件下沉到 plugin-api，结构不变。
- agent 执行域的 `"taskEntry"` agentProp 约定：不变。这是 agent 执行域的既存约定，
  与授权链解耦后仍然存在（`TaskBoundAgentFactory` 内部使用它）。
- `AgentFactory`（原始接口）：不变。新增两个 default 便捷方法。
  `TaskBoundAgentFactory` 是 `AgentFactory` 的静态代理实现（`implements AgentFactory`）。
- SubAgentManager 的 run/wait/stop/list/awaitAll 等方法：不变（它们操作 TaskRuntime
  是编排本职，不是 agent 创建耦合）。

## 14. 文档同步

ARCHITECTURE.md 需要同步修改的章节：

| 章节 | 改动 |
|---|---|
| §7.8 危险操作授权(PermissionGate) | 授权决议链契约从 `AuthorizationRequest(TaskInfo,...)` 改写为 `AuthorizationContext(subjectId, agentFactory, ...)`；说明域中性设计与工作流复用路径 |
| §7.9 AI 安全审议与无人值守 | 拦截链表更新：AiReviewAuthHandler 从 ctx 取预绑定 AgentFactory 创建审议 agent；说明 AgentFactory 预绑定机制（七层模型同构） |
| line 655 授权决议链对比表 | `invoke(req, next)` 签名更新 |
| line 646 分层与核心边界 | 补充"授权链域中性，task 层往下传预绑定的 AgentFactory + EventEmitter，不传任务域类型" |
| §14.6 沙箱红线 | PermissionGate 边界描述更新（"授权链契约域中性"） |
| §14.0 事件管道分层 | 补充 TaskBoundAgentFactory 是七层模型"上层预绑定能力往下传"的同构实例（静态代理，非新接口类型） |
| D28 | 补充"授权链域中性化"决策 |
| §1.1 设计理念 | 补充"授权链不与任何编排域耦合"原则 |

## 15. 改动清单（代码）

### plugin-api

| 文件 | 动作 |
|---|---|
| `permission/AuthorizationContext.java` | **新建**（域中性 record，含 `AgentFactory agentFactory` 字段） |
| `permission/AuthorizationHandler.java` | `AuthorizationRequest` record 删除，`invoke` 参数改为 `AuthorizationContext` |
| `permission/AuthorizationChain.java` | `proceed` 参数改为 `AuthorizationContext` |
| `permission/ReviewDecision.java` | **新建**（从 ai-review 插件下沉） |
| `permission/TaskInfo.java` | **删除**（迁回 task 包） |
| `agent/AgentFactory.java` | 新增两个 default 便捷方法（`create(agentId, configId)` + `create(agentId)`） |
| `WorkerServices.java` | 新增 `createBoundFactory(emitter, properties, defaultConfigId)` 方法 |
| `task/TaskInfo.java` | **新建**（从 permission 包迁入，内容不变） |
| `task/TaskRuntime.java` | import 改为 `task.TaskInfo` |
| `task/TaskService.java` | import 改为 `task.TaskInfo`（如有） |

### worker

| 文件 | 动作 |
|---|---|
| `agent/TaskBoundAgentFactory.java` | **新建**（`implements AgentFactory`，静态代理，预绑定 emitter/props/configId） |
| `tools/permission/GrantRegistry.java` | `authorize(TaskEntry,...)` → `authorize(AuthorizationContext,...)`；`taskId` → `subjectId`；`store.dirOf()` → `ctx.grantStoreDir()`；生命周期方法参数改名 |
| `tools/permission/AuthorizationChainExecutor.java` | record 引用名更新 |
| `tools/permission/HumanAuthorizationHandler.java` | `(TaskEntry) req.task()` → `ctx.subjectId()` |
| `tools/permission/AuthorizeCheck.java` | `grants.authorize(ctx.task(),...)` → `grants.authorize(ctx.authCtx(),...)` |
| `tools/permission/CommandCheck.java` | 同上（透传 authCtx） |
| `tools/permission/PrivilegeCheck.java` | 同上 |
| `tools/permission/ExternalRootAllowCheck.java` | `ctx.task()` → 适配（取 workspaceRoot） |
| `tools/permission/PermissionContext.java` | `TaskEntry task` → `AuthorizationContext authCtx`（或拆字段） |
| `tools/PermissionGate.java` | 构造 AuthorizationContext + TaskBoundAgentFactory；生命周期委托方法参数改名 |
| `task/TaskEntry.java` | import 改为 `task.TaskInfo` |
| `plugin/WorkerServicesImpl.java` | import 改为 `task.TaskInfo`；实现 `createBoundFactory()` |

### 插件

| 文件 | 动作 |
|---|---|
| `unattended/UnattendedAuthHandler.java` | `req.task().metadata()` → `ctx.metadata()` |
| `unattended/UnattendedToolInterceptor.java` | import 改为 `task.TaskInfo` |
| `unattended/UnattendedSlashProvider.java` | import 改为 `task.TaskInfo` |
| `aireview/AiReviewAuthHandler.java` | `(TaskRuntime) req.task()` → `ctx.agentFactory()` 传给 reviewer（类型为 AgentFactory） |
| `aireview/AiReviewPlugin.java` | 不再向 reviewer 注入 AgentFactory（从 ctx 取） |
| `aireview/AiAuthReviewer.java` | 签名从 `review(TaskRuntime, ...)` 改为 `review(AgentFactory, ...)`（factory 是静态代理实例） |
| `aireview/AiReviewSlashProvider.java` | import 改为 `task.TaskInfo` |
| `subagent/SubAgentManager.java` | `buildSubAgent` 改为用 `ctx.services().createBoundFactory(...)` 构造预绑定工厂，调短签名 `create(agentId, configId)` |
| `subagent/SubAgentPlugin.java` | 无改动（构造参数不变，仍注入通用 AgentFactory） |
| `sandbox-wsl-ubuntu/NetworkTaskFlag.java` | import 改为 `task.TaskInfo` |
| `task-input-queue/RestoredQueueContext.java` | import 改为 `task.TaskInfo` |
| `task-input-queue/InputQueueTest.java` | import 改为 `task.TaskInfo` |

### 测试

所有授权链测试桩中 `implements AuthorizationHandler` 的匿名类 / mock，
签名从 `AuthorizationRequest` 改为 `AuthorizationContext`。

AiAuthReviewerTest 中 mock AgentFactory（验证 `create(agentId, configId)` 短签名被调用）。

SubAgentManager 如有测试桩：mock AgentFactory → 验证短签名 `create(agentId, configId)`
被调用、不再传 emitter/props 四参数。

## 16. 向后兼容

这是 **plugin-api SPI breaking change**（`AuthorizationHandler.invoke` 签名变了、
`AuthorizationRequest` 删除、`AgentFactory` 新增 default 方法、`WorkerServices` 新增方法）。
外部插件如有自定义 `AuthorizationHandler` 实现，需要适配。考虑到当前授权链扩展点
仅由内置插件使用（UnattendedAuthHandler / AiReviewAuthHandler / HumanAuthorizationHandler），
影响面可控。

`InteractionService.ask()` 的 context map 里 `"taskId"` 键名保持不变（前端兼容），
值从 `TaskEntry.taskId` 改为 `AuthorizationContext.subjectId()`——今天两者相等。

`AgentFactory`（原接口）：新增两个 default 便捷方法，不新增独立接口类型。
`TaskBoundAgentFactory` 是 `AgentFactory` 的静态代理实现（`implements AgentFactory`）。
SubAgentManager 本次一并改造（`buildSubAgent` 改用预绑定工厂短签名）。

## 17. 未来工作流复用路径

当工作流层（§13 v2 预留）实现时，它构造自己的 `AuthorizationContext`：

```java
// 未来工作流层（示意）
// 工作流层在 task 层的绑定工厂上再包一层静态代理
AgentFactory wfFactory = new WorkflowBoundAgentFactory(
        taskFactory, wfEmitter, wfProps, wfDefaultConfigId);

AuthorizationContext ctx = new AuthorizationContext(
        workflowId,        // subjectId = workflowId
        agentId,
        grantKey,
        prompt,
        workflowMetadata,  // 工作流自己的策略标记
        wfDataDir,         // 工作流数据目录
        wfEmitter,         // 已绑定工作流的 EventEmitter
        wfFactory);         // 已绑定工作流的 AgentFactory（静态代理，链式套娃）

// 同一条授权链，零改动
grantRegistry.authorize(ctx, roots, execRoots);
```

三个链节点（UnattendedAuthHandler / AiReviewAuthHandler / HumanAuthorizationHandler）
全部零改动复用。`GrantRegistry` 按 `workflowId` 分区授权状态，grants.json 落在
工作流数据目录。审议 agent 由 `wfFactory` 创建——调用 `create(agentId, configId)` 时，
`wfFactory` 先用自己的绑定值兜底，如果自己没覆盖（传 null）则透传到内层
`taskFactory` 用 task 绑定值兜底。审计 trace 进工作流日志。
