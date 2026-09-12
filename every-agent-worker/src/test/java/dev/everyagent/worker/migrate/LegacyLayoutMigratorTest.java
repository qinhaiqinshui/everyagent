package dev.everyagent.worker.migrate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 旧布局 → 新布局迁移命令端到端单测(@TempDir,无 Spring;不触发真实 wsl.exe)。
 * 验证:默认工作区改名、注册表补 id、任务按工作区归类、sandbox 升顶级、
 * git 密钥复制到工作区、旧 data 清理、幂等重跑跳过。
 */
class LegacyLayoutMigratorTest {

    @TempDir
    Path home;

    private void seedOldLayout() throws Exception {
        // 默认工作区(旧名) + 另一个工作区(带密文)
        Files.createDirectories(home.resolve("workspace"));
        Path ws2 = home.resolve("ws2").resolve(".everyagent");
        Files.createDirectories(ws2);
        Files.writeString(ws2.resolve(".git-credentials.enc"), "ENC");

        // 旧注册表(无 id)
        String reg = "[" +
                "{\"root\":\"" + home.resolve("workspace").toAbsolutePath().normalize() + "\",\"addedTs\":1000}," +
                "{\"root\":\"" + home.resolve("ws2").toAbsolutePath().normalize() + "\",\"addedTs\":2000}" +
                "]";
        Files.createDirectories(home.resolve("data"));
        Files.writeString(home.resolve("data").resolve("workspaces.json"), reg);

        // 旧任务平铺(data/tasks)
        Path t1 = home.resolve("data").resolve("tasks").resolve("t1");
        Path t2 = home.resolve("data").resolve("tasks").resolve("t2");
        Files.createDirectories(t1);
        Files.createDirectories(t2);
        Files.writeString(t1.resolve("meta.json"),
                "{\"taskId\":\"t1\",\"status\":\"done\",\"workspace\":\"" +
                        home.resolve("workspace").toAbsolutePath().normalize() + "\"}");
        Files.writeString(t2.resolve("meta.json"),
                "{\"taskId\":\"t2\",\"status\":\"done\",\"workspace\":\"" +
                        home.resolve("ws2").toAbsolutePath().normalize() + "\"}");

        // 沙箱持久状态 + 全局 git 密钥
        Files.createDirectories(home.resolve("data").resolve("sandbox").resolve("foo"));
        Files.writeString(home.resolve("data").resolve("sandbox").resolve("foo").resolve("state.json"), "{}");
        Files.createDirectories(home.resolve("data").resolve("keys"));
        Files.writeString(home.resolve("data").resolve("keys").resolve("git-credential.key"), "KEY");
    }

    @Test
    void migratesOldLayoutToWorkspacesAndIsIdempotent() throws Exception {
        seedOldLayout();

        LegacyLayoutMigrator.main(new String[]{"--home", home.toString()});

        // 默认工作区改名
        assertTrue(Files.isDirectory(home.resolve("defaultworkspace")), "workspace 改名 defaultworkspace");
        assertFalse(Files.exists(home.resolve("workspace")), "旧 workspace 目录已迁移");

        // 新注册表含 id
        String registry = Files.readString(home.resolve("workspaces").resolve("workspaces.json"));
        assertTrue(registry.contains("\"id\":\"defaultworkspace\""), "默认工作区 id=defaultworkspace: " + registry);
        assertTrue(registry.contains("\"id\":\"w_"), "其它工作区分配 w_ 短 id: " + registry);

        // 任务按工作区归类
        assertTrue(Files.isRegularFile(
                home.resolve("workspaces").resolve("defaultworkspace").resolve("tasks").resolve("t1").resolve("meta.json")));
        // 找到 ws2 对应 id 目录下的 t2
        boolean t2Moved = false;
        try (var ws = Files.newDirectoryStream(home.resolve("workspaces"), p -> Files.isDirectory(p))) {
            for (Path dir : ws) {
                if (dir.getFileName().toString().startsWith("w_")
                        && Files.isRegularFile(dir.resolve("tasks").resolve("t2").resolve("meta.json"))) {
                    t2Moved = true;
                    break;
                }
            }
        }
        assertTrue(t2Moved, "非默认工作区任务归到 w_ 目录");

        // sandbox 升顶级
        assertTrue(Files.isRegularFile(home.resolve("sandbox").resolve("foo").resolve("state.json")), "sandbox 迁移到顶级");

        // git 密钥复制到已有密文的工作区
        System.err.println("[diag] home=" + home);
        System.err.println("[diag] registry=" + Files.readString(home.resolve("workspaces").resolve("workspaces.json")));
        System.err.println("[diag] ws2 .everyagent=" + (Files.isDirectory(home.resolve("ws2").resolve(".everyagent"))
                ? String.join(",", Files.list(home.resolve("ws2").resolve(".everyagent")).map(Path::toString).toList())
                : "缺失"));
        assertTrue(Files.isRegularFile(home.resolve("ws2").resolve(".everyagent").resolve(".git-credential.key")),
                "git 密钥复制到工作区 .everyagent/");

        // data 目录已清空并删除
        assertFalse(Files.exists(home.resolve("data")), "旧 data 目录删除");

        // 幂等:再跑一次应跳过
        LegacyLayoutMigrator.main(new String[]{"--home", home.toString()});
        assertTrue(Files.readString(
                        home.resolve("workspaces").resolve("defaultworkspace").resolve("tasks").resolve("t1").resolve("meta.json"))
                .contains("\"t1\""), "幂等重跑不破坏已迁移数据");
    }
}
