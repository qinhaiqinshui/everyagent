package dev.everyagent.worker.modules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.skill.SkillCatalog;
import tools.jackson.databind.JsonNode;

/**
 * {@code skill.export} / {@code skill.import} 的 RPC 端到端测试:经<b>真实</b>
 * {@link RpcDispatcher} 分发,mock {@link HubLink} 捕获 wire 帧——钉 zip 回传的分批
 * 形态、导入的落盘/缓存失效语义与 BAD_PARAMS(zip-slip/超大包/非法 base64)分支。
 *
 * <p>导入导出是 §5.5 定义的 skill 目录整体迁移通道:导出 = skillsDir 全树 zip;
 * 导入 = 解压回 skillsDir(同名覆盖)+ 失效 {@link SkillCatalog} 缓存重扫
 * (无需重启 worker 即进 skill.list)。
 */
class SkillZipRpcTest {

    private static final String K = "k1";
    private static final String WORKER_ID = "test-worker";
    private static final AtomicLong SEQ = new AtomicLong();

    @TempDir
    Path tmp;

    private Path home;
    private Path skillsDir;

    private HubPool pool;
    private RpcDispatcher dispatcher;
    private ConfigRpcHandler handler;
    private SkillCatalog catalog;

    @BeforeEach
    void setUp() throws Exception {
        home = Files.createDirectory(tmp.resolve("home"));
        skillsDir = Files.createDirectory(tmp.resolve("skills"));
        Files.writeString(Files.createDirectories(skillsDir.resolve("plan")).resolve("skill.md"),
                "---\nname: 计划模式\ndescription: 复杂任务拆步骤。\n---\n\n# 计划模式\n\n正文。\n");
        Files.writeString(Files.createDirectories(skillsDir.resolve("legacy-tool")).resolve("skill.md"),
                "# Legacy Tool\n\n把 Markdown 转成 PDF。\n");

        pool = mock(HubPool.class);
        dispatcher = new RpcDispatcher(pool, new WorkerProperties());

        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        props.setSkillsDir(skillsDir.toString());
        catalog = new SkillCatalog(props);

        handler = new ConfigRpcHandler(
                mock(ConfigStore.class),
                catalog,
                new SkillExposureStore(props),
                mock(WorkspaceManager.class),
                mock(UserPreferenceStore.class),
                pool,
                dispatcher);
        handler.init();
    }

    // ---- 测试脚手架 ----

    private HubLink conn() {
        HubLink conn = mock(HubLink.class);
        when(conn.k()).thenReturn(K);
        when(conn.workerId()).thenReturn(WORKER_ID);
        return conn;
    }

    private void dispatch(HubLink conn, String method, JsonNode params) {
        JsonNode frame = Json.obj()
                .put("channel", Channels.workerCmd(K, WORKER_ID))
                .put("event", "rpc")
                .set("payload", Json.obj()
                        .put("reqId", "r-" + SEQ.incrementAndGet())
                        .put("method", method)
                        .set("params", params));
        dispatcher.onHubMessage(conn, frame);
    }

    /** 捕获指定事件的全部 wire 帧 payload(异步竞态由 Mockito timeout 覆盖)。 */
    private List<JsonNode> captureFrames(HubLink conn, String event) {
        ArgumentCaptor<JsonNode> body = ArgumentCaptor.forClass(JsonNode.class);
        verify(conn, timeout(10_000).atLeastOnce())
                .pub(eq(Channels.workerEvt(K, WORKER_ID)), eq(event), isNull(), body.capture(), isNull());
        return body.getAllValues();
    }

    private JsonNode lastOf(List<JsonNode> frames) {
        assertTrue(!frames.isEmpty(), "未捕获到 wire 帧");
        return frames.get(frames.size() - 1).path("result");
    }

    private JsonNode rpcOk(String method, JsonNode params) {
        HubLink conn = conn();
        dispatch(conn, method, params);
        return lastOf(captureFrames(conn, "rpc.ok"));
    }

    private JsonNode rpcErr(String method, JsonNode params) {
        HubLink conn = conn();
        dispatch(conn, method, params);
        List<JsonNode> frames = captureFrames(conn, "rpc.err");
        return frames.get(frames.size() - 1);
    }

