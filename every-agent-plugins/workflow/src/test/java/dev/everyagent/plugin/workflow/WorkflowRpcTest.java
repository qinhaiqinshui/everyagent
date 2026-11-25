// ea: RPC 面测试（15 方法各一正一反 + 长轮询三态 + import 重赋 id + run 变量/模型校验；桩注入不依赖 worker，§14.9 红线）
package dev.everyagent.plugin.workflow;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.exception.BadParamsException;
import dev.everyagent.plugin.api.exception.NotFoundException;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.rpc.RpcContext;
import dev.everyagent.plugin.api.rpc.RpcMethod;
import dev.everyagent.plugin.workflow.engine.WorkflowEngine;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EventLine;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.InstanceStatus;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.model.OnError;
import dev.everyagent.plugin.workflow.model.VariableDef;
import dev.everyagent.plugin.workflow.rpc.WorkflowRpc;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

/**
 * 15 个 RPC 方法的参数装配/错误码/长轮询行为测试。错误码对齐经异常类型断言
 * （BadParamsException→BAD_PARAMS / NotFoundException→NOT_FOUND 由 RpcDispatcher 统一映射）；
 * RpcContext 用本地桩（语义与 worker 实现逐字对齐：strParam 缺失/空串抛 BadParams）。
 */
class WorkflowRpcTest {

    @TempDir
    Path home;

    FakeWorkspaces workspaces;
    WorkflowStore store;
    Fakes.FakeAgentFactory factory;
    Fakes.FakeWorkerServices services;
    WorkflowEngine engine;
    WorkflowRpc rpc;

    @BeforeEach
    void setUp() throws Exception {
        workspaces = new FakeWorkspaces(home);
        Files.createDirectories(home.resolve("proj"));
        workspaces.register(home.resolve("proj"), "ws_run");
        factory = new Fakes.FakeAgentFactory();
        services = new Fakes.FakeWorkerServices(workspaces, new Fakes.RealNativeExec(), factory,
                List.of(new ModelConfig("gpt5", "openai", "http://localhost", null, "gpt-5", null)));
        store = new WorkflowStore(workspaces);
        engine = new WorkflowEngine(store, services);
        rpc = new WorkflowRpc(store, engine);
    }

    // ── 调用桩与工具 ──

    /** RpcContext 本地桩（应答捕获；参数读取语义与 worker RpcContext 逐字对齐）。 */
    static final class Call implements RpcContext {

        final JsonNode params;
        JsonNode result;
        String errCode;
        String errMsg;
        boolean answered;

        Call(JsonNode params) {
            this.params = params == null ? Json.obj() : params;
        }

        @Override
        public String strParam(String name) {
            JsonNode n = params.path(name);
            if (n.isMissingNode() || n.isNull() || n.asString().isEmpty()) {
                throw new BadParamsException("缺少参数 " + name);
            }
            return n.asString();
        }

        @Override
        public String optStrParam(String name, String def) {
            JsonNode n = params.path(name);
            return n.isMissingNode() || n.isNull() ? def : n.asString();
        }

        @Override
        public long optLongParam(String name, long def) {
            JsonNode n = params.path(name);
            return n.isMissingNode() || n.isNull() ? def : n.asLong(def);
        }

        @Override
        public void ok(JsonNode result) {
            if (answered) {
                throw new IllegalStateException("同一 reqId 至多一次应答");
            }
            answered = true;
            this.result = result;
        }

        @Override
        public void err(String code, String message) {
            if (answered) {
                throw new IllegalStateException("同一 reqId 至多一次应答");
            }
            answered = true;
            this.errCode = code;
            this.errMsg = message;
        }

        @Override
        public JsonNode params() {
            return params;
        }
    }

    private static Call call(ObjectNode params) {
        return new Call(params);
    }

    private static ObjectNode p() {
        return Json.obj();
    }

    private Definition save(String ws, Object... parts) {
        return store.saveDefinition(ws, DefinitionValidatorTest.def(parts));
    }

    private InstanceMeta runToTerminal(Definition d, Map<String, String> vars) throws InterruptedException {
        Fakes.FakeTaskExec task = new Fakes.FakeTaskExec(home.resolve("workspaces"), "ws_run",
                "task_" + taskSeq.incrementAndGet(), factory);
        InstanceMeta m = engine.start(store.createInstance(d, vars, task.subjectId()), task);
        assertThat(engine.awaitTerminal(m.id(), 20_000)).as("实例终态").isTrue();
        return store.findInstance(m.id()).orElseThrow();
    }

    /** 绑定任务 ExecContext 启动（长轮询/取消/决策等含 AI 节点定义的引擎直启路径）。 */
    private InstanceMeta startBound(Definition d) {
        Fakes.FakeTaskExec task = new Fakes.FakeTaskExec(home.resolve("workspaces"), "ws_run",
                "task_" + taskSeq.incrementAndGet(), factory);
        return engine.start(store.createInstance(d, Map.of(), task.subjectId()), task);
    }

    private final java.util.concurrent.atomic.AtomicInteger taskSeq = new java.util.concurrent.atomic.AtomicInteger();

