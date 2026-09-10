package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

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
 * fs.browse 的 includeFiles 可选参数单元测试(不依赖 Spring/真实 hub,「@ 外部文件选择框」第一步):
 * 缺省仅列目录、响应无 kind/supportsFiles 字段(与旧契约逐字节兼容,老前端零感知);
 * includeFiles=true 时文件与目录一起列,条目带 kind(file/directory)、响应带 supportsFiles 能力标记;
 * 根/盘符列表同样带 kind;path 非目录仍走 NOT_FOUND。
 * 真实 RpcDispatcher 分发 + mock HubLink 捕获出站帧,断言落在 wire 形态上。
 */
class FsBrowseTest {

    @TempDir
    Path tempDir;

    private RpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // browse 不经 workspace 沙箱,WorkspaceManager/HubPool 不参与(FsService 构造仅注册方法)
        dispatcher = new RpcDispatcher(null, new WorkerProperties());
        new FsService(dispatcher, null, null, new WorkerProperties());
    }

    /** 造一个含 2 个子目录 + 2 个文件的目录,文件名穿插在目录名之间以验证按文件名混排。 */
    private Path seed() throws Exception {
        Path dir = tempDir.resolve("ws");
        Files.createDirectories(dir.resolve("a"));
        Files.createDirectories(dir.resolve("b"));
        Files.writeString(dir.resolve("m.txt"), "m");
        Files.writeString(dir.resolve("x.txt"), "x");
        return dir;
    }

    /** 发起一次 fs.browse(经真实分发器,虚拟线程异步执行),等到唯一应答后返回 {event, payload}。 */
    private JsonNode call(ObjectNode params) throws Exception {
        CountDownLatch replied = new CountDownLatch(1);
        List<Object[]> out = new ArrayList<>();
        HubLink link = mock(HubLink.class);
        when(link.k()).thenReturn("k");
        when(link.workerId()).thenReturn("w");
        doAnswer(inv -> {
            out.add(inv.getArguments());
            replied.countDown();
            return null;
        }).when(link).pub(any(), any(), any(), any(), any());

        ObjectNode payload = Json.obj().put("reqId", "req-" + System.nanoTime())
                .put("method", RpcMethods.FS_BROWSE);
        payload.set("params", params);
        ObjectNode frame = Json.obj()
                .put("channel", Channels.workerCmd("k", "w"))
                .put("event", "rpc");
        frame.set("payload", payload);
        dispatcher.onHubMessage(link, frame);

        assertTrue(replied.await(5, TimeUnit.SECONDS), "fs.browse 未在 5s 内应答");
        Object[] args = out.get(0); // 同一 reqId 至多一次 ok/err(§13.3)
        return Json.obj().put("event", (String) args[1]).set("payload", (JsonNode) args[3]);
    }

    /** 断言应答为 rpc.ok 并取出 result 节点。 */
    private static JsonNode okResult(JsonNode reply) {
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        return reply.path("payload").path("result");
    }

    private static List<String> names(JsonNode result) {
        List<String> ns = new ArrayList<>();
        result.path("entries").forEach(e -> ns.add(e.path("name").asString()));
        return ns;
    }

    @Test
    void defaultListsDirectoriesOnlyWithoutKind() throws Exception {
        Path dir = seed();
        JsonNode result = okResult(call(Json.obj().put("path", dir.toString())));
        assertFalse(result.path("isRoot").asBoolean(true));
        assertEquals(dir.toAbsolutePath().normalize().toString(), result.path("path").asString());
        // 缺省只列直接子目录,按文件名排序
        assertEquals(List.of("a", "b"), names(result));
        for (JsonNode e : result.path("entries")) {
            assertFalse(e.has("kind"), "缺省响应不应带 kind: " + e);
            assertFalse(e.has("dir"), "缺省响应字段集合应与旧契约一致: " + e);
            assertTrue(e.has("path") && e.has("name"), e.toString());
        }
        assertFalse(result.has("supportsFiles"), "缺省响应不应带能力标记: " + result);
    }

    @Test
    void includeFilesListsFilesWithKindAndCapability() throws Exception {
        Path dir = seed();
        JsonNode result = okResult(call(Json.obj()
                .put("path", dir.toString())
                .put("includeFiles", true)));
        assertTrue(result.path("supportsFiles").asBoolean(false), "能力标记供前端探测: " + result);
        // 目录与文件混排,按文件名排序(不做目录/文件分组)
        assertEquals(List.of("a", "b", "m.txt", "x.txt"), names(result));
        for (JsonNode e : result.path("entries")) {
            String expect = e.path("name").asString().endsWith(".txt") ? "file" : "directory";
            assertEquals(expect, e.path("kind").asString(), e.toString());
        }
    }

    @Test
    void stringIncludeFilesAccepted() throws Exception {
        // 前端误以字符串 "true" 携带时同样生效(按 optStrParam 模式解析)
        Path dir = seed();
        JsonNode result = okResult(call(Json.obj()
                .put("path", dir.toString())
                .put("includeFiles", "true")));
        assertTrue(result.path("supportsFiles").asBoolean(false), result.toString());
        assertTrue(names(result).contains("m.txt"), "应包含文件条目: " + result);
    }

    @Test
    void rootListCarriesKindAndCapabilityWithIncludeFiles() throws Exception {
        JsonNode result = okResult(call(Json.obj().put("includeFiles", true)));
        assertTrue(result.path("isRoot").asBoolean(false));
        assertTrue(result.path("supportsFiles").asBoolean(false), result.toString());
        assertFalse(result.path("entries").isEmpty(), "至少返回一个根/盘符");
        for (JsonNode e : result.path("entries")) {
            assertEquals("directory", e.path("kind").asString(), "根/盘符必为目录: " + e);
            assertTrue(e.has("path") && e.has("name"), e.toString());
        }
    }

    @Test
    void rootListDefaultStaysByteCompatible() throws Exception {
        JsonNode result = okResult(call(Json.obj()));
        assertTrue(result.path("isRoot").asBoolean(false));
        assertFalse(result.has("supportsFiles"), "缺省响应不应带能力标记: " + result);
        for (JsonNode e : result.path("entries")) {
            assertFalse(e.has("kind"), "缺省根列表不应带 kind: " + e);
        }
    }

    @Test
    void nonDirectoryPathStillNotFoundWithIncludeFiles() throws Exception {
        Path file = tempDir.resolve("plain.txt");
        Files.writeString(file, "not a dir");
        JsonNode reply = call(Json.obj().put("path", file.toString()).put("includeFiles", true));
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        assertEquals("NOT_FOUND", reply.path("payload").path("code").asString());
        assertTrue(reply.path("payload").path("message").asString().contains("不是目录"),
                reply.toString());
    }
}
