package dev.everyagent.plugin.sandbox.codex.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RunnerMaterializer 纯逻辑单测（跨平台：哈希指纹/去重/路径选择/-cp 组装；
 * ACL 授予为 Windows 原生调用，非 Windows 下 no-op）。
 */
class RunnerMaterializerTest {

    @TempDir
    Path tmp;

    private Path jar(String name) throws IOException {
        Path file = tmp.resolve(name);
        Files.writeString(file, "content-of-" + name);
        return file;
    }

    // ---- classpath 解析与依赖选择（纯函数） ----

    @Test
    void parsesClasspathDroppingMissingAndBlankEntries() throws IOException {
        Path jna = jar("jna-5.16.0.jar");
        Path platform = jar("jna-platform-5.16.0.jar");
        Path databind = jar("jackson-databind-2.19.0.jar");
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        String cp = String.join(";", jna.toString(), platform.toString(),
                databind.toString(), classes.toString(), "",
                tmp.resolve("missing.jar").toString(), jna.toString());
        assertEquals(List.of(jna, platform, databind, classes),
                RunnerMaterializer.parseClasspath(cp, ";"), "空段/缺失/重复剔除");
        assertTrue(RunnerMaterializer.parseClasspath(null, ";").isEmpty());
    }

    @Test
    void selectsOnlyJarFilesMatchingDependencyMatchers() throws IOException {
        Path jna = jar("jna-5.16.0.jar");
        Path platform = jar("jna-platform-5.16.0.jar");
        Path core = jar("jackson-core-2.19.0.jar");
        Path annotations = jar("jackson-annotations-2.19.0.jar");
        Path unrelated = jar("foo.jar");
        Path dir = Files.createDirectories(tmp.resolve("jna-fake"));
        List<Path> selected = RunnerMaterializer.selectDependencies(
                List.of(unrelated, jna, platform, core, annotations, dir),
                RunnerMaterializer.DEPENDENCY_MATCHERS);
        assertEquals(List.of(jna, platform, core, annotations), selected,
                "目录条目与非依赖 jar 不入选");
    }

    @Test
    void materializationSourcesLeadsWithPluginCodeSource() throws IOException {
        Path jna = jar("jna-5.16.0.jar");
        Path databind = jar("jackson-databind-2.19.0.jar");
        List<Path> sources = RunnerMaterializer.materializationSources(
                jna + ";" + databind + ";" + tmp.resolve("missing.jar"), ";");
        assertEquals(RunnerMaterializer.pluginCodeSource(), sources.get(0),
                "插件 codeSource（jar 或 classes 目录）永远排首位");
        assertTrue(sources.contains(jna) && sources.contains(databind));
    }

    // ---- 指纹与去重 ----

    @Test
    void fingerprintIsSizePlusSha256Prefix() throws Exception {
        Path file = tmp.resolve("a.jar");
        Files.writeString(file, "abc");
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(RunnerMaterializer.fingerprint(3, digest),
                RunnerMaterializer.fingerprint(file));
        // 前 8 字节 = 16 hex 字符，size 前缀参与
        assertTrue(RunnerMaterializer.fingerprint(file).matches("\\d+:[0-9a-f]{16}"));
    }

    @Test
    void needsCopyDetectsHashDriftBeyondFileName() throws IOException {
        Path source = tmp.resolve("runner.jar");
        Files.writeString(source, "aaa");
        Path target = tmp.resolve("bin").resolve("runner.jar");
        Files.createDirectories(target.getParent());
        assertTrue(RunnerMaterializer.needsCopy(source, target), "目标缺失 → 拷贝");
        Files.copy(source, target);
        assertFalse(RunnerMaterializer.needsCopy(source, target), "同名同大小同哈希 → 跳过");
        Files.writeString(target, "bbb"); // 同长度、内容漂移（哈希不同）
        assertTrue(RunnerMaterializer.needsCopy(source, target), "哈希漂移 → 重拷");
    }

    @Test
    void materializeCopiesOnceAndHealsDriftedTarget() throws IOException {
        Path source = jar("plugin.jar");
        Path bin = tmp.resolve(".sandbox-bin");
        List<Path> first = RunnerMaterializer.materialize(List.of(source), bin, null);
        Path target = bin.resolve("plugin.jar");
        assertEquals(List.of(target), first);
        assertEquals("content-of-plugin.jar", Files.readString(target));

        // 目标被篡改（同大小不同内容）→ 指纹不一致 → 物化自愈回源内容
        String original = Files.readString(target);
        Files.writeString(target, "X".repeat(original.length()));
        RunnerMaterializer.materialize(List.of(source), bin, null);
        assertEquals(original, Files.readString(target));
    }

    @Test
    void classpathStringJoinsWithSemicolon() throws IOException {
        Path a = jar("a.jar");
        Path b = Files.createDirectories(tmp.resolve("classes"));
        assertEquals(a + ";" + b, RunnerMaterializer.classpathString(List.of(a, b)),
                "Windows -cp 分号语义");
    }

    /**
     * 进程内缓存：第二次调用不再逐 jar 全量 SHA-256（实测该开销 146-266ms/命令）。
     * 证据 = 产物被外部删除后第二次调用不重拷；invalidate 后自愈。
     */
    @Test
    void ensureRunnerClasspathCachesPerProcessAndHealsOnInvalidate() throws IOException {
        RunnerMaterializer.invalidateAllRunnerClasspaths();
        String cp = RunnerMaterializer.ensureRunnerClasspath(tmp, null);
        Path bin = tmp.resolve(".sandbox-bin");
        List<Path> copied;
        try (var s = Files.list(bin)) {
            copied = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(!copied.isEmpty(),
                "本环境 classpath 无 jar 来源（纯 classes 目录）→ 无删除证据可用");
        Path deleted = copied.get(0);
        Files.delete(deleted);

        assertEquals(cp, RunnerMaterializer.ensureRunnerClasspath(tmp, null), "返回同一 -cp");
        assertFalse(Files.exists(deleted), "第二次调用应短路（不重拷 → 证明未重复校验）");

        RunnerMaterializer.invalidateRunnerClasspath(tmp);
        RunnerMaterializer.ensureRunnerClasspath(tmp, null);
        assertTrue(Files.exists(deleted), "失效后重做物化，产物自愈");
    }

    /** 缓存按 codexHome 隔离：A 的缓存不得让 B 短路。 */
    @Test
    void cacheIsKeyedByCodexHome() throws IOException {
        RunnerMaterializer.invalidateAllRunnerClasspaths();
        Path homeA = Files.createDirectories(tmp.resolve("homeA"));
        Path homeB = Files.createDirectories(tmp.resolve("homeB"));
        RunnerMaterializer.ensureRunnerClasspath(homeA, null);
        Path binA = homeA.resolve(".sandbox-bin");
        List<Path> copiedA;
        try (var s = Files.list(binA)) {
            copiedA = s.filter(p -> p.toString().endsWith(".jar")).sorted().toList();
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(!copiedA.isEmpty(),
                "本环境 classpath 无 jar 来源 → 无隔离证据可用");
        Files.delete(copiedA.get(0));

        RunnerMaterializer.ensureRunnerClasspath(homeB, null); // 不得被 A 的缓存短路
        try (var s = Files.list(homeB.resolve(".sandbox-bin"))) {
            assertTrue(s.findAny().isPresent(), "B 独立物化");
        }
        assertFalse(Files.exists(copiedA.get(0)), "A 仍走自己的缓存（未因 B 的调用而重拷）");
    }
}