    private void await(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                fail("超时等待: " + what);
            }
            Thread.sleep(20);
        }
    }

    /** 等待指定节点的 node.started 落盘（长轮询测试的游标锚点——取在它之后才无杂散事件）。 */
    private void awaitNodeStarted(String instanceId, String nodeId) throws InterruptedException {
        await(() -> store.readEvents("ws_run", instanceId, 0, 0).stream()
                .anyMatch(e -> "node.started".equals(e.event())
                        && nodeId.equals(e.payload().path("nodeId").asString())),
                "node.started " + nodeId);
    }

    /** 当前事件尾 seq（单次读，防两读竞态）。 */
    private String tailSeq(String instanceId) {
        var all = store.readEvents("ws_run", instanceId, 0, 0);
        return all.get(all.size() - 1).seq();
    }

    /** 记录 registerRpcMethod 调用的 WorkerPluginContext 动态代理（不依赖 worker 实现）。 */
    private static WorkerPluginContext recordingContext(Map<String, RpcMethod> registered) {
        return (WorkerPluginContext) Proxy.newProxyInstance(
                WorkerPluginContext.class.getClassLoader(),
                new Class<?>[]{WorkerPluginContext.class},
                (proxy, method, args) -> {
                    if ("registerRpcMethod".equals(method.getName())) {
                        registered.put((String) args[0], (RpcMethod) args[1]);
                    }
                    return null;
                });
    }

    // ══════════════════════════ 注册面 ══════════════════════════

    @Test
    void registerAllRegistersExactlyFifteenMethods() {
        Map<String, RpcMethod> registered = new LinkedHashMap<>();
        rpc.registerAll(recordingContext(registered));
        assertThat(registered.keySet()).containsExactlyElementsOf(rpc.methods().keySet());
        assertThat(registered).hasSize(15);
        assertThat(registered.keySet()).containsExactly(
                "workflow.def.list", "workflow.def.get", "workflow.def.save",
                "workflow.def.delete", "workflow.def.export", "workflow.def.import",
                "workflow.run", "workflow.instance.list", "workflow.instance.get",
                "workflow.instance.runLog", "workflow.instance.events",
                "workflow.instance.cancel", "workflow.instance.delete",
                "workflow.instance.decide", "workflow.instance.dataDir");
    }

    // ══════════════════════════ def.list ══════════════════════════

    @Test
    void defListReturnsSummariesSortedWithFilterAndMissingFlag() throws Exception {
        Definition a = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c", "command").command("echo hi"));
        Thread.sleep(5);
        // ws_other 未在注册表登记 → workspaceMissing 标记
        Definition b = save("ws_other", DefinitionValidatorTest.node("s", "start"));

        Call all = call(p());
        rpc.defList(all);
        assertThat(all.errCode).isNull();
        List<JsonNode> defs = new ArrayList<>();
        all.result.path("defs").forEach(defs::add);
        assertThat(defs).hasSize(2);
        // updatedAt 倒序（b 后保存）
        assertThat(defs.get(0).path("id").asString()).isEqualTo(b.id());
        assertThat(defs.get(1).path("id").asString()).isEqualTo(a.id());
        // 摘要字段齐备；不含全图（无 nodes/edges 键）
        assertThat(defs.get(1).path("name").asString()).isEqualTo("测试定义");
        assertThat(defs.get(1).path("nodeCount").asInt()).isEqualTo(2);
        assertThat(defs.get(1).path("workspaceId").asString()).isEqualTo("ws_run");
        assertThat(defs.get(1).path("workspaceMissing").asBoolean()).isFalse();
        assertThat(defs.get(1).has("nodes")).isFalse();
        // 缺失标记：ws_other 不在注册表
        assertThat(defs.get(0).path("workspaceMissing").asBoolean()).isTrue();

        Call filtered = call(p().put("workspace", "ws_run"));
        rpc.defList(filtered);
        assertThat(filtered.result.path("defs")).hasSize(1);
        assertThat(filtered.result.path("defs").get(0).path("id").asString()).isEqualTo(a.id());
    }

    @Test
    void defListUnknownWorkspaceIsEmpty() {
        Call c = call(p().put("workspace", "ws_nope"));
        rpc.defList(c);
        assertThat(c.result.path("defs")).isEmpty();
    }

    // ══════════════════════════ def.get ══════════════════════════

    @Test
    void defGetReturnsFullDefinition() {
        Definition d = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        Call c = call(p().put("workspace", "ws_run").put("id", d.id()));
        rpc.defGet(c);
        JsonNode def = c.result.path("definition");
        assertThat(def.path("id").asString()).isEqualTo(d.id());
        assertThat(def.path("workspaceId").asString()).isEqualTo("ws_run");
        assertThat(def.path("nodes")).hasSize(2);
        assertThat(def.path("edges")).hasSize(1);
    }

    @Test
    void defGetMissingIdOrWorkspaceRejected() {
        Definition d = save("ws_run", DefinitionValidatorTest.node("s", "start"));
        // NOT_FOUND：挂靠不符/不存在
        assertThatThrownBy(() -> rpc.defGet(call(p().put("workspace", "ws_other").put("id", d.id()))))
                .isInstanceOf(NotFoundException.class).hasMessageContaining("定义不存在");
        assertThatThrownBy(() -> rpc.defGet(call(p().put("workspace", "ws_run").put("id", "wf_nope"))))
                .isInstanceOf(NotFoundException.class);
        // BAD_PARAMS：缺 workspace 参数
        assertThatThrownBy(() -> rpc.defGet(call(p().put("id", d.id()))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("workspace");
    }

    // ══════════════════════════ def.save ══════════════════════════

    @Test
    void defSaveUpsertsWithParamWorkspaceAndFreshId() throws Exception {
        // 新建：draft 无 id → 赋 wf id；workspaceId 以参数为准回写（draft 里是 w_test）
        Definition draft = DefinitionValidatorTest.def(DefinitionValidatorTest.node("s", "start"));
        Call c = call(p().put("workspace", "ws_run").set("definition", Json.toJson(draft)));
        rpc.defSave(c);
        String id = c.result.path("id").asString();
        assertThat(id).startsWith("wf_");
        Definition saved = store.getDefinition("ws_run", id).orElseThrow();
        assertThat(saved.workspaceId()).isEqualTo("ws_run");

        // upsert：同 id 再保存改名
        Thread.sleep(5);
        Definition renamed = new Definition(saved.schemaVersion(), id, "改名后的定义", saved.description(),
                "w_elsewhere", saved.createdAt(), saved.updatedAt(), saved.variables(), saved.agentConfigId(),
                saved.onError(),
                saved.supervisor(), saved.nodes(), saved.edges());
        Call c2 = call(p().put("workspace", "ws_run").set("definition", Json.toJson(renamed)));
        rpc.defSave(c2);
        assertThat(c2.result.path("id").asString()).isEqualTo(id);
        Definition updated = store.getDefinition("ws_run", id).orElseThrow();
        assertThat(updated.name()).isEqualTo("改名后的定义");
        assertThat(updated.workspaceId()).isEqualTo("ws_run");
        assertThat(updated.updatedAt()).isGreaterThan(saved.updatedAt());
    }

    @Test
    void defSaveValidationErrorsListedAndParamsRequired() {
        // 全量校验失败 → BAD_PARAMS 带逐项错误（缺 start + 边引用不存在）
        Definition bad = DefinitionValidatorTest.def(
                DefinitionValidatorTest.node("n1", "command").command("echo hi"),
                DefinitionValidatorTest.edge("e1", "n1", "nX"));
        assertThatThrownBy(() -> rpc.defSave(call(p().put("workspace", "ws_run")
                .set("definition", Json.toJson(bad)))))
                .isInstanceOf(BadParamsException.class)
                .satisfies(e -> {
                    String msg = e.getMessage();
                    assertThat(msg).contains("start");
                    assertThat(msg).contains("不存在(to)");
                });
        // 缺 workspace 参数 → BAD_PARAMS
        assertThatThrownBy(() -> rpc.defSave(call(p().set("definition", Json.toJson(bad)))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("workspace");
        // 缺 definition 参数 → BAD_PARAMS
        assertThatThrownBy(() -> rpc.defSave(call(p().put("workspace", "ws_run"))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("definition");
        // definition 非对象 → BAD_PARAMS
        assertThatThrownBy(() -> rpc.defSave(call(p().put("workspace", "ws_run")
                .put("definition", "不是对象"))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("对象");
    }

    // ══════════════════════════ def.delete ══════════════════════════

    @Test
    void defDeleteKeepsInstancesAndMissingRejected() throws Exception {
        Definition d = save("ws_run", DefinitionValidatorTest.node("s", "start"));
        InstanceMeta m = runToTerminal(d, Map.of());
        Call c = call(p().put("workspace", "ws_run").put("id", d.id()));
        rpc.defDelete(c);
        assertThat(c.result.path("ok").asBoolean()).isTrue();
        assertThat(store.findDefinition(d.id())).isEmpty();
        // 有历史实例也允许：实例持快照不受影响
        assertThat(store.findInstance(m.id())).isPresent();
        assertThat(store.findInstance(m.id()).orElseThrow().definitionSnapshot().id()).isEqualTo(d.id());
        // 再删 → NOT_FOUND
        assertThatThrownBy(() -> rpc.defDelete(call(p().put("workspace", "ws_run").put("id", d.id()))))
                .isInstanceOf(NotFoundException.class);
    }

    // ══════════════════════════ def.export / def.import ══════════════════════════

    @Test
    void defExportReturnsRawAndMissingRejected() {
        Definition d = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer").modelConfigId("gpt5").systemPrompt("你是作者").input("写点东西"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        Call c = call(p().put("workspace", "ws_run").put("id", d.id()));
        rpc.defExport(c);
        JsonNode exported = c.result.path("definition");
        assertThat(exported.path("id").asString()).isEqualTo(d.id());
        assertThat(exported.path("nodes")).hasSize(2);
        assertThat(exported.path("nodes").get(1).path("modelConfigId").asString()).isEqualTo("gpt5");
        // 与 def.get 同源（原样往返）
        Call g = call(p().put("workspace", "ws_run").put("id", d.id()));
        rpc.defGet(g);
        assertThat(exported).isEqualTo(g.result.path("definition"));
        assertThatThrownBy(() -> rpc.defExport(call(p().put("workspace", "ws_run").put("id", "wf_nope"))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void defImportReassignsIdAndLandsInTargetWorkspace() throws Exception {
        Definition original = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer").systemPrompt("你是作者").input("内容"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        String raw = store.exportDefinition("ws_run", original.id()).orElseThrow();

        // 导入同工作区：重赋顶层 id，原定义保留
        Thread.sleep(5);
        Call c = call(p().put("workspace", "ws_run").set("definition", Json.parse(raw)));
        rpc.defImport(c);
        String newId = c.result.path("id").asString();
        assertThat(newId).startsWith("wf_").isNotEqualTo(original.id());
        Definition imported = store.getDefinition("ws_run", newId).orElseThrow();
        assertThat(imported.nodesOrEmpty()).hasSize(2);
        assertThat(imported.nodesOrEmpty().get(1).agent()).isEqualTo("writer");
        assertThat(store.findDefinition(original.id())).isPresent();

        // 导入另一工作区：落入指定工作区
        Files.createDirectories(home.resolve("proj2"));
        workspaces.register(home.resolve("proj2"), "ws_two");
        Call c2 = call(p().put("workspace", "ws_two").set("definition", Json.parse(raw)));
        rpc.defImport(c2);
        String id2 = c2.result.path("id").asString();
        assertThat(id2).isNotEqualTo(newId).isNotEqualTo(original.id());
        assertThat(store.getDefinition("ws_two", id2)).isPresent();
        assertThat(store.getDefinition("ws_run", id2)).isEmpty();
    }

    @Test
    void defImportValidatesSameAsSave() {
        Definition bad = DefinitionValidatorTest.def(DefinitionValidatorTest.node("n1", "command")
                .command("echo hi"));
        assertThatThrownBy(() -> rpc.defImport(call(p().put("workspace", "ws_run")
                .set("definition", Json.toJson(bad)))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("start");
        assertThatThrownBy(() -> rpc.defImport(call(p().put("workspace", "ws_run"))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("definition");
    }

    // ══════════════════════════ workflow.run ══════════════════════════

    @Test
    void runCreatesInstanceAndStartsEngine() throws Exception {
        // RPC 直启兼容通道：无 AI 节点定义照常直启（含 AI 节点见下方拒绝测试）
        Definition d = store.saveDefinition("ws_run", DefinitionValidatorTest.withVariables(
                DefinitionValidatorTest.def(
                        DefinitionValidatorTest.node("s", "start"),
                        DefinitionValidatorTest.node("c", "command").command("echo 《{{title}}》"),
                        DefinitionValidatorTest.edge("e1", "s", "c")),
                new VariableDef("title", null, true, null, null)));

        Call c = call(p().put("workspace", "ws_run").put("definitionId", d.id())
                .set("variables", Json.obj().put("title", "星际牧羊人")));
        rpc.run(c);
        String instanceId = c.result.path("instanceId").asString();
        assertThat(instanceId).startsWith("wi_");
        assertThat(engine.awaitTerminal(instanceId, 20_000)).isTrue();
        InstanceMeta m = store.findInstance(instanceId).orElseThrow();
        assertThat(m.status()).isEqualTo(InstanceStatus.DONE);
        assertThat(m.variablesOrEmpty()).containsEntry("title", "星际牧羊人");
        assertThat(m.definitionId()).isEqualTo(d.id());
        assertThat(m.originTaskId()).isNull();
    }

    @Test
    void runRejectsAiDefinitionWithoutTaskBinding() {
        factory.script("writer", key -> Fakes.Script.echo("ok"));
        Definition d = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        // 含 AI 节点而无任务绑定 → 拒绝并引导经任务运行；已建实例停留在 created（从未启动）
        assertThatThrownBy(() -> rpc.run(call(p().put("workspace", "ws_run")
                .put("definitionId", d.id()))))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("AI")
                .hasMessageContaining("任务");
        assertThat(store.listInstances("ws_run", d.id()))
                .allSatisfy(m -> assertThat(m.status()).isEqualTo(InstanceStatus.CREATED));
        assertThat(store.readEvents("ws_run",
                store.listInstances("ws_run", d.id()).get(0).id(), 0, 0))
                .extracting(EventLine::event).containsExactly("wf.created");
    }

    @Test
    void runValidatesRequiredVariablesAndDefaultsSatisfy() throws Exception {
        Definition d = store.saveDefinition("ws_run", DefinitionValidatorTest.withVariables(
                DefinitionValidatorTest.def(
                        DefinitionValidatorTest.node("s", "start"),
                        DefinitionValidatorTest.node("c", "command").command("echo {{opt}}"),
                        DefinitionValidatorTest.edge("e1", "s", "c")),
                new VariableDef("must", null, true, null, null),
                new VariableDef("opt", null, true, "默认值", null)));
        // 必填缺失（opt 有默认可满足）→ BAD_PARAMS 列出缺失名
        assertThatThrownBy(() -> rpc.run(call(p().put("workspace", "ws_run").put("definitionId", d.id())
                .set("variables", Json.obj().put("opt", "v")))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("must");
        // 默认值满足 required：只给 must → 正常跑
        factory.script("writer", key -> Fakes.Script.echo("ok"));
        Call c = call(p().put("workspace", "ws_run").put("definitionId", d.id())
                .set("variables", Json.obj().put("must", "x")));
        rpc.run(c);
        assertThat(engine.awaitTerminal(c.result.path("instanceId").asString(), 20_000)).isTrue();
    }

    @Test
    void runValidatesModelConfigsAndParams() {
        Definition bad = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer").modelConfigId("gpt4-nope"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        // 模型配置不存在（services.modelConfigs 只有 gpt5）→ BAD_PARAMS 带定位
        assertThatThrownBy(() -> rpc.run(call(p().put("workspace", "ws_run")
                .put("definitionId", bad.id()))))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("模型配置不存在")
                .hasMessageContaining("gpt4-nope");
        // 节点级覆盖同样校验
        Definition badNode = store.saveDefinition("ws_run", new Definition(null, null, "测试定义",
                null, "ws_run", null, null, null, null, null, null,
                List.of(DefinitionValidatorTest.node("s", "start").def,
                        new NodeDef("a", "ai", "a", 0.0, 0.0, null, null, null, "b",
                                "missing-cfg", null, null, null)),
                List.of(DefinitionValidatorTest.edge("e1", "s", "a"))));
        assertThatThrownBy(() -> rpc.run(call(p().put("workspace", "ws_run")
                .put("definitionId", badNode.id()))))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("missing-cfg");
        // 定义级主 agent 配置（agentConfigId）同样校验
        Definition badMain = store.saveDefinition("ws_run", DefinitionValidatorTest.withAgentConfigId(
                DefinitionValidatorTest.def(
                        DefinitionValidatorTest.node("s", "start"),
                        DefinitionValidatorTest.node("a", "ai").agent("writer"),
                        DefinitionValidatorTest.edge("e1", "s", "a")), "main-nope"));
        assertThatThrownBy(() -> rpc.run(call(p().put("workspace", "ws_run")
                .put("definitionId", badMain.id()))))
                .isInstanceOf(BadParamsException.class)
                .hasMessageContaining("主 agent")
                .hasMessageContaining("main-nope");
        // 定义不存在 → NOT_FOUND
        assertThatThrownBy(() -> rpc.run(call(p().put("workspace", "ws_run")
                .put("definitionId", "wf_nope"))))
                .isInstanceOf(NotFoundException.class);
        // 缺 workspace / definitionId → BAD_PARAMS
        assertThatThrownBy(() -> rpc.run(call(p().put("definitionId", bad.id()))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("workspace");
        assertThatThrownBy(() -> rpc.run(call(p().put("workspace", "ws_run"))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("definitionId");
        // variables 非对象 → BAD_PARAMS
        assertThatThrownBy(() -> rpc.run(call(p().put("workspace", "ws_run")
                .put("definitionId", bad.id()).put("variables", "x"))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("variables");
        // 变量值非标量 → BAD_PARAMS
        assertThatThrownBy(() -> rpc.run(call(p().put("workspace", "ws_run")
                .put("definitionId", bad.id())
                .set("variables", Json.obj().set("a", Json.obj().put("x", 1))))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("标量");
    }

    // ══════════════════════════ instance.list ══════════════════════════

    @Test
    void instanceListFiltersProjectsAndMarksMissingWorkspace() throws Exception {
        factory.script("writer", key -> Fakes.Script.echo("ok"));
        Definition d1 = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        Definition d2 = save("ws_run", DefinitionValidatorTest.node("s2", "start"));
        String id1 = runToTerminal(d1, Map.of()).id();
        Thread.sleep(5);
        String id2 = runToTerminal(d2, Map.of()).id();
        // 未注册工作区的实例（store 直建，列表打缺失标记）；带运行变量（列表摘要应透出）
        Definition other = save("ws_other", DefinitionValidatorTest.node("s", "start"));
        String id3 = store.createInstance(other, Map.of("mode", "quick", "lang", "zh")).id();

        Call all = call(p());
        rpc.instanceList(all);
        List<JsonNode> items = new ArrayList<>();
        all.result.path("instances").forEach(items::add);
        assertThat(items).hasSize(3);
        // createdAt 倒序：id3（最后创建）在最前
        assertThat(items.get(0).path("id").asString()).isEqualTo(id3);
        assertThat(items.get(0).path("workspaceMissing").asBoolean()).isTrue();
        // 运行变量非空才带：id3 透出 name→value，空变量的 id1/id2 不带该字段
        assertThat(items.get(0).path("variables").path("mode").asString()).isEqualTo("quick");
        assertThat(items.get(0).path("variables").path("lang").asString()).isEqualTo("zh");
        JsonNode done = items.stream()
                .filter(n -> n.path("id").asString().equals(id1)).findFirst().orElseThrow();
        assertThat(done.path("definitionId").asString()).isEqualTo(d1.id());
        assertThat(done.path("definitionName").asString()).isEqualTo("测试定义");
        assertThat(done.path("workspaceId").asString()).isEqualTo("ws_run");
        assertThat(done.path("status").asString()).isEqualTo("done");
        assertThat(done.path("endedAt").asLong()).isPositive();
        assertThat(done.path("durationMs").asLong()).isPositive();
        assertThat(done.path("nodeStates")).isNotEmpty();
        assertThat(done.path("variables").isMissingNode()).isTrue();
        assertThat(done.path("workspaceMissing").asBoolean()).isFalse();

        // definitionId 过滤
        Call byDef = call(p().put("definitionId", d1.id()));
        rpc.instanceList(byDef);
        assertThat(byDef.result.path("instances")).hasSize(1);
        assertThat(byDef.result.path("instances").get(0).path("id").asString()).isEqualTo(id1);
        // workspace 过滤
        Call byWs = call(p().put("workspace", "ws_other"));
        rpc.instanceList(byWs);
        assertThat(byWs.result.path("instances")).hasSize(1);
        assertThat(byWs.result.path("instances").get(0).path("id").asString()).isEqualTo(id3);
    }

    @Test
    void instanceListUnknownFilterIsEmpty() {
        Call c = call(p().put("definitionId", "wf_nope"));
        rpc.instanceList(c);
        assertThat(c.result.path("instances")).isEmpty();
    }

    // ══════════════════════════ instance.get ══════════════════════════

    @Test
    void instanceGetReturnsFullMetaAndMissingRejected() throws Exception {
        Definition d = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        factory.script("writer", key -> Fakes.Script.echo("ok"));
        InstanceMeta m = runToTerminal(d, Map.of("k", "v"));
        Call c = call(p().put("instanceId", m.id()));
        rpc.instanceGet(c);
        JsonNode instance = c.result.path("instance");
        assertThat(instance.path("id").asString()).isEqualTo(m.id());
        assertThat(instance.path("status").asString()).isEqualTo("done");
        assertThat(instance.path("definitionSnapshot").path("nodes")).hasSize(2);
        assertThat(instance.path("variables").path("k").asString()).isEqualTo("v");
        assertThat(instance.path("nodeStates")).isNotEmpty();

        assertThatThrownBy(() -> rpc.instanceGet(call(p().put("instanceId", "wi_nope"))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> rpc.instanceGet(call(p())))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("instanceId");
    }

    // ══════════════════════════ instance.runLog ══════════════════════════

    @Test
    void runLogAscendingWithNextRunNoAndMissingRejected() throws Exception {
        Definition d = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("c", "command").command("echo out"),
                DefinitionValidatorTest.node("a", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "c"),
                DefinitionValidatorTest.edge("e2", "c", "a"));
        factory.script("writer", key -> Fakes.Script.echo("ok"));
        InstanceMeta m = runToTerminal(d, Map.of());

        Call c = call(p().put("instanceId", m.id()));
        rpc.instanceRunLog(c);
        List<JsonNode> runs = new ArrayList<>();
        c.result.path("runs").forEach(runs::add);
        assertThat(runs).hasSize(3);
        int prev = 0;
        for (JsonNode r : runs) {
            assertThat(r.path("runNo").asInt()).isGreaterThan(prev);
            prev = r.path("runNo").asInt();
        }
        assertThat(runs.get(0).path("nodeId").asString()).isEqualTo("s");
        assertThat(runs.get(1).path("output").path("stdout").asString()).contains("out");
        assertThat(runs.get(2).path("output").path("text").asString()).isEqualTo("ok");
        assertThat(c.result.path("nextRunNo").asInt()).isEqualTo(4);

        assertThatThrownBy(() -> rpc.instanceRunLog(call(p().put("instanceId", "wi_nope"))))
                .isInstanceOf(NotFoundException.class);
    }

    // ══════════════════════════ instance.events ══════════════════════════

    @Test
    void eventsFullSequenceStringSeqCursorAndLimit() throws Exception {
        Definition d = save("ws_run", DefinitionValidatorTest.node("s", "start"));
        InstanceMeta m = runToTerminal(d, Map.of());

        Call c = call(p().put("instanceId", m.id()));
        rpc.instanceEvents(c);
        List<JsonNode> events = new ArrayList<>();
        c.result.path("events").forEach(events::add);
        assertThat(events.size()).isGreaterThanOrEqualTo(5); // created/status/started/output/done/status
        long prevSeq = 0;
        for (JsonNode e : events) {
            // seq 一律字符串（wire 纪律）
            assertThat(e.path("seq").isString()).as("seq 必须是字符串").isTrue();
            assertThat(Long.parseLong(e.path("seq").asString())).isGreaterThan(prevSeq);
            prevSeq = Long.parseLong(e.path("seq").asString());
            assertThat(e.path("event").asString()).isNotEmpty();
        }
        String tail = events.get(events.size() - 1).path("seq").asString();
        assertThat(c.result.path("nextSeq").asString()).isEqualTo(tail);

        // afterSeq 游标（字符串入参）：只取其后事件
        String mid = events.get(2).path("seq").asString();
        Call c2 = call(p().put("instanceId", m.id()).put("afterSeq", mid));
        rpc.instanceEvents(c2);
        List<JsonNode> tailEvents = new ArrayList<>();
        c2.result.path("events").forEach(tailEvents::add);
        assertThat(tailEvents).hasSize(events.size() - 3);
        assertThat(tailEvents.get(0).path("seq").asString())
                .isEqualTo(events.get(3).path("seq").asString());

        // 尾后查询：空批 + 游标不动
        Call c3 = call(p().put("instanceId", m.id()).put("afterSeq", tail)
                .put("waitMs", 0));
        rpc.instanceEvents(c3);
        assertThat(c3.result.path("events")).isEmpty();
        assertThat(c3.result.path("nextSeq").asString()).isEqualTo(tail);

        // limit 截断：nextSeq=截断批尾（未过滤推进游标——客户端续拉不丢不重）
        Call c4 = call(p().put("instanceId", m.id()).put("limit", 2));
        rpc.instanceEvents(c4);
        assertThat(c4.result.path("events")).hasSize(2);
        assertThat(c4.result.path("nextSeq").asString())
                .isEqualTo(events.get(1).path("seq").asString());

        assertThatThrownBy(() -> rpc.instanceEvents(call(p().put("instanceId", "wi_nope"))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> rpc.instanceEvents(call(p())))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("instanceId");
    }

    @Test
    void eventsLongPollEmptyWaitsUntilTimeoutAndKeepsCursor() throws Exception {
        factory.script("writer", key -> Fakes.Script.delayed("慢产出", 1500));
        Definition d = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        InstanceMeta m = startBound(d);
        // 游标锚点=writer 节点自身的 node.started（此后到节点完成前无新事件）
        awaitNodeStarted(m.id(), "a");
        String tail = tailSeq(m.id());

        long t0 = System.currentTimeMillis();
        Call c = call(p().put("instanceId", m.id()).put("afterSeq", tail).put("waitMs", 700));
        rpc.instanceEvents(c);
        long elapsed = System.currentTimeMillis() - t0;
        assertThat(c.result.path("events")).isEmpty();
        assertThat(c.result.path("nextSeq").asString()).isEqualTo(tail);
        // 空批等待 ≈ waitMs（引擎 200ms 轮询粒度容差）后返回，而非立即/永不
        assertThat(elapsed).isBetween(550L, 3500L);
        assertThat(engine.awaitTerminal(m.id(), 20_000)).isTrue();
    }

    @Test
    void eventsLongPollReturnsEarlyOnArrival() throws Exception {
        factory.script("writer", key -> Fakes.Script.delayed("慢产出", 1200));
        Definition d = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        InstanceMeta m = startBound(d);
        // 游标锚点=writer 节点自身的 node.started（其后只可能出现 node.output/wf.done/wf.status）
        awaitNodeStarted(m.id(), "a");
        String tail = tailSeq(m.id());

        // waitMs 远大于节点剩余时长：事件到达（node.output/wf.done）应提前唤醒
        long t0 = System.currentTimeMillis();
        Call c = call(p().put("instanceId", m.id()).put("afterSeq", tail).put("waitMs", 12_000));
        rpc.instanceEvents(c);
        long elapsed = System.currentTimeMillis() - t0;
        List<JsonNode> events = new ArrayList<>();
        c.result.path("events").forEach(events::add);
        assertThat(events).isNotEmpty();
        assertThat(events.stream().map(e -> e.path("event").asString()).toList())
                .contains("node.output", "wf.done");
        assertThat(elapsed).isLessThan(10_000L);
    }

    @Test
    void eventsTerminalInstanceReturnsImmediately() throws Exception {
        Definition d = save("ws_run", DefinitionValidatorTest.node("s", "start"));
        InstanceMeta m = runToTerminal(d, Map.of());
        String tail = tailSeq(m.id());
        long t0 = System.currentTimeMillis();
        Call c = call(p().put("instanceId", m.id()).put("afterSeq", tail).put("waitMs", 8_000));
        rpc.instanceEvents(c);
        assertThat(System.currentTimeMillis() - t0).isLessThan(2_000L);
        assertThat(c.result.path("events")).isEmpty();
        assertThat(c.result.path("nextSeq").asString()).isEqualTo(tail);
    }

    @Test
    void waitMsClampedToCeiling() {
        assertThat(WorkflowRpc.clampWaitMs(99_999)).isEqualTo(WorkflowRpc.MAX_WAIT_MS);
        assertThat(WorkflowRpc.clampWaitMs(-5)).isZero();
        assertThat(WorkflowRpc.clampWaitMs(800)).isEqualTo(800);
    }

    // ══════════════════════════ instance.cancel ══════════════════════════

    @Test
    void cancelRunningInstanceAndRejectTerminalOrMissing() throws Exception {
        factory.script("writer", key -> Fakes.Script.delayed("慢产出", 5000));
        Definition d = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        InstanceMeta m = startBound(d);

        Call c = call(p().put("instanceId", m.id()));
        rpc.instanceCancel(c);
        assertThat(c.result.path("ok").asBoolean()).isTrue();
        assertThat(engine.awaitTerminal(m.id(), 20_000)).isTrue();
        assertThat(store.findInstance(m.id()).orElseThrow().status()).isEqualTo(InstanceStatus.CANCELLED);

        // 已终态 → BAD_PARAMS；未知 → NOT_FOUND；缺参 → BAD_PARAMS
        assertThatThrownBy(() -> rpc.instanceCancel(call(p().put("instanceId", m.id()))))
                .isInstanceOf(BadParamsException.class);
        assertThatThrownBy(() -> rpc.instanceCancel(call(p().put("instanceId", "wi_nope"))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> rpc.instanceCancel(call(p())))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("instanceId");
    }

    // ══════════════════════════ instance.delete ══════════════════════════

    @Test
    void deleteTerminalInstanceRemovesDirectoryAndIndex() throws Exception {
        Definition d = save("ws_run", DefinitionValidatorTest.node("s", "start"));
        InstanceMeta m = runToTerminal(d, Map.of());
        Path dir = home.resolve("workspaces").resolve("ws_run").resolve("workflows")
                .resolve("instances").resolve(m.id());
        assertThat(dir).exists();

        Call c = call(p().put("instanceId", m.id()));
        rpc.instanceDelete(c);
        assertThat(c.result.path("ok").asBoolean()).isTrue();
        assertThat(store.findInstance(m.id())).isEmpty();
        assertThat(dir).doesNotExist();
        // 删后查询 → NOT_FOUND
        assertThatThrownBy(() -> rpc.instanceDelete(call(p().put("instanceId", m.id()))))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void deleteRunningInstanceRejected() throws Exception {
        factory.script("writer", key -> Fakes.Script.delayed("慢产出", 5000));
        Definition d = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        InstanceMeta m = startBound(d);
        assertThatThrownBy(() -> rpc.instanceDelete(call(p().put("instanceId", m.id()))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("拒绝删除");
        // 取消后（终态）可删
        engine.cancel(m.id(), "test");
        assertThat(engine.awaitTerminal(m.id(), 20_000)).isTrue();
        Call c = call(p().put("instanceId", m.id()));
        rpc.instanceDelete(c);
        assertThat(c.result.path("ok").asBoolean()).isTrue();
    }

    // ══════════════════════════ instance.decide ══════════════════════════

    @Test
    void decideWakesWaitingUserInstance() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        factory.script("writer", key -> calls.incrementAndGet() == 1
                ? Fakes.Script.failing(new RuntimeException("第一次失败"))
                : Fakes.Script.echo("重试成功"));
        Definition d = store.saveDefinition("ws_run", new Definition(null, null, "测试定义", null,
                "ws_run", null, null, null, null, OnError.ASK_USER, null,
                List.of(DefinitionValidatorTest.node("s", "start").def,
                        DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10).def,
                        DefinitionValidatorTest.node("t", "ai").agent("tail").maxRuns(10).def),
                List.of(DefinitionValidatorTest.edge("e1", "s", "n"),
                        DefinitionValidatorTest.edge("e2", "n", "t"))));
        InstanceMeta m = startBound(d);
        await(() -> store.findInstance(m.id()).orElseThrow().status() == InstanceStatus.WAITING_USER,
                "waiting-user");
        InstanceMeta parked = store.findInstance(m.id()).orElseThrow();
        assertThat(parked.pendingDecision()).isNotNull();

        Call c = call(p().put("instanceId", m.id())
                .put("runNo", parked.pendingDecision().runNo())
                .put("decision", "retry"));
        rpc.instanceDecide(c);
        assertThat(c.result.path("ok").asBoolean()).isTrue();
        assertThat(engine.awaitTerminal(m.id(), 20_000)).isTrue();
        assertThat(store.findInstance(m.id()).orElseThrow().status()).isEqualTo(InstanceStatus.DONE);
    }

    @Test
    void decideRejectsMismatchAndBadParams() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        factory.script("writer", key -> calls.incrementAndGet() == 1
                ? Fakes.Script.failing(new RuntimeException("失败"))
                : Fakes.Script.echo("ok"));
        Definition d = store.saveDefinition("ws_run", new Definition(null, null, "测试定义", null,
                "ws_run", null, null, null, null, OnError.ASK_USER, null,
                List.of(DefinitionValidatorTest.node("s", "start").def,
                        DefinitionValidatorTest.node("n", "ai").agent("writer").maxRuns(10).def),
                List.of(DefinitionValidatorTest.edge("e1", "s", "n"))));
        InstanceMeta m = startBound(d);
        await(() -> store.findInstance(m.id()).orElseThrow().status() == InstanceStatus.WAITING_USER,
                "waiting-user");
        InstanceMeta parked = store.findInstance(m.id()).orElseThrow();
        int runNo = parked.pendingDecision().runNo();

        // runNo 不匹配 → BAD_PARAMS
        assertThatThrownBy(() -> rpc.instanceDecide(call(p().put("instanceId", m.id())
                .put("runNo", runNo + 100).put("decision", "retry"))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("不匹配");
        // 非法 decision → BAD_PARAMS
        assertThatThrownBy(() -> rpc.instanceDecide(call(p().put("instanceId", m.id())
                .put("runNo", runNo).put("decision", "自创动作"))))
                .isInstanceOf(BadParamsException.class);
        // 缺 runNo / decision / instanceId → BAD_PARAMS
        assertThatThrownBy(() -> rpc.instanceDecide(call(p().put("instanceId", m.id())
                .put("decision", "retry"))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("runNo");
        assertThatThrownBy(() -> rpc.instanceDecide(call(p().put("instanceId", m.id())
                .put("runNo", runNo))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("decision");
        assertThatThrownBy(() -> rpc.instanceDecide(call(p().put("runNo", runNo)
                .put("decision", "retry"))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("instanceId");
        // retryWithInput 缺 input → BAD_PARAMS（引擎校验）
        assertThatThrownBy(() -> rpc.instanceDecide(call(p().put("instanceId", m.id())
                .put("runNo", runNo).put("decision", "retryWithInput"))))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("input");
        // 收口：正确决策
        Call ok = call(p().put("instanceId", m.id()).put("runNo", runNo).put("decision", "fail"));
        rpc.instanceDecide(ok);
        assertThat(ok.result.path("ok").asBoolean()).isTrue();
        assertThat(engine.awaitTerminal(m.id(), 20_000)).isTrue();
        assertThat(store.findInstance(m.id()).orElseThrow().status()).isEqualTo(InstanceStatus.FAILED);
        // 已终态再决策 → BAD_PARAMS
        assertThatThrownBy(() -> rpc.instanceDecide(call(p().put("instanceId", m.id())
                .put("runNo", runNo).put("decision", "retry"))))
                .isInstanceOf(BadParamsException.class);
    }

    // ══════════════════════════ instance.dataDir ══════════════════════════

    @Test
    void instanceDataDirReturnsAbsoluteDirAndMissingRejected() throws Exception {
        Definition d = save("ws_run",
                DefinitionValidatorTest.node("s", "start"),
                DefinitionValidatorTest.node("a", "ai").agent("writer"),
                DefinitionValidatorTest.edge("e1", "s", "a"));
        InstanceMeta m = store.createInstance(d, Map.of());
        Call c = call(p().put("instanceId", m.id()));
        rpc.instanceDataDir(c);
        String path = c.result.path("path").asString();
        // worker 宿主上的绝对路径：落位 workspaces/<wsId>/workflows/instances/<wiId>/ 且目录真实在盘。
        assertThat(path).isNotEmpty();
        assertThat(Path.of(path).isAbsolute()).isTrue();
        assertThat(path).contains("workspaces").contains("ws_run")
                .contains("workflows").contains("instances").contains(m.id());
        assertThat(Files.isDirectory(Path.of(path))).isTrue();

        // 实例不存在 → NOT_FOUND；缺 instanceId → BAD_PARAMS。
        assertThatThrownBy(() -> rpc.instanceDataDir(call(p().put("instanceId", "wi_nope"))))
                .isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> rpc.instanceDataDir(call(p())))
                .isInstanceOf(BadParamsException.class).hasMessageContaining("instanceId");
    }
}
