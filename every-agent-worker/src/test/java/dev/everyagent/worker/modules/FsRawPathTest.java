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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * fs.readRaw/fs.writeRaw/fs.statRaw/fs.grantRawRoot 的契约单元测试(不依赖 Spring/真实 hub)。
 *
 * <p>两大回归面:
 * <ol>
 *   <li><b>机器绝对路径契约</b>:非绝对路径(相对路径,及 Windows 上的盘符相对路径
 *       {@code /x})必须显式 BAD_PARAMS 拒绝——此前 {@code Path.of("/x").toAbsolutePath()}
 *       在 Windows 上会静默解析到 worker 进程当前盘,把前端误传的 {@code /.everyagent/t.txt}
 *       变成 {@code C:\.everyagent\t.txt} 报 NOT_FOUND,writeRaw 更有写错盘的隐患。</li>
 *   <li><b>合法根收敛(H-2,§5.5/§14.6)</b>:Raw 系列不再对命名空间内任意连接全盘开放——
 *       越界(在册工作区根/外部授权根/技能目录/在途授权根/会话放行目录之外)一律
 *       SANDBOX_DENIED(消息含路径,前端可识别弹确认);用户经 fs.grantRawRoot 显式
 *       放行目录后重试放行;在册工作区内不受影响。</li>
 * </ol>
 * 真实 RpcDispatcher 分发 + mock HubLink 捕获出站帧,断言落在 wire 形态上(与 FsBrowseTest 同款)。
 */
class FsRawPathTest {

    @TempDir
    Path tempDir;

    private RpcDispatcher dispatcher;
    private RawRootRegistry rawGrants;

    @BeforeEach
    void setUp() {
        // Raw 系列不依赖 workspace 沙箱构造参数;WorkspaceManager/HubPool/GrantRegistry
        // 传 null(合法根按空集退化),会话放行注册表用真实实例(fs.grantRawRoot 用)。
        dispatcher = new RpcDispatcher(null, new WorkerProperties());
        rawGrants = new RawRootRegistry();
        new FsService(dispatcher, null, null, new WorkerProperties(), null, rawGrants);
    }

    /** 发起一次 RPC(经真实分发器,虚拟线程异步执行),等到唯一应答后返回 {event, payload}。 */
    private JsonNode call(String method, ObjectNode params) throws Exception {
        return call(method, params, "k");
    }

    /** 同 {@link #call},可指定连接命名空间 ownerKey(会话放行目录按其分区)。 */
    private JsonNode call(String method, ObjectNode params, String ownerKey) throws Exception {
        List<Object[]> frames = dispatch(method, params, ownerKey);
        return Json.obj().put("event", (String) frames.get(0)[1])
                .set("payload", (JsonNode) frames.get(0)[3]);
    }

    /**
     * 发起一次 RPC 并收集**全部**出站帧(rpc.data 分批在前、ok/err 终答在后),等到终答
     * 帧才返回(大文件流式分批读取断言用)。同一 reqId 至多一次 ok/err(§14.3)。
     */
    private List<Object[]> callAll(String method, ObjectNode params) throws Exception {
        return dispatch(method, params, "k");
    }

    private List<Object[]> dispatch(String method, ObjectNode params, String ownerKey) throws Exception {
        CountDownLatch terminal = new CountDownLatch(1);
        List<Object[]> out = new ArrayList<>();
        HubLink link = mock(HubLink.class);
        when(link.k()).thenReturn(ownerKey);
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
                .put("channel", Channels.workerCmd(ownerKey, "w"))
                .put("event", "rpc");
        frame.set("payload", payload);
        dispatcher.onHubMessage(link, frame);

        assertTrue(terminal.await(10, TimeUnit.SECONDS), method + " 未在 10s 内终答");
        return out;
    }

