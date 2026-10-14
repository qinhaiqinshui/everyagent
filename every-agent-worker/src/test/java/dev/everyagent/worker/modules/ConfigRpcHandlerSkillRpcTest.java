package dev.everyagent.worker.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.modules.SkillExposureStore.ExposureConfig;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.skill.SkillCatalog;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * {@code skill.list} / {@code skill.exposure.set} 的 RPC 端到端测试:
 * 经<b>真实</b> {@link RpcDispatcher} 分发(覆盖式注册进方法表 + 异常→错误码映射),
 * mock {@link HubLink} 捕获 wire 帧——钉 wire 形状与 NOT_FOUND/BAD_PARAMS 语义,
 * 而非只测内部方法。skill 目录用临时 skillsDir 造两类条目:
 * 带 frontmatter 的与<b>无 frontmatter 回落</b>(title=目录名、description=正文首行)的。
 *
 * <p>新模型:blacklist(除列出外全部暴露)/ whitelist(仅列出暴露),
 * 无配置 = 全部暴露(不携带 exposureMode/exposureWorkspaces 字段)。
 */
class ConfigRpcHandlerSkillRpcTest {

    private static final String K = "k1";
    private static final String WORKER_ID = "test-worker";
    private static final AtomicLong SEQ = new AtomicLong();

    @TempDir
    Path tmp;

    private Path home;
    private Path skillsDir;
    private Path wsA;
    private Path wsB;

    private HubPool pool;
    private RpcDispatcher dispatcher;
    private SkillExposureStore store;
    private ConfigRpcHandler handler;
    private WorkspaceManager workspaces;
    /** 可变注册表:测试中途可注销工作区,模拟「排除项对应的工作区已被删」。 */
    private final List<WorkspaceManager.Registered> registeredNow = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectory(tmp.resolve("home"));
        skillsDir = Files.createDirectory(tmp.resolve("skills"));
        wsA = Files.createDirectory(tmp.resolve("ws-a"));
        wsB = Files.createDirectory(tmp.resolve("ws-b"));

        // 带 frontmatter 的 skill
        Files.writeString(Files.createDirectories(skillsDir.resolve("plan")).resolve("skill.md"),
                "---\nname: 计划模式\ndescription: 当任务复杂需拆步骤执行时，使用本技能。\n---\n\n# 计划模式\n\n正文方法论。\n");
        // 无 frontmatter 的用户自制 skill(迁移期形态):title 回落目录名、description 回落正文首个非 # 行
        Files.writeString(Files.createDirectories(skillsDir.resolve("legacy-tool")).resolve("skill.md"),
                "# Legacy Tool\n\n把 Markdown 转成 PDF。\n\n后续正文。\n");

        pool = mock(HubPool.class);
        dispatcher = new RpcDispatcher(pool, new WorkerProperties());

        workspaces = mock(WorkspaceManager.class);
        registeredNow.add(new WorkspaceManager.Registered("w_a", wsA.toString(), 1L, 1L, List.of()));
        registeredNow.add(new WorkspaceManager.Registered("w_b", wsB.toString(), 2L, 2L, List.of()));
        when(workspaces.list()).thenAnswer(inv -> List.copyOf(registeredNow));

        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        props.setSkillsDir(skillsDir.toString());
        store = new SkillExposureStore(props);
        store.init();

