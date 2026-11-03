package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.plugin.api.event.Channels;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * fs.replaceRaw(「定位-替换」写,文件差异标签页「回滚」数据面)的契约单元测试
 * (不依赖 Spring/真实 hub,与 FsRawPathTest 同款 harness)。
 *
 * <p>回归面:
 * <ol>
 *   <li><b>反向 update_file 语义</b>:oldContent 行尾宽容唯一匹配(CRLF/LF 互认)、
 *       newContent 按主导行尾转写、替换区间外逐字节保留;未找到(=已被其他修改)与
 *       出现多次一律 BAD_PARAMS 拒绝且<b>不落盘</b>。</li>
 *   <li><b>deleteWhenEmpty</b>(回滚「新建文件」):匹配段覆盖全文件且 newContent 为空
 *       → 删除文件而非写空文件;匹配段不覆盖全文件 → 正常替换保留残余内容。</li>
 *   <li><b>Raw 系列合法根收敛</b>(§5.5/§14.6):越界 SANDBOX_DENIED、相对路径 BAD_PARAMS,
 *       与 readRaw/writeRaw 同构。</li>
 * </ol>
 */
class FsReplaceRawTest {

    @TempDir
    Path tempDir;

    private RpcDispatcher dispatcher;
    private RawRootRegistry rawGrants;

    @BeforeEach
    void setUp() {
        dispatcher = new RpcDispatcher(null, new WorkerProperties());
        rawGrants = new RawRootRegistry();
        new FsService(dispatcher, null, null, new WorkerProperties(), null, rawGrants);
    }

    /** 发起一次 RPC(经真实分发器),等到唯一终答后返回 {event, payload}。 */
    private JsonNode call(String method, ObjectNode params) throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        List<Object[]> out = new ArrayList<>();
        HubLink link = mock(HubLink.class);
        when(link.k()).thenReturn("k");
        when(link.workerId()).thenReturn("w");
        doAnswer(inv -> {
            Object[] args = inv.getArguments();
            out.add(args);
            if ("rpc.ok".equals(args[1]) || "rpc.err".equals(args[1])) {
                terminal.countDown();
            }
            return null;
        }).when(link).pub(any(), any(), any(), any(), any());

        ObjectNode payload = Json.obj().put("reqId", "req-" + System.nanoTime())
                .put("method", method);
        payload.set("params", params);
        ObjectNode frame = Json.obj()
                .put("channel", Channels.workerCmd("k", "w"))
                .put("event", "rpc");
        frame.set("payload", payload);
        dispatcher.onHubMessage(link, frame);

