package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.config.WorkerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WslCommon 资源三级链单测(架构 §7.10/§7.17):插件目录 {@code <pluginDir>/wsl/} →
 * 共享 runtime {@code resolveRuntimeDir()/wsl/}(打包 desktop 态,插件资源经构建链并入)
 * → 配置 {@code worker.sandbox.wsl.tarball}(相对系统目录)。覆盖 tarballFor(rootfs
 * 镜像)与 resolveRunner(eagent-run.py)两条链——打包 desktop 态插件目录不含 wsl/ 资源,
 * 若无共享 runtime 层级,启用该插件的打包安装将定位不到镜像与启动器(历史断链)。
 *
 * <p>约束(§14.9):插件对 worker 零依赖——WorkerConfig 以 Mockito 桩实现。
 */
class WslCommonResourceChainTest {

    @TempDir
    Path pluginDir;

    @TempDir
    Path runtimeDir;

    @TempDir
    Path homeDir;

    private WorkerConfig props(String tarballConfig) {
        WorkerConfig props = mock(WorkerConfig.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        when(props.resolveRuntimeDir()).thenReturn(runtimeDir);
        when(props.resolveHomeDir()).thenReturn(homeDir);
        when(props.sandbox().wsl().tarball()).thenReturn(tarballConfig);
        return props;
    }

    private Path touch(Path base, String... rest) throws IOException {
        Path p = base;
        for (String r : rest) {
            p = p.resolve(r);
        }
        Files.createDirectories(p.getParent());
        Files.writeString(p, "stub");
        return p;
    }

    // ---- tarballFor:三级链 ----

    @Test
    void tarballPrefersPluginDirOverSharedRuntime() throws IOException {
        Path bundled = touch(pluginDir, "wsl", "eagent-rootfs.tar.gz");
        touch(runtimeDir, "wsl", "eagent-rootfs.tar.gz");
        assertEquals(bundled, WslCommon.tarballFor(props("wsl/eagent-rootfs.tar.gz"), pluginDir),
                "插件目录自带镜像(.eap/开发态)优先于共享 runtime 与配置");
    }

    @Test
    void tarballFallsBackToSharedRuntimeWhenPluginDirLacksIt() throws IOException {
        Path shared = touch(runtimeDir, "wsl", "eagent-rootfs.tar.gz");
        assertEquals(shared, WslCommon.tarballFor(props(""), pluginDir),
                "打包 desktop 态插件目录无 wsl/ 资源,应命中共享 runtime/wsl/");
    }

    @Test
    void tarballFallsBackToConfiguredPathRelativeToHome() throws IOException {
        Path cfg = touch(homeDir, "wsl", "eagent-rootfs.tar.gz");
        assertEquals(cfg, WslCommon.tarballFor(props("wsl/eagent-rootfs.tar.gz"), pluginDir),
                "三级:配置路径相对系统目录解析");
    }

    @Test
    void tarballNullWhenAllLevelsMissing() {
        assertNull(WslCommon.tarballFor(props(""), pluginDir),
                "三级全缺 = 自动导入关闭(null)");
        assertNull(WslCommon.tarballFor(props(""), null),
                "pluginDir 未知时仍按 runtime/配置 链兜底,全缺返回 null");
    }

    // ---- resolveRunner:同一三级链 ----

    @Test
    void runnerPrefersPluginDir() throws IOException {
        Path bundled = touch(pluginDir, "wsl", "eagent-run.py");
        touch(runtimeDir, "wsl", "eagent-run.py");
        assertEquals(bundled, WslCommon.resolveRunner(props(""), pluginDir));
    }

    @Test
    void runnerFallsBackToSharedRuntime() throws IOException {
        Path shared = touch(runtimeDir, "wsl", "eagent-run.py");
        assertEquals(shared, WslCommon.resolveRunner(props(""), pluginDir),
                "打包态 eagent-run.py 在共享 runtime/wsl/ 命中");
        assertEquals(shared, WslCommon.resolveRunner(props(""), null),
                "pluginDir 未知时共享 runtime 兜底");
    }

    @Test
    void runnerThrowsWhenAllLevelsMissing() {
        assertThrows(IOException.class, () -> WslCommon.resolveRunner(props(""), pluginDir),
                "三级全缺应抛 IOException(带已查位置说明)");
    }
}
