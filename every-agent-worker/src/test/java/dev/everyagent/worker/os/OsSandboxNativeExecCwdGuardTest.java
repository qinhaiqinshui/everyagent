package dev.everyagent.worker.os;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.SandboxProviderRegistry;

/**
 * {@code spawnNative} 的 cwd 护栏(§8.5,uninstall 墓碑预防侧):
 *
 * <ul>
 *   <li>cwd 位于外部插件目录内(含嵌套子目录) → 直接拒绝,不 spawn 进程,
 *       返回失败 ExecResult(文案指引改用工作区/临时目录);</li>
 *   <li>cwd 在插件目录外 → 护栏不触发,正常执行。</li>
 * </ul>
 *
 * <p>背景:Windows 上进程把 CWD 停在插件目录会锁死目录(文件可删、目录删不掉),
 * 令 plugin.uninstall 必败——护栏把这类调用拦在源头。
 */
class OsSandboxNativeExecCwdGuardTest {

    private static OsSandbox newSandbox(Path pluginsRoot) {
        WorkerProperties props = mock(WorkerProperties.class);
        when(props.resolvePluginsDir()).thenReturn(pluginsRoot);
        when(props.getSandbox()).thenReturn(new WorkerProperties.Sandbox());
        return new OsSandbox(props, mock(SandboxProviderRegistry.class));
    }

    @Test
    void cwdInsidePluginsRootIsRejected(@TempDir Path tmp) {
        OsSandbox sandbox = newSandbox(tmp.resolve("plugins"));

        ExecResult r = sandbox.spawnNative(new String[] { "ping", "127.0.0.1" },
                tmp.resolve("plugins"), Map.of(), 1000);

        assertTrue(r.stderr().contains("插件目录"), "拒绝文案应说明插件目录限制,实际: " + r.stderr());
        assertTrue(r.stderr().contains("工作区"), "拒绝文案应指引改用工作区/临时目录");
        assertFalse(r.aborted());
    }

    @Test
    void cwdInsideNestedPluginDirIsRejected(@TempDir Path tmp) {
        // 目录无需真实存在:护栏是路径前缀判定(拦截意图),不依赖文件系统状态
        OsSandbox sandbox = newSandbox(tmp.resolve("plugins"));

        ExecResult r = sandbox.spawnNative(new String[] { "ping", "127.0.0.1" },
                tmp.resolve("plugins").resolve("novel-reader").resolve("lib"), Map.of(), 1000);

        assertTrue(r.stderr().contains("插件目录"), "嵌套插件目录同样必须拒绝,实际: " + r.stderr());
    }

    @Test
    void cwdOutsidePluginsRootRunsNormally(@TempDir Path tmp) {
        OsSandbox sandbox = newSandbox(tmp.resolve("plugins"));

        // java 必在 PATH(maven 测试进程即运行于其上);只断言护栏未触发,不断言命令成败
        ExecResult r = sandbox.spawnNative(new String[] { "java", "-version" },
                tmp, Map.of(), 30_000);

        assertFalse(r.stderr().contains("插件目录"), "插件目录外的 cwd 不应被护栏拦截,实际: " + r.stderr());
    }
}