    /** 构造 zip 字节(LinkedHashMap 保持条目顺序)。 */
    private static byte[] zipOf(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, byte[]> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue());
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    /** 解 zip 为 路径 → 内容 映射。 */
    private static Map<String, byte[]> unzipOf(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                out.put(entry.getName(), zis.readAllBytes());
            }
        }
        return out;
    }

    private static String b64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    // ---- skill.export ----

    @Test
    void exportSmallZipIsInlineBase64AndRoundTripsWholeTree() throws Exception {
        JsonNode result = rpcOk("skill.export", Json.obj());

        assertEquals(2, result.path("skillCount").asInt(), "skillCount = 目录内 skill 数");
        assertEquals(2, result.path("fileCount").asInt(), "fileCount = 文件数(plan + legacy-tool 各一个 skill.md)");
        assertTrue(result.path("size").asLong() > 0, "携带 zip 大小");
        assertFalse(result.path("base64").asString("").isEmpty(), "小包内联 base64");

        Map<String, byte[]> entries = unzipOf(Base64.getDecoder().decode(result.path("base64").asString()));
        assertEquals(2, entries.size(), "整树打包: " + entries.keySet());
        assertTrue(new String(entries.get("plan/skill.md"), StandardCharsets.UTF_8).contains("计划模式"));
        assertTrue(new String(entries.get("legacy-tool/skill.md"), StandardCharsets.UTF_8).contains("Legacy Tool"));
    }

    @Test
    void exportLargeZipStreamsInChunksWithFinalOkSummary() throws Exception {
        // 不可压缩的随机字节放进已有 skill 目录(非 skill.md,不影响 frontmatter 解析),
        // 保证 zip 本体超过 INLINE_MAX(256KB)走分批路径
        byte[] big = new byte[400 * 1024];
        new Random(42).nextBytes(big);
        Files.write(skillsDir.resolve("plan").resolve("blob.bin"), big);

        HubLink conn = conn();
        dispatch(conn, "skill.export", Json.obj());

        List<JsonNode> dataFrames = captureFrames(conn, "rpc.data");
        JsonNode ok = lastOf(captureFrames(conn, "rpc.ok"));

        ByteArrayOutputStream zip = new ByteArrayOutputStream();
        long expectedOffset = 0;
        for (JsonNode frame : dataFrames) {
            JsonNode part = frame.path("batch").path(0);
            assertEquals(expectedOffset, part.path("offset").asLong(), "分批 offset 连续递增");
            assertEquals(part.path("size").asInt(),
                    Base64.getDecoder().decode(part.path("base64").asString()).length,
                    "size 与 base64 解码长度一致");
            zip.writeBytes(Base64.getDecoder().decode(part.path("base64").asString()));
            expectedOffset += part.path("size").asLong();
        }
        assertTrue(ok.path("base64").isMissingNode() || ok.path("base64").asString("").isEmpty(),
                "大包末帧 ok 不内联 base64(只汇总)");
        assertEquals(expectedOffset, ok.path("size").asLong(), "分批字节数 = 末帧汇总 size");
        assertEquals(zip.size(), ok.path("size").asInt());
        assertEquals(2, ok.path("skillCount").asInt(), "skill 目录数不变(blob.bin 是 plan 的附属文件)");
        assertEquals(3, ok.path("fileCount").asInt(), "文件数 = skill.md × 2 + blob.bin");

        Map<String, byte[]> entries = unzipOf(zip.toByteArray());
        assertTrue(entries.containsKey("plan/blob.bin"), "大文件完整入包: " + entries.keySet());
        assertEquals(400 * 1024, entries.get("plan/blob.bin").length);
    }

    // ---- skill.import:成功路径 ----

    @Test
    void importAddsNewSkillVisibleInSkillListWithoutRestartAndBroadcasts() throws Exception {
        byte[] zip = zipOf(Map.of(
                "imported-tool/skill.md",
                "---\nname: 导入工具\ndescription: 从别的 worker 迁来。\n---\n\n正文。\n".getBytes(StandardCharsets.UTF_8)));

        JsonNode result = rpcOk("skill.import", Json.obj().put("contentBase64", b64(zip)));

        assertEquals(1, result.path("fileCount").asInt(), "解压 1 个文件");
        assertEquals(3, result.path("skillCount").asInt(), "重扫后 3 个 skill");

        // 缓存失效后无需重启 worker,skill.list 立即可见(同一 catalog 实例)
        JsonNode list = rpcOk("skill.list", Json.obj());
        boolean found = false;
        for (JsonNode n : list) {
            if ("imported-tool".equals(n.path("id").asString())) {
                found = true;
                assertEquals("导入工具", n.path("name").asString(), "frontmatter 在重扫后重新解析");
            }
        }
        assertTrue(found, "导入的 skill 立即出现在 skill.list: " + list);

        ArgumentCaptor<JsonNode> payload = ArgumentCaptor.forClass(JsonNode.class);
        verify(pool, timeout(10_000).atLeastOnce()).broadcastEvt(eq("config.changed"), payload.capture());
        assertEquals("skills", payload.getValue().path("keys").path(0).asString(),
                "广播 config.changed{keys:[\"skills\"]}");
    }

    @Test
    void importOverwritesSameNameSkillOnDiskAndInCatalog() throws Exception {
        byte[] zip = zipOf(Map.of(
                "plan/skill.md",
                "---\nname: 新计划\ndescription: 覆盖后的版本。\n---\n\n新正文。\n".getBytes(StandardCharsets.UTF_8)));

        JsonNode result = rpcOk("skill.import", Json.obj().put("contentBase64", b64(zip)));
        assertEquals(1, result.path("fileCount").asInt());

        assertEquals("---\nname: 新计划\ndescription: 覆盖后的版本。\n---\n\n新正文。\n",
                Files.readString(skillsDir.resolve("plan").resolve("skill.md")),
                "同名条目覆盖落盘");
        JsonNode list = rpcOk("skill.list", Json.obj());
        assertEquals("新计划", find(list, "plan").path("name").asString(),
                "catalog 重扫后 name 刷新");
    }

    @Test
    void importSkipsMacOsJunkEntries() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("imported-tool/skill.md", "正文\n".getBytes(StandardCharsets.UTF_8));
        entries.put("__MACOSX/imported-tool/._skill.md", "junk".getBytes(StandardCharsets.UTF_8));
        entries.put("imported-tool/.DS_Store", "junk".getBytes(StandardCharsets.UTF_8));

        JsonNode result = rpcOk("skill.import", Json.obj().put("contentBase64", b64(zipOf(entries))));
        assertEquals(1, result.path("fileCount").asInt(), "__MACOSX/.DS_Store 不计入也不落盘");
        assertFalse(Files.exists(skillsDir.resolve("imported-tool").resolve(".DS_Store")));
    }

    // ---- skill.import:校验分支 ----

    @Test
    void importZipSlipEntryIsBadParamsAndWritesNothingOutside() throws Exception {
        byte[] zip = zipOf(Map.of(
                "../evil.txt", "逃逸".getBytes(StandardCharsets.UTF_8)));

        JsonNode err = rpcErr("skill.import", Json.obj().put("contentBase64", b64(zip)));
        assertEquals("BAD_PARAMS", err.path("code").asString(), "zip-slip → BAD_PARAMS: " + err);
        assertFalse(Files.exists(tmp.resolve("evil.txt")), "skillsDir 外无任何写入");
    }

    @Test
    void importMissingOrInvalidBase64IsBadParams() {
        assertEquals("BAD_PARAMS", rpcErr("skill.import", Json.obj()).path("code").asString(),
                "缺 contentBase64 → BAD_PARAMS");
        assertEquals("BAD_PARAMS",
                rpcErr("skill.import", Json.obj().put("contentBase64", "不是base64!!")).path("code").asString(),
                "非法 base64 → BAD_PARAMS(不落 INTERNAL)");
    }

    @Test
    void importOverSizeZipIsBadParams() throws Exception {
        // 17MB 随机内容(不可压缩)→ zip 本体超 16MB 解码上限
        Random rnd = new Random(7);
        byte[] big = new byte[17 * 1024 * 1024];
        rnd.nextBytes(big);
        byte[] bigRandomZip = zipOf(Map.of("a/skill.md", big));
        assertTrue(bigRandomZip.length > 16 * 1024 * 1024, "测试前提:随机内容 zip 超 16MB");

        JsonNode err = rpcErr("skill.import", Json.obj().put("contentBase64", b64(bigRandomZip)));
        assertEquals("BAD_PARAMS", err.path("code").asString(), "超 16MB → BAD_PARAMS: " + err);
        assertFalse(Files.exists(skillsDir.resolve("a")), "超限包不落盘");
    }

    // ---- 辅助 ----

    private static JsonNode find(JsonNode list, String id) {
        for (JsonNode n : list) {
            if (id.equals(n.path("id").asString())) {
                return n;
            }
        }
        throw new AssertionError("skill.list 缺少条目 " + id + ": " + list);
    }
}
