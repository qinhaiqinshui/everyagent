package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.os.wsl.WslUmounter;
import dev.everyagent.worker.rpc.BadParamsException;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.task.TaskManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 启动自检(缺失工作区)与 workspaces.resolveMissing 落定(架构 §5.9)。
 * 不依赖 Spring/hub:真实 WorkspaceManager + 临时目录,HubPool/RpcDispatcher/TaskManager 打桩。
 * 验证:init 只标记「从 workspaces.json 载入的缺失条目」;缺失 resolve 拒绝;redirect 纠正路径
 * 并迁移任务归属;delete 删除注册并级联任务;默认工作区不可删除。
 */
class WorkspaceManagerTest {

    @TempDir
    Path tempDir;

    private WorkerProperties props(Path home, Path defaultWs) {
        WorkerProperties p = new WorkerProperties();
        p.setHomeDir(home.toString());
        p.setWorkspaceRoot(defaultWs.toString());
        return p;
    }

    private RpcContext ctx(String method, ObjectNode params) {
        HubLink link = mock(HubLink.class);
        when(link.k()).thenReturn("k");
        when(link.workerId()).thenReturn("w");
        return new RpcContext(link, "req-" + System.nanoTime(), method, params);
    }

    /** 打桩 WslUmounter(伪造 runner,不触发真实 wsl.exe;恒返回 0 = 命令成功)。 */
    private WslUmounter noUmount() {
        return new WslUmounter(new WorkerProperties(), (argv, timeoutMs) -> 0);
    }

    private void invokeResolveMissing(WorkspaceManager wm, ObjectNode params) throws Exception {
        Method m = WorkspaceManager.class.getDeclaredMethod("rpcResolveMissing", RpcContext.class);
        m.setAccessible(true);
        try {
            m.invoke(wm, ctx("workspaces.resolveMissing", params));
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    /** 预置注册表 workspaces/workspaces.json:一个有效、一个缺失(模拟用户移动目录后重启)。 */
    private void seedRegistry(Path home, Path valid, Path missing) throws Exception {
        Path registryDir = home.resolve("workspaces");
        Files.createDirectories(registryDir);
        ArrayNode arr = Json.arr();
        arr.add(Json.obj().put("root", valid.toAbsolutePath().normalize().toString()).put("addedTs", 1000L));
        arr.add(Json.obj().put("root", missing.toAbsolutePath().normalize().toString()).put("addedTs", 2000L));
        Files.writeString(registryDir.resolve("workspaces.json"), Json.write(arr));
    }

    @Test
    void startupMarksMissing_ValidResolves_MissingBlocked() throws Exception {
        Path home = tempDir.resolve("home");
        Path valid = tempDir.resolve("valid-ws");
        Path moved = tempDir.resolve("moved-ws");
        Files.createDirectories(valid);
        seedRegistry(home, valid, moved);

        TaskManager tm = mock(TaskManager.class);
        ObjectProvider<TaskManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tm);
        WorkspaceManager wm = new WorkspaceManager(props(home, valid),
                mock(RpcDispatcher.class), mock(HubPool.class), provider, noUmount());
        wm.init();

        assertEquals(2, wm.list().size());
        // 有效工作区照常解析。
        assertDoesNotThrow(() -> wm.resolve(valid.toString()));
        // 缺失工作区 resolve 被拒(避免沙箱挂载失败/静默新建空目录)。
        BadParamsException ex = assertThrows(BadParamsException.class, () -> wm.resolve(moved.toString()));
        assertTrue(ex.getMessage().contains("纠正路径"), ex.getMessage());
    }

    @Test
    void redirectRepairsPathAndMigratesTasks() throws Exception {
        Path home = tempDir.resolve("home");
        Path valid = tempDir.resolve("valid-ws");
        Path moved = tempDir.resolve("moved-ws");
        Files.createDirectories(valid);
        seedRegistry(home, valid, moved);

        TaskManager tm = mock(TaskManager.class);
        ObjectProvider<TaskManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tm);
        WorkspaceManager wm = new WorkspaceManager(props(home, valid),
                mock(RpcDispatcher.class), mock(HubPool.class), provider, noUmount());
        wm.init();

        Path newDir = tempDir.resolve("new-home");
        Files.createDirectories(newDir);
        String newKey = newDir.toAbsolutePath().normalize().toString();
        ObjectNode params = Json.obj()
                .put("root", moved.toAbsolutePath().normalize().toString())
                .put("action", "redirect")
                .put("newRoot", newDir.toString());
        invokeResolveMissing(wm, params);

        // 注册表去掉旧路径、换成新路径;缺失标记清除;任务归属迁移。
        assertTrue(wm.list().stream().noneMatch(r -> r.root().equals(moved.toAbsolutePath().normalize().toString())));
        assertTrue(wm.list().stream().anyMatch(r -> r.root().equals(newKey)));
        assertDoesNotThrow(() -> wm.resolve(newKey));
        verify(tm).redirectWorkspace(moved.toAbsolutePath().normalize().toString(), newKey);
    }

    @Test
    void deleteRemovesRegistryAndCascadesTasks_DefaultNotDeletable() throws Exception {
        Path home = tempDir.resolve("home");
        Path valid = tempDir.resolve("valid-ws");
        Path moved = tempDir.resolve("moved-ws");
        Files.createDirectories(valid);
        seedRegistry(home, valid, moved);

        TaskManager tm = mock(TaskManager.class);
        ObjectProvider<TaskManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(tm);
        WorkspaceManager wm = new WorkspaceManager(props(home, valid),
                mock(RpcDispatcher.class), mock(HubPool.class), provider, noUmount());
        wm.init();

        // 默认工作区(valid)不可删除。
        ObjectNode defaultDel = Json.obj()
                .put("root", valid.toAbsolutePath().normalize().toString())
                .put("action", "delete");
        assertThrows(BadParamsException.class, () -> invokeResolveMissing(wm, defaultDel));

        // 缺失的非默认工作区可删除,且级联删除任务(按稳定 id)。
        ObjectNode del = Json.obj()
                .put("root", moved.toAbsolutePath().normalize().toString())
                .put("action", "delete");
        invokeResolveMissing(wm, del);
        assertTrue(wm.list().stream().noneMatch(r -> r.root().equals(moved.toAbsolutePath().normalize().toString())));
        verify(tm).deleteByWorkspaceId(org.mockito.ArgumentMatchers.anyString());
    }
}