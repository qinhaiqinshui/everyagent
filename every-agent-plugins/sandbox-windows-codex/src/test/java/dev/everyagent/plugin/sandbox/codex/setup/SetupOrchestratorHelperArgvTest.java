package dev.everyagent.plugin.sandbox.codex.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SetupOrchestrator#buildHelperArgv} 形态单测：helper 统一以
 * RunnerMaterializer 物化的 .sandbox-bin classpath 启动（fat jar 打包态下
 * 依赖对 -cp 不可见的历史缺陷由此修复），不再拼 java.class.path 原文、
 * 也不再用 PropertiesLauncher。
 */
class SetupOrchestratorHelperArgvTest {

    @TempDir
    Path codexHome;

    @Test
    void helperArgvUsesMaterializedClasspath() throws Exception {
        Files.createDirectories(codexHome);
        List<String> argv = SetupOrchestrator.buildHelperArgv(
                "--setup-payload", "e30=", codexHome);
        assertEquals(6, argv.size(), "java -cp <cp> <main> <flag> <payload>");
        assertEquals("-cp", argv.get(1));
        assertEquals("dev.everyagent.plugin.sandbox.codex.setup.SetupHelperMain", argv.get(3));
        assertEquals("--setup-payload", argv.get(4));
        assertEquals("e30=", argv.get(5));
        assertTrue(argv.get(2).contains(".sandbox-bin"),
                "classpath 来自物化目录: " + argv.get(2));
        assertTrue(Files.isDirectory(codexHome.resolve(".sandbox-bin")),
                "物化目录已创建");
    }
}