    /** 断言应答为 rpc.err 且 code 匹配,返回 message。 */
    private static String errOf(JsonNode reply, String code) {
        assertEquals("rpc.err", reply.path("event").asString(), reply.toString());
        assertEquals(code, reply.path("payload").path("code").asString(), reply.toString());
        return reply.path("payload").path("message").asString();
    }

    /** 造一个「工作区外」目录里的文件(模拟审计里的 C:\Users\public\x):与 tempDir 互不为祖先。 */
    private Path seedOutsiderFile() throws Exception {
        Path outsider = tempDir.resolveSibling("ea-raw-outsider-" + System.nanoTime());
        Files.createDirectories(outsider);
        return Files.writeString(outsider.resolve("secret.txt"), "secret", StandardCharsets.UTF_8);
    }

    // ---- 合法根收敛(H-2) ----

    @Test
    void readRawDeniedOutsideAllowedRoots() throws Exception {
        Path secret = seedOutsiderFile();
        JsonNode reply = call(RpcMethods.FS_READ_RAW, Json.obj().put("path", secret.toString()));
        String message = errOf(reply, "SANDBOX_DENIED");
        assertTrue(message.contains("越界"), "错误应可识别为越界: " + message);
        assertTrue(message.contains(secret.toString()), "错误应携带路径: " + message);
    }

    @Test
    void statRawDeniedOutsideAllowedRoots() throws Exception {
        Path secret = seedOutsiderFile();
        errOf(call(RpcMethods.FS_STAT_RAW, Json.obj().put("path", secret.toString())), "SANDBOX_DENIED");
    }

    @Test
    void writeRawDeniedOutsideAllowedRootsAndDoesNotTouchDisk() throws Exception {
        Path secret = seedOutsiderFile();
        Path target = secret.getParent().resolve("pwned.txt");
        JsonNode reply = call(RpcMethods.FS_WRITE_RAW,
                Json.obj().put("path", target.toString())
                        .put("contentBase64", Base64.getEncoder().encodeToString("x".getBytes())));
        errOf(reply, "SANDBOX_DENIED");
        assertFalse(Files.exists(target), "越界写必须被拦截,不得落盘");
    }

    @Test
    void grantRawRootThenReadStatWriteAllowed() throws Exception {
        Path secret = seedOutsiderFile();
        // ① 越界被拒(直连 worker 裸调即被拒,无任何授权旁路)
        errOf(call(RpcMethods.FS_READ_RAW, Json.obj().put("path", secret.toString())), "SANDBOX_DENIED");
        // ② 用户确认 → 放行所在目录(传文件路径,注册表取所在目录,返回 realpath)
        JsonNode grant = call(RpcMethods.FS_GRANT_RAW_ROOT, Json.obj().put("path", secret.toString()));
        assertEquals("rpc.ok", grant.path("event").asString(), grant.toString());
        assertEquals(secret.getParent().toRealPath().toString(),
                grant.path("payload").path("result").path("root").asString());
        // ③ 同路径重试 → 放行;同目录兄弟文件(写)亦放行
        JsonNode read = call(RpcMethods.FS_READ_RAW, Json.obj().put("path", secret.toString()));
        assertEquals("rpc.ok", read.path("event").asString(), read.toString());
        assertEquals("secret", new String(Base64.getDecoder().decode(
                read.path("payload").path("result").path("base64").asString()), StandardCharsets.UTF_8));
        assertEquals("rpc.ok", call(RpcMethods.FS_STAT_RAW, Json.obj().put("path", secret.toString()))
                .path("event").asString());
        Path sibling = secret.getParent().resolve("note.txt");
        assertEquals("rpc.ok", call(RpcMethods.FS_WRITE_RAW,
                        Json.obj().put("path", sibling.toString()).put("contentBase64", "aGk="))
                .path("event").asString());
        assertEquals("hi", Files.readString(sibling, StandardCharsets.UTF_8));
    }

