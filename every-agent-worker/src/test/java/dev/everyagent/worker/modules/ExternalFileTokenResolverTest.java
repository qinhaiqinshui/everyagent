package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.os.wsl.WslUmounter;
import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.slash.SlashTokenEncoder;
import dev.everyagent.worker.slash.SlashTokenHandler;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * system.external_file 提交解析(§7.16)分支覆盖:payload 缺 absolutePath / 无任务上下文
 * (保留原串)、路径不存在(失效文本)、工作区内(退化为相对路径明文 + 不注册)、工作区外
 * 目录/文件(注册自身/父目录 + 替换文本)、过宽根拒收、工作区未注册(注册失败文本)、
 * 幂等重解析。真实 WorkspaceManager + @TempDir 真实文件系统;沙箱后缀形态由纯函数
 * {@code externalRefText} 单测钉住(测试宿主非 Windows → OsSandbox 恒 DIRECT,不附后缀)。
 */
class ExternalFileTokenResolverTest {

    @TempDir
    Path tempDir;

    WorkspaceManager wm;
    SlashTokenHandler handler;
    Path ws;

    @BeforeEach
    void setUp() throws IOException {
        ws = tempDir.resolve("ws");
        Files.createDirectories(ws);
        WorkerProperties p = props(tempDir.resolve("home"), tempDir.resolve("data"), ws);
        @SuppressWarnings("unchecked")
        ObjectProvider<TaskManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(TaskManager.class));
        wm = new WorkspaceManager(p, mock(RpcDispatcher.class), mock(HubPool.class), provider,
                new WslUmounter(p, (argv, timeoutMs) -> 0));
        wm.init();
        // 测试宿主非 Windows → OsSandbox 后端恒 DIRECT(isWslDirect/isWslBwrap 均 false)
        handler = new SlashTokenHandler(List.of(new ExternalFileTokenResolver(wm, new OsSandbox(p, wm))));
    }

    private static WorkerProperties props(Path home, Path data, Path defaultWs) {
        WorkerProperties p = new WorkerProperties();
        p.setHomeDir(home.toString());
        p.setDataDir(data.toString());
        p.setWorkspaceRoot(defaultWs.toString());
        return p;
    }

    private static TaskEntry task(String workspaceRoot) {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "http://localhost:9999/v1", "m", null);
        return new TaskEntry("t-1", "任务", snap, "k", workspaceRoot, "main-agent", 10_000);
    }

    /** 构造 external_file opaque token(absolutePath 传 null = payload 缺该字段)。 */
    private static String token(String absolutePath, String kind) {
        ObjectNode payload = Json.obj().put("fileName", "data.csv").put("kind", kind);
        if (absolutePath != null) {
            payload.put("absolutePath", absolutePath);
        }
        return SlashTokenEncoder.buildToken(ExternalFileTokenResolver.KIND, "data.csv",
                absolutePath == null ? "" : absolutePath, payload);
    }

    // ---- 兜底:保留原串 ----

    @Test
    void missingAbsolutePathOrTaskKeepsOriginalToken() {
        String t = token(null, "file");
        assertEquals(t, handler.resolve(t, task(ws.toString()))); // payload 缺 absolutePath
        String ok = token(tempDir.resolve("refs").toString(), "directory");
        assertEquals(ok, handler.resolve(ok)); // 无任务上下文:resolver 返回 null → handler 保留原串
    }

    // ---- 路径不存在 ----

    @Test
    void nonexistentPathYieldsInvalidText() {
        String missing = tempDir.resolve("nope/missing.csv").toString();
        assertEquals("（外部引用已失效：" + missing + "）",
                handler.resolve(token(missing, "file"), task(ws.toString())));
    }

    // ---- 工作区内:退化相对路径,不注册 ----

    @Test
    void insideWorkspaceDegradesToRelativePath() throws IOException {
        Path file = ws.resolve("docs/a.md");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        assertEquals(" docs/a.md ",
                handler.resolve(token(file.toString(), "file"), task(ws.toString())));
        assertEquals(" docs ",
                handler.resolve(token(ws.resolve("docs").toString(), "directory"), task(ws.toString())));
        assertTrue(wm.externalRootsOf(ws.toString()).isEmpty());
    }

    // ---- 工作区外:注册授权根 + 替换文本 ----

    @Test
    void outsideDirectoryRegistersItself() throws IOException {
        Path dir = tempDir.resolve("refs");
        Files.createDirectories(dir);
        String out = handler.resolve(token(dir.toString(), "directory"), task(ws.toString()));
        assertEquals(" [外部引用] " + dir.toRealPath() + " ", out);
        assertFalse(out.contains("wsl 沙箱内")); // 非 wsl 后端不附沙箱内路径
        assertEquals(List.of(dir.toRealPath()), wm.externalRootsOf(ws.toString()));
    }

    @Test
    void outsideFileRegistersParentDirectory() throws IOException {
        Path dir = tempDir.resolve("refs");
        Files.createDirectories(dir);
        Path file = dir.resolve("data.csv");
        Files.writeString(file, "x");
        String out = handler.resolve(token(file.toString(), "file"), task(ws.toString()));
        assertEquals(" [外部引用] " + file.toRealPath() + " ", out); // 文本仍指文件本身
        assertEquals(List.of(dir.toRealPath()), wm.externalRootsOf(ws.toString())); // 授权根 = 父目录
    }

    @Test
    void reresolveIsIdempotent() throws IOException {
        Path dir = tempDir.resolve("refs");
        Files.createDirectories(dir);
        TaskEntry t = task(ws.toString());
        handler.resolve(token(dir.toString(), "directory"), t);
        assertEquals(" [外部引用] " + dir.toRealPath() + " ",
                handler.resolve(token(dir.toString(), "directory"), t)); // skipped 同样视为成功
        assertEquals(1, wm.externalRootsOf(ws.toString()).size());
    }

    // ---- 拒收分支 ----

    @Test
    void overBroadRootRejected() {
        // 工作区祖先目录(tempDir ⊃ ws)作为授权根过宽:拒收且不注册
        assertEquals("（外部路径被拒：授权根过于宽泛 " + tempDir + "）",
                handler.resolve(token(tempDir.toString(), "directory"), task(ws.toString())));
        assertTrue(wm.externalRootsOf(ws.toString()).isEmpty());
    }

    @Test
    void unregisteredWorkspaceFailsRegistration() throws IOException {
        Path other = tempDir.resolve("other"); // 存在但未注册的工作区
        Files.createDirectories(other);
        Path file = tempDir.resolve("refs/data.csv");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
        assertEquals("（外部引用注册失败：" + file + "）",
                handler.resolve(token(file.toString(), "file"), task(other.toString())));
    }

    // ---- 沙箱内路径后缀(纯函数钉住文本形态) ----

    @Test
    void wslBackendSuffixForms() {
        Path win = Path.of("C:\\docs\\data.csv");
        assertEquals(" [外部引用] C:\\docs\\data.csv（wsl 沙箱内: /c/docs/data.csv） ",
                ExternalFileTokenResolver.externalRefText(win, true, false)); // wsl-direct
        assertEquals(" [外部引用] C:\\docs\\data.csv（wsl 沙箱内: /mnt/c/docs/data.csv） ",
                ExternalFileTokenResolver.externalRefText(win, false, true)); // wsl-bwrap
        assertEquals(" [外部引用] C:\\docs\\data.csv ",
                ExternalFileTokenResolver.externalRefText(win, false, false)); // 其余后端不附
    }
}
