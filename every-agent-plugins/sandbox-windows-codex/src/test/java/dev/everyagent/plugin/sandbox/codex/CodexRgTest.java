package dev.everyagent.plugin.sandbox.codex;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodexRg} 三档解析（架构 §7.10「程序附属文件」）：插件根 {@code bin/} →
 * 程序根 {@code runtime/bin/} → 系统 PATH。第二档是 desktop 打包态的实际命中位，
 * 没有它，一旦 staging 漏搬插件 bin/ 就会让安装包里 rg 静默缺席。
 */
class CodexRgTest {

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
        CodexRg.Rg rg = CodexRg.resolve(pluginDir, runtimeDir);
        assertEquals(pluginDir.resolve("bin").resolve(rgName()), rg.injectPath(),
                "插件自带位优先（rg 归属下放插件的既有口径）");
        assertTrue(rg.available());
    }

    @Test
    void fallsBackToProgramRuntimeBin() throws Exception {
        // 插件根存在但没有 bin/ ——正是 desktop 打包态漏搬 bin 的真实形态
        Path pluginDir = Files.createDirectories(tempDir.resolve("plugin-no-bin"));
        Path runtimeDir = fakeBinRoot("runtime-with-bin");
        CodexRg.Rg rg = CodexRg.resolve(pluginDir, runtimeDir);
        assertEquals(runtimeDir.resolve("bin").resolve(rgName()), rg.injectPath(),
                "插件未自带 → 回退程序根 runtime/bin（与核心 RipgrepBinary 同一位置）");
        assertTrue(rg.available(), "回退命中即算可用，工具描述可如实声明 rg 可用");
    }

    @Test
    void missingBundledFilesFallToPathProbeWithoutCrash() throws Exception {
        Path emptyPlugin = Files.createDirectories(tempDir.resolve("empty-plugin"));
        Path emptyRuntime = Files.createDirectories(tempDir.resolve("empty-runtime"));
        CodexRg.Rg rg = CodexRg.resolve(emptyPlugin, emptyRuntime);
        // 前两档皆无 → 可用性完全由 PATH 档决定；宿主 PATH 不可控，故按同口径自查对照
        assertEquals(onHostPath(), rg.available(),
                "PATH 档判定必须与宿主 PATH 实况一致（不得凭空判可用/不可用）");
        CodexRg.Rg nulls = CodexRg.resolve(null, null);
        assertEquals(rg.available(), nulls.available(), "null 目录不得抛异常，语义等同空目录");
        assertEquals(null, nulls.injectPath(), "PATH 已含或彻底不可用时都不注入");
    }

    /** 宿主 PATH 是否已含 rg（复刻实现第三档口径，供上一条测试对照）。 */
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