    @Test
    void grantRawRootRejectsRelativePathAndMissingPath() throws Exception {
        errOf(call(RpcMethods.FS_GRANT_RAW_ROOT, Json.obj().put("path", "rel/dir")), "BAD_PARAMS");
        // 自身与父目录均缺失 → 拒(不回溯更宽泛祖先,防把缺失路径放大成大目录放行)
        Path nowhere = tempDir.resolve("a/b/c");
        errOf(call(RpcMethods.FS_GRANT_RAW_ROOT, Json.obj().put("path", nowhere.toString())), "BAD_PARAMS");
        // 新建文件目标(自身缺失、父目录存在)→ 放行父目录
        Path newFile = tempDir.resolve("brand-new.txt");
        assertEquals("rpc.ok", call(RpcMethods.FS_GRANT_RAW_ROOT,
                Json.obj().put("path", newFile.toString())).path("event").asString());
    }

    @Test
    void grantRawRootIsSessionScopedByOwnerKey() throws Exception {
        Path secret = seedOutsiderFile();
        assertEquals("rpc.ok", call(RpcMethods.FS_GRANT_RAW_ROOT,
                Json.obj().put("path", secret.getParent().toString()), "k").path("event").asString());
        // 另一命名空间(k2)不继承 k 的会话放行:仍越界
        errOf(call(RpcMethods.FS_READ_RAW, Json.obj().put("path", secret.toString()), "k2"),
                "SANDBOX_DENIED");
    }

    @Test
    void lexicalEscapeOutsideGrantedDirDenied() throws Exception {
        Path secret = seedOutsiderFile();
        assertEquals("rpc.ok", call(RpcMethods.FS_GRANT_RAW_ROOT,
                Json.obj().put("path", secret.getParent().toString())).path("event").asString());
        // 放行目录之外的旁路文件(经 .. 词法逃逸形态归一化后)仍拒绝
        Path escape = secret.getParent().getParent().resolve("escape-" + System.nanoTime() + ".txt");
        Files.writeString(escape, "e", StandardCharsets.UTF_8);
        errOf(call(RpcMethods.FS_READ_RAW, Json.obj().put("path", escape.toString())), "SANDBOX_DENIED");
    }

    @Test
    void workspaceRootAllowedWithoutSessionGrant() throws Exception {
        // 真实 WorkspaceManager:在册工作区根是 Raw 系列合法根,无需会话放行(既有 UX 不受影响)
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(tempDir.resolve("home").toString());
        WorkspaceManager wm = new WorkspaceManager(props, dispatcher, null, null, null);
        wm.init();
        Path ws = tempDir.resolve("ws");
        wm.resolveAndRegister(ws.toString());
        new FsService(dispatcher, wm, null, props, null, rawGrants);

        Path inside = Files.writeString(ws.resolve("in-ws.txt"), "ws-content", StandardCharsets.UTF_8);
        JsonNode read = call(RpcMethods.FS_READ_RAW, Json.obj().put("path", inside.toString()));
        assertEquals("rpc.ok", read.path("event").asString(), read.toString());
        assertEquals("ws-content", new String(Base64.getDecoder().decode(
                read.path("payload").path("result").path("base64").asString()), StandardCharsets.UTF_8));
        Path toWrite = ws.resolve("new.txt");
        assertEquals("rpc.ok", call(RpcMethods.FS_WRITE_RAW,
                        Json.obj().put("path", toWrite.toString()).put("contentBase64", "dwM="))
                .path("event").asString());
        assertTrue(Files.exists(toWrite), "工作区内写不受影响");
        // 系统目录(homeDir)不在合法根内:仍拒(工作区 ≠ 任意目录)
        Path homeFile = Files.writeString(props.resolveHomeDir().resolve("bootstrap.txt"), "cfg");
        errOf(call(RpcMethods.FS_READ_RAW, Json.obj().put("path", homeFile.toString())), "SANDBOX_DENIED");
    }

    // ---- 大文件分批(与 fs.read 语义一致,流式不整读) ----

