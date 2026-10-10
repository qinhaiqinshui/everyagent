package dev.everyagent.worker.modules;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.File;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkerRestartRpcHandler#buildRelaunchCommand} 的命令重建形态测试(纯函数,不起 Spring)。
 */
class WorkerRestartRpcHandlerTest {

    private static final String EXE = "/opt/jdk/bin/java";
    private static final String JAR = "/opt/everyagent/worker.jar";

    @Test
    void fullArgvPreferredWhenAvailable() {
        // Linux 形态:ProcessHandle 提供完整 argv(含 JVM 选项),原样保真。
        List<String> cmd = WorkerRestartRpcHandler.buildRelaunchCommand(
                EXE,
                new String[]{"-Dreactor.schedulers.default.poolSize=8", "-jar", JAR, "--opt"},
                "-jar " + JAR + " --opt",
                JAR,
                "/opt/jdk");
        assertEquals(Arrays.asList(EXE, "-Dreactor.schedulers.default.poolSize=8", "-jar", JAR, "--opt"), cmd);
    }

    @Test
    void jarFormRebuiltFromClasspathSingleEntry() {
        // Windows fat jar 形态:arguments() 不可用 → sun.java.command + java.class.path(单条目即 jar 路径)。
        // JDK 25 实测 -jar 启动的 sun.java.command 只含 jar 路径(无 "-jar" 前缀)。
        List<String> cmd = WorkerRestartRpcHandler.buildRelaunchCommand(
                "C:\\jdk\\bin\\javaw.exe", null,
                JAR,
                JAR,
                "C:\\jdk");
        assertEquals(Arrays.asList("C:\\jdk\\bin\\javaw.exe", "-jar", JAR), cmd);
    }

    @Test
    void jarFormWithDashJarPrefixAlsoNormalized() {
        // 部分 JDK 的 sun.java.command 带 "-jar" 前缀:同样归一到 classpath 单条目精确边界。
        List<String> cmd = WorkerRestartRpcHandler.buildRelaunchCommand(
                EXE, null,
                "-jar " + JAR,
                JAR,
                "/opt/jdk");
        assertEquals(Arrays.asList(EXE, "-jar", JAR), cmd);
    }

    @Test
    void jarFormKeepsProgramArgsAfterJarPath() {
        // 无前缀变体 + 程序参数(引号包裹的含空格参数在 sun.java.command 中已无引号,按空白切为已知取舍)。
        List<String> cmd = WorkerRestartRpcHandler.buildRelaunchCommand(
                EXE, null,
                JAR + " --foo bar",
                JAR,
                "/opt/jdk");
        assertEquals(Arrays.asList(EXE, "-jar", JAR, "--foo", "bar"), cmd);
    }

    @Test
    void jarFormWithJarPathContainingSpaces() {
        // jar 路径含空格:以 classpath 单条目为精确边界,不按空白拆坏路径。
        String jar = "/opt/Every Agent/worker.jar";
        List<String> cmd = WorkerRestartRpcHandler.buildRelaunchCommand(
                EXE, null,
                "-jar " + jar + " --foo",
                jar,
                "/opt/jdk");
        assertEquals(Arrays.asList(EXE, "-jar", jar, "--foo"), cmd);
    }

    @Test
    void jarFormWithMultiEntryClasspathFallsBackToSplit() {
        // 异常形态(-jar 但 classpath 多条目):整串按空白切(jar 路径含空格时可能失败,已知取舍)。
        List<String> cmd = WorkerRestartRpcHandler.buildRelaunchCommand(
                EXE, null,
                "-jar " + JAR,
                JAR + File.pathSeparator + "/other/lib.jar",
                "/opt/jdk");
        assertEquals(Arrays.asList(EXE, "-jar", JAR), cmd);
    }

    @Test
    void classpathFormForDevLaunch() {
        // dev 形态(spring-boot:run / IDE):主类 + 显式 -cp 完整类路径。
        List<String> cmd = WorkerRestartRpcHandler.buildRelaunchCommand(
                EXE, null,
                "dev.everyagent.worker.WorkerApplication --spring.profiles.active=dev",
                "/repo/worker/target/classes" + File.pathSeparator + "/m2/spring.jar",
                "/opt/jdk");
        assertEquals(Arrays.asList(
                EXE,
                "-cp",
                "/repo/worker/target/classes" + File.pathSeparator + "/m2/spring.jar",
                "dev.everyagent.worker.WorkerApplication",
                "--spring.profiles.active=dev"), cmd);
    }

    @Test
    void missingEverythingRejected() {
        // exe 与 java.home 均不可得:直接拒绝(两个失败点共用「无法重建启动命令」前缀)。
        IllegalStateException e = assertThrows(IllegalStateException.class, () ->
                WorkerRestartRpcHandler.buildRelaunchCommand(null, null, "", "", ""));
        assertTrue(e.getMessage().contains("无法重建启动命令"));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void exeFallbackToJavaHomeOnWindows() {
        // exe 缺失:回退 java.home 下的 javaw.exe(桌面/bat 均以 javaw 启动,保住无控制台窗口形态)。
        String javaHome = System.getProperty("java.home");
        List<String> cmd = WorkerRestartRpcHandler.buildRelaunchCommand(
                null, null, "-jar " + JAR, JAR, javaHome);
        assertTrue(cmd.get(0).startsWith(javaHome), "应回退到 java.home 下的可执行文件: " + cmd.get(0));
        assertTrue(cmd.get(0).endsWith("javaw.exe") || cmd.get(0).endsWith("java.exe"));
        assertEquals("-jar", cmd.get(1));
    }
}
