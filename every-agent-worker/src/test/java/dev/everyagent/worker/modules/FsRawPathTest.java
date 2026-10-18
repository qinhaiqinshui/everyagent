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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * fs.readRaw/fs.statRaw/fs.writeRaw 的机器绝对路径契约单元测试(不依赖 Spring/真实 hub)。
 * 核心回归:非绝对路径(相对路径,及 Windows 上的盘符相对路径 {@code /x})必须显式
 * BAD_PARAMS 拒绝——此前 {@code Path.of("/x").toAbsolutePath()} 在 Windows 上会静默
 * 解析到 worker 进程当前盘({@code C:\x}),把前端误传的 {@code /.everyagent/t.txt}
 * 变成 {@code C:\.everyagent\t.txt} 报 NOT_FOUND,writeRaw 更有写错盘的隐患。
 * 真实 RpcDispatcher 分发 + mock HubLink 捕获出站帧,断言落在 wire 形态上(与 FsBrowseTest 同款)。
 */
class FsRawPathTest {

    @TempDir
    Path tempDir;

    private RpcDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        // readRaw 族不经 workspace 沙箱,WorkspaceManager/HubPool/GrantRegistry 不参与(FsService 构造仅注册方法)
        dispatcher = new RpcDispatcher(null, new WorkerProperties());
        new FsService(dispatcher, null, null, new WorkerProperties(), null);
    }

    /** 发起一次 RPC(经真实分发器,虚拟线程异步执行),等到唯一应答后返回 {event, payload}。 */
    private JsonNode call(String method, ObjectNode params) throws Exception {
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
                .put("method", method);
        payload.set("params", params);
        ObjectNode frame = Json.obj()
                .put("channel", Channels.workerCmd("k", "w"))
                .put("event", "rpc");
        frame.set("payload", payload);
        dispatcher.onHubMessage(link, frame);

        assertTrue(replied.await(5, TimeUnit.SECONDS), method + " 未在 5s 内应答");
        Object[] args = out.get(0); // 同一 reqId 至多一次 ok/err(§14.3)
        return Json.obj().put("event", (String) args[1]).set("payload", (JsonNode) args[3]);
    }

    /** 断言应答为 rpc.err 且 code 匹配,返回 message。 */
    private static String errOf(JsonNode reply, String code) {
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        assertEquals(code, reply.path("payload").path("code").asString(), reply.toString());
        return reply.path("payload").path("message").asString();
    }

    @Test
    void readRawAbsolutePathInlineBase64() throws Exception {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        JsonNode reply = call(RpcMethods.FS_READ_RAW, Json.obj().put("path", file.toString()));
        assertEquals("rpc.ok", reply.path("event").asString(), reply.toString());
        JsonNode result = reply.path("payload").path("result");
        assertEquals("hello", new String(
                Base64.getDecoder().decode(result.path("base64").asString()), StandardCharsets.UTF_8));
        assertEquals(5, result.path("size").asInt());
    }

    @Test
    void relativePathRejectedByAllRawMethods() throws Exception {
        for (String method : List.of(RpcMethods.FS_READ_RAW, RpcMethods.FS_STAT_RAW, RpcMethods.FS_WRITE_RAW)) {
            JsonNode reply = call(method, Json.obj().put("path", "rel/x.txt"));
            String message = errOf(reply, "BAD_PARAMS");
            assertTrue(message.contains("机器绝对路径"), method + " 应显式拒绝相对路径: " + message);
        }
    }

    @Test
    void windowsDriveRelativeRejectedInsteadOfSilentCwdResolution() throws Exception {
        // "/x" 仅在 Windows 上是盘符相对路径(Unix 上是合法绝对路径,按平台跳过)
        assumeFalse(Path.of("/x").isAbsolute(), "本平台 /x 已是绝对路径,无需此回归");
        JsonNode reply = call(RpcMethods.FS_READ_RAW, Json.obj().put("path", "/.everyagent/t.txt"));
        String message = errOf(reply, "BAD_PARAMS");
        assertTrue(message.contains("机器绝对路径"),
                "盘符相对路径不得静默解析到 worker 当前盘: " + message);
    }

    @Test
    void writeRawRejectsDriveRelativeWithoutTouchingDisk() throws Exception {
        assumeFalse(Path.of("/x").isAbsolute(), "本平台 /x 已是绝对路径,无需此回归");
        JsonNode reply = call(RpcMethods.FS_WRITE_RAW,
                Json.obj().put("path", "/.everyagent/t.txt").put("contentBase64", ""));
        errOf(reply, "BAD_PARAMS");
        // 不依赖进程当前盘是否存在 .everyagent 目录;只要拒绝即不可能写错盘
    }

    @Test
    void statRawMissingAbsoluteFileStillNotFound() throws Exception {
        Path missing = tempDir.resolve("missing.txt");
        JsonNode reply = call(RpcMethods.FS_STAT_RAW, Json.obj().put("path", missing.toString()));
        String message = errOf(reply, "NOT_FOUND");
        assertTrue(message.contains("文件不存在"), message);
        Files.writeString(tempDir.resolve("keep.txt"), "占位,防止 tempDir 被优化掉");
        if (Files.exists(missing)) {
            fail("不应创建文件");
        }
    }
}
