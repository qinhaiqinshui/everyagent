package dev.everyagent.plugin.ripgrepTool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link RgResolver} 三档解析（架构 §7.10「程序附属文件」）：插件根 {@code bin/} →
 * 程序根 {@code runtime/bin/} → 系统 PATH。与 CodexRg 的关键差异：三档命中都必须携带
 * 二进制绝对路径（含系统 PATH 档），因为 rg 工具要以绝对路径委托执行。
 */
class RgResolverTest {

    @TempDir
    Path tempDir;

    /** 与实现同口径的二进制文件名（Windows=rg.exe，其余=rg）。 */
    private static String rgName() {
        return System.getProperty("os.name").toLowerCase().contains("win") ? "rg.exe" : "rg";
    }

    /** 造一个含 {@code bin/<rg>} 的目录树，返回其根（用作假 pluginDir 或假 runtimeDir）。 */
    private Path fakeBinRoot(String dirName) throws Exception {
        Path bin = tempDir.resolve(dirName).resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve(rgName()), "fake-rg");
        return tempDir.resolve(dirName);
    }

    @Test
    void pluginBundledBinaryWins() throws Exception {
        Path pluginDir = fakeBinRoot("plugin");
        Path runtimeDir = fakeBinRoot("runtime");
        RgResolver.Resolved rg = RgResolver.resolve(pluginDir, runtimeDir);
        assertEquals(pluginDir.resolve("bin").resolve(rgName()), rg.binary(),
                "插件自带位优先（rg 归属下放插件的既有口径），binary 携带该文件路径");
        assertTrue(rg.available());
    }

    @Test
    void fallsBackToProgramRuntimeBin() throws Exception {
        // 插件根存在但没有 bin/ ——正是 desktop 打包态漏搬 bin 的真实形态
        Path pluginDir = Files.createDirectories(tempDir.resolve("plugin-no-bin"));
        Path runtimeDir = fakeBinRoot("runtime-with-bin");
        RgResolver.Resolved rg = RgResolver.resolve(pluginDir, runtimeDir);
        assertEquals(runtimeDir.resolve("bin").resolve(rgName()), rg.binary(),
                "插件未自带 → 回退程序根 runtime/bin（与核心 RipgrepBinary 同一位置）");
        assertTrue(rg.available(), "回退命中即算可用，工具描述可如实声明 rg 可用");
    }

    @Test
    void allThreeTiersMissingIsUnavailable() throws Exception {
        // 宿主 PATH 是否含 rg 不可控：含 rg 时无法在本机构造「三档皆无」，跳过
        assumeFalse(onHostPath(), "本机 PATH 已含 rg，三档皆无场景不可构造");
        Path emptyPlugin = Files.createDirectories(tempDir.resolve("empty-plugin"));
        Path emptyRuntime = Files.createDirectories(tempDir.resolve("empty-runtime"));
        RgResolver.Resolved rg = RgResolver.resolve(emptyPlugin, emptyRuntime);
        assertNull(rg.binary(), "三档皆无 → binary=null");
        assertFalse(rg.available(), "三档皆无 → 不可用（不抛异常、不阻断启动）");
        RgResolver.Resolved nulls = RgResolver.resolve(null, null);
        assertNull(nulls.binary(), "null 目录不得抛异常，语义等同空目录");
        assertFalse(nulls.available());
    }

    @Test
    void pathTierCarriesNonNullAbsolutePath() throws Exception {
        // 系统 PATH 档在本机不一定可控：PATH 无 rg 时条件化跳过
        assumeTrue(onHostPath(), "本机 PATH 不含 rg，PATH 档场景不可构造");
        Path emptyPlugin = Files.createDirectories(tempDir.resolve("empty-plugin"));
        Path emptyRuntime = Files.createDirectories(tempDir.resolve("empty-runtime"));
        RgResolver.Resolved rg = RgResolver.resolve(emptyPlugin, emptyRuntime);
        assertTrue(rg.available(), "PATH 已含 rg → 可用");
        // 与 CodexRg 的关键差异：PATH 命中也必须返回非 null 绝对路径（rg 工具以它委托执行），
        // 而非 CodexRg 的 Rg(null, true)
        assertNotNull(rg.binary(), "PATH 档命中必须携带二进制路径，不得丢弃");
        assertTrue(rg.binary().isAbsolute(), "PATH 档命中必须给出绝对路径");
        assertTrue(Files.isReadable(rg.binary()), "PATH 档命中的路径必须真实可读");
        RgResolver.Resolved nulls = RgResolver.resolve(null, null);
        assertEquals(rg.binary(), nulls.binary(), "null 目录不得抛异常，语义等同空目录");
        assertTrue(nulls.available());
    }

    /** 宿主 PATH 是否已含 rg（复刻实现第三档口径，供上面两条条件化测试判定）。 */
    private static boolean onHostPath() {
        String path = System.getenv("Path");
        if (path == null || path.isBlank()) {
            path = System.getenv("PATH");
        }
        if (path == null) {
            return false;
        }
        for (String dir : path.split(java.util.regex.Pattern.quote(File.pathSeparator))) {
            if (!dir.isBlank() && Files.isReadable(Path.of(dir).resolve(rgName()))) {
                return true;
            }
        }
        return false;
    }
}
