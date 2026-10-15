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
 * 而非只测内部方法。
 *
 * <p>双清单模型:blacklist(列出的不暴露)+ whitelist(非空时仅列出的暴露),
 * 黑名单优先;无配置 = 全部暴露(不携带 blacklist/whitelist 字段)。
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
    private final List<WorkspaceManager.Registered> registeredNow = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectory(tmp.resolve("home"));
        skillsDir = Files.createDirectory(tmp.resolve("skills"));
        wsA = Files.createDirectory(tmp.resolve("ws-a"));
        wsB = Files.createDirectory(tmp.resolve("ws-b"));

        Files.writeString(Files.createDirectories(skillsDir.resolve("plan")).resolve("skill.md"),
                "---\nname: 计划模式\ndescription: 当任务复杂需拆步骤执行时，使用本技能。\n---\n\n# 计划模式\n\n正文方法论。\n");
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
        handler.init();
    }

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
    private JsonNode callExposureSet(String skillId, String[] blacklist, String[] whitelist) {
        ArrayNode bl = Json.arr();
        for (String r : blacklist) bl.add(r);
        ArrayNode wl = Json.arr();
        for (String r : whitelist) wl.add(r);
        return rpcOk("skill.exposure.set", Json.obj()
                .put("skillId", skillId)
                .set("blacklist", bl)
                .set("whitelist", wl));
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
        assertEquals("计划模式", plan.path("name").asString());
        assertEquals("当任务复杂需拆步骤执行时，使用本技能。", plan.path("description").asString());
        assertEquals(skillsDir.resolve("plan").resolve("skill.md"), Path.of(plan.path("knowledgePath").asString()));
        // 无配置时不携带 blacklist/whitelist 字段
        assertTrue(plan.path("blacklist").isMissingNode(), "无配置时不携带 blacklist: " + plan);
        assertTrue(plan.path("whitelist").isMissingNode(), "无配置时不携带 whitelist: " + plan);

        JsonNode legacy = byId(list, "legacy-tool");
        assertEquals("legacy-tool", legacy.path("name").asString());
        assertTrue(legacy.path("blacklist").isMissingNode());
        assertTrue(legacy.path("whitelist").isMissingNode());
    }

    // ---- skill.exposure.set:校验分支 ----

    @Test
    void exposureSetUnknownSkillIdIsNotFound() {
        JsonNode err = rpcErr("skill.exposure.set",
                Json.obj().put("skillId", "no-such-skill")
                        .set("blacklist", Json.arr().add(wsA.toString()))
                        .set("whitelist", Json.arr()));
        assertEquals("NOT_FOUND", err.path("code").asString(), "未知 skillId → NOT_FOUND: " + err);
        assertTrue(store.snapshot().isEmpty(), "拒绝后不落盘");
    }

    @Test
    void exposureSetUnregisteredRootIsBadParams() {
        JsonNode err = rpcErr("skill.exposure.set",
                Json.obj().put("skillId", "plan")
                        .set("blacklist", Json.arr().add("C:\\nowhere\\ws"))
                        .set("whitelist", Json.arr()));
        assertEquals("BAD_PARAMS", err.path("code").asString(), "未注册 workspace root → BAD_PARAMS: " + err);
        assertTrue(store.snapshot().isEmpty(), "拒绝后不落盘");
    }

    @Test
    void exposureSetStaleRootAlreadyInConfigSurvivesRoundTripAfterWorkspaceRemoved() {
        // 排除 wsA → 工作区被删(注销) → 携带 wsA 再保存(原样保留)必须成功
        callExposureSet("plan", new String[]{wsA.toString()}, new String[]{});
        registeredNow.removeIf(r -> r.root().equals(wsA.toString()));
        JsonNode result = callExposureSet("plan", new String[]{wsA.toString()}, new String[]{});
        assertEquals(1, result.path("blacklist").size(), "旧黑名单项回写成功: " + result);
        assertTrue(store.getConfig("plan").blacklist().contains(
                wsA.toAbsolutePath().normalize().toString()), "stale root 仍在黑名单");
        // 不再携带即清除(唯一移除途径)
        callExposureSet("plan", new String[]{}, new String[]{});
        assertNull(store.getConfig("plan"), "省略 stale root 即清除(都空=删键)");
    }

    @Test
    void exposureSetNewUnregisteredRootStillRejectedWhileStaleAllowed() {
        callExposureSet("plan", new String[]{wsA.toString()}, new String[]{});
        registeredNow.removeIf(r -> r.root().equals(wsA.toString()));
        // 混入全新的未注册 root 仍须拒绝
        JsonNode err = rpcErr("skill.exposure.set",
                Json.obj().put("skillId", "plan")
                        .set("blacklist", Json.arr().add(wsA.toString()).add("C:\\nowhere\\ws"))
                        .set("whitelist", Json.arr()));
        assertEquals("BAD_PARAMS", err.path("code").asString(), "新增未注册 root 仍拒绝: " + err);
    }

    @Test
    void exposureSetRelativeRootIsBadParams() {
        JsonNode err = rpcErr("skill.exposure.set",
                Json.obj().put("skillId", "plan")
                        .set("blacklist", Json.arr().add("relative/path"))
                        .set("whitelist", Json.arr()));
        assertEquals("BAD_PARAMS", err.path("code").asString(), "相对路径 → BAD_PARAMS: " + err);
    }

    // ---- skill.exposure.set:成功路径 ----

    @Test
    void exposureSetNormalizesRootAndIsVisibleInSubsequentList() {
        JsonNode result = callExposureSet("plan",
                new String[]{wsA.toString() + "/.", wsB.toString()},
                new String[]{});

        assertEquals("plan", result.path("skillId").asString());
        assertEquals(2, result.path("blacklist").size(), "应答回显黑名单: " + result);
        assertEquals(0, result.path("whitelist").size(), "白名单为空: " + result);
        assertEquals(wsA.toString(), result.path("blacklist").path(0).asString(), "normalize 后无 /. 后缀");
        assertEquals(wsB.toString(), result.path("blacklist").path(1).asString());

        JsonNode list = rpcOk("skill.list", Json.obj());
        JsonNode planEntry = byId(list, "plan");
        assertEquals(2, planEntry.path("blacklist").size());
        assertEquals(wsA.toString(), planEntry.path("blacklist").path(0).asString());
        assertEquals(wsB.toString(), planEntry.path("blacklist").path(1).asString());
        assertTrue(planEntry.path("whitelist").isArray());
        assertEquals(0, planEntry.path("whitelist").size());
        // 其余 skill 不受影响
        assertTrue(byId(list, "legacy-tool").path("blacklist").isMissingNode());
    }

    @Test
    void exposureSetWhitelistOnly() {
        JsonNode result = callExposureSet("plan", new String[]{}, new String[]{wsA.toString()});

        assertEquals("plan", result.path("skillId").asString());
        assertEquals(0, result.path("blacklist").size());
        assertEquals(1, result.path("whitelist").size());
        assertEquals(wsA.toString(), result.path("whitelist").path(0).asString());

        JsonNode list = rpcOk("skill.list", Json.obj());
        JsonNode planEntry = byId(list, "plan");
        assertEquals(0, planEntry.path("blacklist").size());
        assertEquals(1, planEntry.path("whitelist").size());
        assertEquals(wsA.toString(), planEntry.path("whitelist").path(0).asString());
    }

    @Test
    void exposureSetBothBlacklistAndWhitelist() {
        JsonNode result = callExposureSet("plan",
                new String[]{wsA.toString()},
                new String[]{wsB.toString()});

        assertEquals("plan", result.path("skillId").asString());
        assertEquals(1, result.path("blacklist").size(), "黑名单 1 项");
        assertEquals(1, result.path("whitelist").size(), "白名单 1 项");
        assertEquals(wsA.toString(), result.path("blacklist").path(0).asString());
        assertEquals(wsB.toString(), result.path("whitelist").path(0).asString());

        ExposureConfig cfg = store.getConfig("plan");
        assertNotNull(cfg);
        assertEquals(Set.of(wsA.toString()), cfg.blacklist());
        assertEquals(Set.of(wsB.toString()), cfg.whitelist());

        // 黑名单优先:wsA 同时不在白名单但不在黑名单中也不在白名单中 → 不暴露
        assertFalse(store.isExposed("plan", wsA.toString()), "黑名单命中:不暴露");
        assertTrue(store.isExposed("plan", wsB.toString()), "白名单命中:暴露");
    }

    @Test
    void exposureSetBothEmptyClearsConfig() {
        callExposureSet("plan", new String[]{wsA.toString()}, new String[]{});
        assertFalse(byId(rpcOk("skill.list", Json.obj()), "plan").path("blacklist").isMissingNode());

        JsonNode result = callExposureSet("plan", new String[]{}, new String[]{});

        assertEquals(0, result.path("blacklist").size());
        assertEquals(0, result.path("whitelist").size());
        JsonNode planEntry = byId(rpcOk("skill.list", Json.obj()), "plan");
        assertTrue(planEntry.path("blacklist").isMissingNode(), "配置已清除");
        assertTrue(planEntry.path("whitelist").isMissingNode(), "配置已清除");
        assertFalse(store.snapshot().containsKey("plan"));
    }

    @Test
    void exposureSetMissingBothArraysTreatedAsEmpty() {
        // 不传 blacklist/whitelist = 空数组 = 清除配置
        JsonNode result = rpcOk("skill.exposure.set",
                Json.obj().put("skillId", "plan"));
        assertEquals(0, result.path("blacklist").size());
        assertEquals(0, result.path("whitelist").size());
        assertNull(store.getConfig("plan"), "缺省 = 都空 = 删键");
    }

    @Test
    void exposureSetSuccessBroadcastsConfigChangedSkills() {
        callExposureSet("plan", new String[]{wsA.toString()}, new String[]{});

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool, org.mockito.Mockito.timeout(10_000))
                .broadcastEvt(eq("config.changed"), payload.capture());
        assertEquals("skills", payload.getValue().path("keys").path(0).asString(),
                "广播 config.changed{keys:[\"skills\"]}: " + payload.getValue());
        assertEquals(1, payload.getValue().path("keys").size());
    }
}