        handler = new ConfigRpcHandler(
                mock(ConfigStore.class),
                new SkillCatalog(props),
                store,
                workspaces,
                mock(UserPreferenceStore.class),
                pool,
                dispatcher);
        handler.init(); // @PostConstruct:把 skill.list / skill.exposure.set 注册进方法表
    }

    // ---- 调用辅助:经真实 dispatcher 分发,捕获 wire 应答帧 ----

    /** 发起一次 RPC 并等待应答帧(rpc.ok 取 result,rpc.err 取整个错误体)。 */
    private JsonNode rpc(String method, JsonNode params, String expectEvent) {
        HubLink conn = mock(HubLink.class);
        when(conn.k()).thenReturn(K);
        when(conn.workerId()).thenReturn(WORKER_ID);
        JsonNode frame = Json.obj()
                .put("channel", Channels.workerCmd(K, WORKER_ID))
                .put("event", "rpc")
                .set("payload", Json.obj()
                        .put("reqId", "r-" + SEQ.incrementAndGet())
                        .put("method", method)
                        .set("params", params));
        dispatcher.onHubMessage(conn, frame);
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(conn, org.mockito.Mockito.timeout(10_000))
                .pub(eq(Channels.workerEvt(K, WORKER_ID)), eq(expectEvent), isNull(), body.capture(), isNull());
        return body.getValue();
    }

    private JsonNode rpcOk(String method, JsonNode params) {
        return rpc(method, params, "rpc.ok").path("result");
    }

    private JsonNode rpcErr(String method, JsonNode params) {
        return rpc(method, params, "rpc.err");
    }

    /** 调用 skill.exposure.set,返回 ok 应答的 result 体。 */
    private JsonNode callExposureSet(String skillId, String mode, String... roots) {
        ArrayNode arr = Json.arr();
        for (String r : roots) {
            arr.add(r);
        }
        return rpcOk("skill.exposure.set", Json.obj()
                .put("skillId", skillId)
                .put("mode", mode)
                .set("workspaces", arr));
    }

    private static JsonNode byId(JsonNode list, String id) {
        for (JsonNode n : list) {
            if (id.equals(n.path("id").asString())) {
                return n;
            }
        }
        throw new AssertionError("skill.list 缺少条目 " + id + ": " + list);
    }

    // ---- skill.list ----

    @Test
    void skillListShapeIncludesFrontmatterAndFallbackEntries() {
        JsonNode list = rpcOk("skill.list", Json.obj());
        assertTrue(list.isArray(), "wire 形态 = 数组: " + list);
        assertEquals(2, list.size(), "目录即真相源:plan + legacy-tool 共 2 条: " + list);

        JsonNode plan = byId(list, "plan");
        assertEquals("计划模式", plan.path("name").asString(), "wire 字段名用 name(取自 Skill.title/frontmatter)");
        assertEquals("当任务复杂需拆步骤执行时，使用本技能。", plan.path("description").asString());
        assertEquals(skillsDir.resolve("plan").resolve("skill.md"), Path.of(plan.path("knowledgePath").asString()));
        // 无配置时不携带 exposureMode/exposureWorkspaces 字段(前端按默认 = 全部暴露渲染)
        assertTrue(plan.path("exposureMode").isMissingNode(), "无配置时不携带 exposureMode 字段: " + plan);
        assertTrue(plan.path("exposureWorkspaces").isMissingNode(), "无配置时不携带 exposureWorkspaces 字段: " + plan);

        JsonNode legacy = byId(list, "legacy-tool");
        assertEquals("legacy-tool", legacy.path("name").asString(), "无 frontmatter:title 回落目录名");
        assertEquals("把 Markdown 转成 PDF。", legacy.path("description").asString(),
                "无 frontmatter:description 回落正文首个非 # 行");
        assertEquals(skillsDir.resolve("legacy-tool").resolve("skill.md"),
                Path.of(legacy.path("knowledgePath").asString()));
        assertTrue(legacy.path("exposureMode").isMissingNode());
        assertTrue(legacy.path("exposureWorkspaces").isMissingNode());
    }

    // ---- skill.exposure.set:校验分支 ----

    @Test
    void exposureSetUnknownSkillIdIsNotFound() {
        JsonNode err = rpcErr("skill.exposure.set",
                Json.obj().put("skillId", "no-such-skill")
                        .put("mode", "blacklist")
                        .set("workspaces", Json.arr().add(wsA.toString())));
        assertEquals("NOT_FOUND", err.path("code").asString(), "未知 skillId → NOT_FOUND: " + err);
        assertTrue(store.snapshot().isEmpty(), "拒绝后不落盘");
    }

    @Test
    void exposureSetMissingModeIsBadParams() {
        JsonNode err = rpcErr("skill.exposure.set",
                Json.obj().put("skillId", "plan")
                        .set("workspaces", Json.arr().add(wsA.toString())));
        assertEquals("BAD_PARAMS", err.path("code").asString(), "缺少 mode → BAD_PARAMS: " + err);
        assertTrue(store.snapshot().isEmpty(), "拒绝后不落盘");
    }

    @Test
    void exposureSetInvalidModeIsBadParams() {
        JsonNode err = rpcErr("skill.exposure.set",
                Json.obj().put("skillId", "plan")
                        .put("mode", "grey")
                        .set("workspaces", Json.arr().add(wsA.toString())));
        assertEquals("BAD_PARAMS", err.path("code").asString(), "非法 mode → BAD_PARAMS: " + err);
        assertTrue(store.snapshot().isEmpty(), "拒绝后不落盘");
    }

    @Test
    void exposureSetUnregisteredRootIsBadParams() {
        JsonNode err = rpcErr("skill.exposure.set",
                Json.obj().put("skillId", "plan")
                        .put("mode", "blacklist")
                        .set("workspaces", Json.arr().add("C:\\nowhere\\ws")));
        assertEquals("BAD_PARAMS", err.path("code").asString(), "未注册 workspace root → BAD_PARAMS: " + err);
        assertTrue(store.snapshot().isEmpty(), "拒绝后不落盘");
    }

    @Test
    void exposureSetStaleRootAlreadyInConfigSurvivesRoundTripAfterWorkspaceRemoved() {
        // 前端「保存时原样保留旧排除项」的回写不能被注册校验卡死:
        // 排除 wsA → 工作区被删(注销) → 携带 wsA 再保存(原样保留)必须成功
        callExposureSet("plan", "blacklist", wsA.toString());
        registeredNow.removeIf(r -> r.root().equals(wsA.toString()));
        JsonNode result = callExposureSet("plan", "blacklist", wsA.toString());
        assertEquals(1, result.path("workspaces").size(), "旧排除项回写成功: " + result);
        assertTrue(store.getConfig("plan").workspaces().contains(
                wsA.toAbsolutePath().normalize().toString()), "stale root 仍在排除表");
        // 不再携带即清除(唯一的移除途径,不能被校验拦住)
        callExposureSet("plan", "blacklist");
        assertNull(store.getConfig("plan"), "省略 stale root 即清除(blacklist + 空 = 删键)");
    }

    @Test
    void exposureSetNewUnregisteredRootStillRejectedWhileStaleAllowed() {
        callExposureSet("plan", "blacklist", wsA.toString());
        registeredNow.removeIf(r -> r.root().equals(wsA.toString()));
        // 混入全新的未注册 root 仍须拒绝(防拼写错配的校验不因放行 stale 而失效)
        JsonNode err = rpcErr("skill.exposure.set",
                Json.obj().put("skillId", "plan")
                        .put("mode", "blacklist")
                        .set("workspaces", Json.arr().add(wsA.toString()).add("C:\\nowhere\\ws")));
        assertEquals("BAD_PARAMS", err.path("code").asString(), "新增未注册 root 仍拒绝: " + err);
    }

    @Test
    void exposureSetRelativeRootIsBadParams() {
        JsonNode err = rpcErr("skill.exposure.set",
                Json.obj().put("skillId", "plan")
                        .put("mode", "blacklist")
                        .set("workspaces", Json.arr().add("relative/path")));
        assertEquals("BAD_PARAMS", err.path("code").asString(), "相对路径不是工作区根 → BAD_PARAMS: " + err);
    }

    @Test
    void exposureSetMissingWorkspacesParamIsBadParams() {
        JsonNode err = rpcErr("skill.exposure.set",
                Json.obj().put("skillId", "plan")
                        .put("mode", "blacklist"));
        assertEquals("BAD_PARAMS", err.path("code").asString(), "缺 workspaces 数组 → BAD_PARAMS: " + err);
    }

    // ---- skill.exposure.set:成功路径 ----

    @Test
    void exposureSetNormalizesRootAndIsVisibleInSubsequentList() {
        // 传未规范化变体(尾段 "."):与注册表 normalize 后字符串相等才放行,落盘为规范化形态
        JsonNode result = callExposureSet("plan", "blacklist", wsA.toString() + "/.", wsB.toString());

        assertEquals("plan", result.path("skillId").asString());
        assertEquals("blacklist", result.path("mode").asString());
        assertEquals(2, result.path("workspaces").size(), "应答回显规范化后的排除表: " + result);
        // 逐元素断言(JSON 字符串含转义反斜杠,不能对整串 contains)
        assertEquals(wsA.toString(), result.path("workspaces").get(0).asString(),
                "落盘/应答均为 normalize 后的 root(无 /. 后缀)");
        assertEquals(wsB.toString(), result.path("workspaces").get(1).asString());

        // 随后的 skill.list 可见(同一次扫描 + 同一 store)
        JsonNode list = rpcOk("skill.list", Json.obj());
        JsonNode planEntry = byId(list, "plan");
        assertEquals("blacklist", planEntry.path("exposureMode").asString());
        JsonNode exposed = planEntry.path("exposureWorkspaces");
        assertEquals(2, exposed.size());
        assertEquals(wsA.toString(), exposed.path(0).asString());
        assertEquals(wsB.toString(), exposed.path(1).asString());
        // 其余 skill 不受影响(无配置,不携带 exposure 字段)
        assertTrue(byId(list, "legacy-tool").path("exposureMode").isMissingNode());

        // 已落盘(重启可恢复)
        ExposureConfig cfg = store.getConfig("plan");
        assertNotNull(cfg);
        assertEquals(Set.of(wsA.toString(), wsB.toString()), cfg.workspaces());
    }

    @Test
    void exposureSetWhitelistModeOnlyExposesListedWorkspaces() {
        JsonNode result = callExposureSet("plan", "whitelist", wsA.toString());

        assertEquals("plan", result.path("skillId").asString());
        assertEquals("whitelist", result.path("mode").asString());
        assertEquals(1, result.path("workspaces").size());
        assertEquals(wsA.toString(), result.path("workspaces").get(0).asString());

        // skill.list 可见
        JsonNode list = rpcOk("skill.list", Json.obj());
        JsonNode planEntry = byId(list, "plan");
        assertEquals("whitelist", planEntry.path("exposureMode").asString());
        assertEquals(1, planEntry.path("exposureWorkspaces").size());
        assertEquals(wsA.toString(), planEntry.path("exposureWorkspaces").path(0).asString());
    }

    @Test
    void exposureSetBlacklistEmptyClearsConfig() {
        callExposureSet("plan", "blacklist", wsA.toString());
        assertEquals(1, byId(rpcOk("skill.list", Json.obj()), "plan").path("exposureWorkspaces").size());

        JsonNode result = callExposureSet("plan", "blacklist"); // 空数组 = 恢复默认(全部暴露)

        assertEquals("blacklist", result.path("mode").asString());
        assertEquals(0, result.path("workspaces").size(), "应答回显空排除表: " + result);
        // skill.list 不再携带 exposure 字段(配置已清除)
        JsonNode planEntry = byId(rpcOk("skill.list", Json.obj()), "plan");
        assertTrue(planEntry.path("exposureMode").isMissingNode(), "配置已清除,不携带 exposureMode: " + planEntry);
        assertTrue(planEntry.path("exposureWorkspaces").isMissingNode(), "配置已清除,不携带 exposureWorkspaces: " + planEntry);
        assertFalse(store.snapshot().containsKey("plan"), "store 中该 skill 的键已删除");
    }

    @Test
    void exposureSetSuccessBroadcastsConfigChangedSkills() {
        callExposureSet("plan", "blacklist", wsA.toString());

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool, org.mockito.Mockito.timeout(10_000))
                .broadcastEvt(eq("config.changed"), payload.capture());
        assertEquals("skills", payload.getValue().path("keys").path(0).asString(),
                "广播 config.changed{keys:[\"skills\"]}: " + payload.getValue());
        assertEquals(1, payload.getValue().path("keys").size());
    }
}