    @Test
    void readRawLargeFileChunkedLikeFsRead() throws Exception {
        Path dir = tempDir.resolve("big");
        Files.createDirectories(dir);
        assertEquals("rpc.ok", call(RpcMethods.FS_GRANT_RAW_ROOT,
                Json.obj().put("path", dir.toString())).path("event").asString());
        // INLINE_MAX(256KB) < size 走分批;内容带规律便于重组校验
        int size = FsService.INLINE_MAX + FsService.CHUNK + 12345;
        byte[] data = new byte[size];
        for (int i = 0; i < size; i++) {
            data[i] = (byte) (i % 251);
        }
        Path file = dir.resolve("big.bin");
        Files.write(file, data);

        List<Object[]> frames = callAll(RpcMethods.FS_READ_RAW, Json.obj().put("path", file.toString()));
        List<byte[]> parts = new ArrayList<>();
        JsonNode terminal = null;
        boolean sawHasMoreTrue = false;
        for (Object[] f : frames) {
            String event = (String) f[1];
            JsonNode payload = (JsonNode) f[3];
            if ("rpc.data".equals(event)) {
                assertTrue(payload.path("hasMore").isBoolean(), "rpc.data 应带 hasMore");
                sawHasMoreTrue |= payload.path("hasMore").asBoolean();
                for (JsonNode item : payload.path("batch")) {
                    assertTrue(item.path("size").asInt() <= FsService.CHUNK,
                            "单批不得超过 CHUNK: " + item.path("size").asInt());
                    parts.add(Base64.getDecoder().decode(item.path("base64").asString()));
                }
            } else if ("rpc.ok".equals(event) || "rpc.err".equals(event)) {
                terminal = payload;
            }
        }
        assertNotNull(terminal, "必须有终答帧");
        assertTrue(terminal.path("code").isMissingNode(), "终答应为 rpc.ok 而非错误: " + terminal);
        assertEquals(size, terminal.path("result").path("size").asInt(), "末帧 ok 汇总 size");
        assertEquals(file.toString(), terminal.path("result").path("path").asString());
        assertTrue(sawHasMoreTrue, "多批场景应有 hasMore=true 的中间帧");
        // 重组后与原文件逐字节一致(流式分批不丢不重)
        int total = parts.stream().mapToInt(p -> p.length).sum();
        assertEquals(size, total, "分批字节总数应等于文件大小");
        byte[] merged = new byte[total];
        int cursor = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, merged, cursor, p.length);
            cursor += p.length;
        }
        assertArrayEquals(data, merged);
    }

    // ---- 机器绝对路径契约(既有回归) ----

    @Test
    void readRawAbsolutePathInlineBase64() throws Exception {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        // 会话放行 tempDir(文件标签页用户确认同语义)后内联读
        assertEquals("rpc.ok", call(RpcMethods.FS_GRANT_RAW_ROOT,
                Json.obj().put("path", tempDir.toString())).path("event").asString());
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
        // 缺失路径:按最近已存在祖先(tempDir,先放行)做边界判定,再报 NOT_FOUND
        assertEquals("rpc.ok", call(RpcMethods.FS_GRANT_RAW_ROOT,
                Json.obj().put("path", tempDir.toString())).path("event").asString());
        Path missing = tempDir.resolve("missing.txt");
        JsonNode reply = call(RpcMethods.FS_STAT_RAW, Json.obj().put("path", missing.toString()));
        String message = errOf(reply, "NOT_FOUND");
        assertTrue(message.contains("文件不存在"), message);
        Files.writeString(tempDir.resolve("keep.txt"), "占位,防止 tempDir 被优化掉");
        if (Files.exists(missing)) {
            fail("不应创建文件");
        }
    }

    private static void assertArrayEquals(byte[] expected, byte[] actual) {
        org.junit.jupiter.api.Assertions.assertArrayEquals(expected, actual);
    }
}