        assertTrue(terminal.await(10, TimeUnit.SECONDS), method + " 未在 10s 内终答");
        return Json.obj().put("event", (String) out.get(0)[1])
                .set("payload", (JsonNode) out.get(0)[3]);
    }

    /** fs.replaceRaw 快捷封装。 */
    private JsonNode replace(Path file, String oldContent, String newContent, boolean deleteWhenEmpty)
            throws Exception {
        ObjectNode params = Json.obj().put("path", file.toString())
                .put("oldContent", oldContent)
                .put("newContent", newContent);
        if (deleteWhenEmpty) {
            params.put("deleteWhenEmpty", "true");
        }
        return call(RpcMethods.FS_REPLACE_RAW, params);
    }

    /** 断言应答为 rpc.err 且 code 匹配,返回 message。 */
    private static String errOf(JsonNode reply, String code) {
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        assertEquals(code, reply.path("payload").path("code").asString(), reply.toString());
        return reply.path("payload").path("message").asString();
    }

    /** 在 tempDir 里造一个文件并放行 tempDir(会话级,与前端确认放行同语义)。 */
    private Path seedFile(String name, String content) throws Exception {
        assertEquals("rpc.ok", call(RpcMethods.FS_GRANT_RAW_ROOT,
                Json.obj().put("path", tempDir.toString())).path("event").asString());
        return Files.writeString(tempDir.resolve(name), content, StandardCharsets.UTF_8);
    }

    // ---- 反向 update_file 语义 ----

    @Test
    void replacesUniqueMatchEolAgnosticAndPreservesRest() throws Exception {
        // 文件是 CRLF、oldContent/newContent 是 LF(前端 diff 文本口径)——行尾互认 + 按主导行尾转写
        Path file = seedFile("crlf.txt", "头部\r\nOLD1\r\nOLD2\r\n尾部\r\n");
        JsonNode reply = replace(file, "OLD1\nOLD2", "NEW1\nNEW2", false);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        assertFalse(reply.path("payload").path("result").path("deleted").asBoolean());
        assertEquals("头部\r\nNEW1\r\nNEW2\r\n尾部\r\n",
                Files.readString(file, StandardCharsets.UTF_8), "区间外逐字节保留,插入按 CRLF 转写");
    }

    @Test
    void notFoundRejectedWithoutTouchingDisk() throws Exception {
        Path file = seedFile("plain.txt", "alpha\nbeta\n");
        String message = errOf(replace(file, "zeta", "x", false), "BAD_PARAMS");
        assertTrue(message.contains("未找到"), "错误应可识别为未找到: " + message);
        assertEquals("alpha\nbeta\n", Files.readString(file, StandardCharsets.UTF_8),
                "未找到必须拒绝且不落盘(回滚失败而非盲改)");
    }

    @Test
    void notUniqueRejectedWithStableMessage() throws Exception {
        Path file = seedFile("dup.txt", "x\nDUP\ny\nDUP\n");
        String message = errOf(replace(file, "DUP", "x", false), "BAD_PARAMS");
        assertTrue(message.contains("不唯一"), "错误应携带「不唯一」稳定标记(前端据此扩大上下文重试): " + message);
        assertEquals("x\nDUP\ny\nDUP\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void emptyOldContentRejected() throws Exception {
        Path file = seedFile("empty-old.txt", "abc");
        errOf(replace(file, "", "x", false), "BAD_PARAMS");
        assertEquals("abc", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void binaryNulFileRejected() throws Exception {
        Path file = seedFile("bin.dat", "a\0b");
        String message = errOf(replace(file, "a", "x", false), "BAD_PARAMS");
        assertTrue(message.contains("二进制"), message);
    }

    @Test
    void missingFileNotFound() throws Exception {
        seedFile("keep.txt", "占位");
        Path missing = tempDir.resolve("missing.txt");
        String message = errOf(replace(missing, "a", "b", false), "NOT_FOUND");
        assertTrue(message.contains("文件不存在"), message);
        assertFalse(Files.exists(missing), "不得创建文件");
    }

    // ---- deleteWhenEmpty(回滚「新建文件」) ----

    @Test
    void deleteWhenEmptyWholeFileMatchDeletesFile() throws Exception {
        Path file = seedFile("created.txt", "新建内容\n第二行\n");
        JsonNode reply = replace(file, "新建内容\n第二行\n", "", true);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        assertTrue(reply.path("payload").path("result").path("deleted").asBoolean(),
                "整文件匹配 + 空替换 → 删除文件(回滚新建)");
        assertFalse(Files.exists(file), "文件应被删除而非留下空文件");
    }

    @Test
    void deleteWhenEmptyPartialMatchOnlyReplaces() throws Exception {
        // AI 新建后又有人追加内容:匹配段不覆盖全文件 → 只回滚 AI 部分,保留追加内容,不删除
        Path file = seedFile("appended.txt", "AI 部分\n用户追加\n");
        JsonNode reply = replace(file, "AI 部分\n", "", true);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        assertFalse(reply.path("payload").path("result").path("deleted").asBoolean());
        assertEquals("用户追加\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void deleteWhenEmptyIgnoredWhenNewContentNotEmpty() throws Exception {
        Path file = seedFile("updated.txt", "old\n");
        JsonNode reply = replace(file, "old\n", "new\n", true);
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        assertFalse(reply.path("payload").path("result").path("deleted").asBoolean());
        assertEquals("new\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    // ---- Raw 系列合法根收敛(与 readRaw/writeRaw 同构) ----

    @Test
    void relativePathRejected() throws Exception {
        errOf(replace(Path.of("rel/x.txt"), "a", "b", false), "BAD_PARAMS");
    }

    @Test
    void outsideAllowedRootsDeniedWithoutTouchingDisk() throws Exception {
        Path outsider = tempDir.resolveSibling("ea-replace-outsider-" + System.nanoTime());
        Files.createDirectories(outsider);
        Path target = outsider.resolve("pwned.txt");
        Files.writeString(target, "secret", StandardCharsets.UTF_8);
        errOf(replace(target, "secret", "x", false), "SANDBOX_DENIED");
        assertEquals("secret", Files.readString(target, StandardCharsets.UTF_8), "越界替换必须被拦截");
    }
}
