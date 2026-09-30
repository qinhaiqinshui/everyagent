package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DenyReadGlobs 单测（跨平台；对齐 deny_read_resolver.rs 测试面：精确路径直通、
 * glob 快照展开、空目录保留、深度封顶、根起无界拒绝）。
 * 与 codex 的差异：匹配恒为大小写不敏感（本插件仅面向 Windows，见类注释）。
 */
class DenyReadGlobsTest {

    @TempDir
    Path tmp;

    @Test
    void exactPathsPassThroughIncludingMissing() throws IOException {
        Path missing = tmp.resolve("missing.env");
        List<Path> resolved = DenyReadGlobs.resolve(List.of(missing), List.of(), tmp, null);
        assertEquals(List.of(missing.normalize()), resolved);
    }

    @Test
    void recursiveGlobExpandsExistingMatches() throws IOException {
        Path rootEnv = Files.writeString(tmp.resolve(".env"), "s");
        Path nested = Files.createDirectories(tmp.resolve("app"));
        Path nestedEnv = Files.writeString(nested.resolve(".env"), "s");
        Path upper = Files.writeString(nested.resolve("SECRET.ENV"), "s");
        Files.writeString(nested.resolve("notes.txt"), "n");

        Set<Path> resolved = new HashSet<>(DenyReadGlobs.resolve(List.of(),
                List.of(tmp + "/**/*.env"), tmp, null));

        assertEquals(Set.of(rootEnv, nestedEnv, upper), resolved);
    }

    @Test
    void relativeRecursiveGlobAnchorsAtCwd() throws IOException {
        Path nested = Files.createDirectories(tmp.resolve("nested"));
        Path secret = Files.writeString(nested.resolve("secret.env"), "s");

        List<Path> resolved = DenyReadGlobs.resolve(List.of(), List.of("**/*.env"), tmp, null);

        assertEquals(List.of(secret), resolved);
    }

    @Test
    void nonRecursiveGlobStaysAtTopLevel() throws IOException {
        Path rootEnv = Files.writeString(tmp.resolve(".env"), "s");
        Files.createDirectories(tmp.resolve("app"));
        Files.writeString(tmp.resolve("app").resolve(".env"), "s");

        List<Path> resolved = DenyReadGlobs.resolve(List.of(), List.of(tmp + "/*.env"), tmp, null);

        assertEquals(List.of(rootEnv), resolved);
    }

    @Test
    void matchingEmptyDirectoriesPreserved() throws IOException {
        Path rootSecret = Files.createDirectories(tmp.resolve("root.env"));
        Path nestedSecret = Files.createDirectories(tmp.resolve("nested").resolve("empty.env"));

        Set<Path> resolved = new HashSet<>(DenyReadGlobs.resolve(List.of(),
                List.of("**/*.env"), tmp, null));

        assertEquals(Set.of(rootSecret, nestedSecret), resolved);
    }

    @Test
    void configuredDepthExcludesDeeperMatches() throws IOException {
        Files.writeString(tmp.resolve("shallow.env"), "s");
        Files.createDirectories(tmp.resolve("nested"));
        Path deep = tmp.resolve("nested").resolve("deep.env");
        Files.writeString(deep, "s");

        List<Path> resolved = DenyReadGlobs.resolve(List.of(), List.of("**/*.env"), tmp, 1);

        assertEquals(List.of(tmp.resolve("shallow.env")), resolved);
    }

    @Test
    void sharedRootsScanAtEachRequiredDepth() throws IOException {
        Path env = Files.writeString(tmp.resolve(".env"), "s");
        Files.createDirectories(tmp.resolve("nested"));
        Path pem = tmp.resolve("nested").resolve("credentials.pem");
        Files.writeString(pem, "s");

        Set<Path> resolved = new HashSet<>(DenyReadGlobs.resolve(List.of(),
                List.of(tmp + "/*.env", tmp + "/*/*.pem"), tmp, null));

        assertEquals(Set.of(env, pem), resolved);
    }

    @Test
    void rootRecursiveGlobWithoutDepthFailsBeforeExpansion() {
        StringBuilder rootPath = new StringBuilder();
        Path root = tmp;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        rootPath.append(root);
        if (rootPath.charAt(rootPath.length() - 1) != '/') {
            rootPath.append('/');
        }
        String pattern = rootPath + "**/*.env";

        IOException error = assertThrows(IOException.class,
                () -> DenyReadGlobs.resolve(List.of(), List.of(pattern), tmp, null));
        assertTrue(error.getMessage().contains("cannot be safely expanded from a filesystem root"),
                error.getMessage());
    }

    @Test
    void configuredDepthBoundsRootRecursiveGlob() throws IOException {
        Path root = tmp;
        while (root.getParent() != null) {
            root = root.getParent();
        }
        String separator = root.toString().endsWith("/") ? "" : "/";
        // 根下扫描一层；不在根建文件，仅验证不再抛「根起无界」错误
        DenyReadGlobs.resolve(List.of(), List.of(root + separator + "**/*.env"), tmp, 1);
    }

    @Test
    void invalidGlobFailsBeforeExpansion() {
        IOException error = assertThrows(IOException.class,
                () -> DenyReadGlobs.resolve(List.of(), List.of(tmp + "/**/[z-a]"), tmp, null));
        assertTrue(error.getMessage().contains("invalid deny-read glob pattern"),
                error.getMessage());
    }
}
