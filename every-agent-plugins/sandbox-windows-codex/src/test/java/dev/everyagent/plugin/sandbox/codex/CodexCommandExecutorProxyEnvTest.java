package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.sandbox.codex.proxy.ProxyEnv;
import dev.everyagent.plugin.sandbox.codex.proxy.ProxyRegistry;
import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Exit;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Stream;
import dev.everyagent.plugin.sandbox.codex.session.CodexSandboxSession;
import dev.everyagent.plugin.sandbox.codex.session.RunnerClient;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link CodexCommandExecutor} 的代理 env 接线（阶段二 §6）：online 身份且代理在跑 →
 * spec.env 注入代理变量;offline 身份/代理未跑 → 不注入;用户显式值不被覆盖
 * （不覆盖语义的纯函数面在 ProxyEnvTest,这里验证执行层接线与身份门控）。
 */
class CodexCommandExecutorProxyEnvTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void tearDown() {
        ProxyRegistry.removeForTesting(tempDir);
    }

    /** fake 会话:开帧即 Exit(0)——executor 聚合路径的最小驱动。 */
    static final class FakeSession implements CodexCommandExecutor.ExecSession {
        final LinkedBlockingQueue<FrameCodec.FramedMessage> queue =
                new LinkedBlockingQueue<>();

        FakeSession() {
            queue.add(new FrameCodec.FramedMessage(6, new Exit(0, false)));
        }

        @Override
        public FrameCodec.FramedMessage receive() {
            return queue.poll();
        }

        @Override
        public void terminate() {
            // 无需
        }

        @Override
        public void close() {
            // 无需
        }
    }

    private record Exec(CodexCommandExecutor executor, Capture capture) {
    }

    static final class Capture {
        CodexSandboxSession.SessionSpec spec;
    }

    private Exec executorWith(String policy) {
        Capture capture = new Capture();
        CodexSandboxManager manager = new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, policy, null, false, null), 30_000);
        FakeSession session = new FakeSession();
        CodexCommandExecutor executor = new CodexCommandExecutor(manager,
                tempDir.resolve("ws"), false,
                (cfg, spec) -> {
                    capture.spec = spec;
                    return session;
                },
                (options, capSids, writeRoots, readRoots) -> new ArrayList<>(writeRoots),
                (options, username) -> new RunnerClient.RunnerConfig(
                        options.codexHome(), username, "pw", "cp", "java.home", "cwd"));
        return new Exec(executor, capture);
    }

    @Test
    void onlineSessionInjectsProxyEnvWhenProxyRunning() {
        ProxyRegistry.putForTesting(tempDir, 45678);
        Exec exec = executorWith(null); // auto + 未断网 → online
        exec.executor.execute("echo hi", "powershell");
        Map<String, String> env = exec.capture.spec.env();
        String url = "http://127.0.0.1:45678";
        assertEquals(url, env.get("HTTP_PROXY"), "online 身份 + 代理在跑 → 注入代理 env");
        assertEquals(url, env.get("https_proxy"), "小写变体成对");
        assertEquals(url, env.get("ALL_PROXY"));
        assertEquals(ProxyEnv.NO_PROXY_VALUE, env.get("NO_PROXY"));
        assertEquals("1", env.get(ProxyEnv.ACTIVE_MARKER), "活性标记进 env");
        assertEquals("online", exec.capture.spec.networkIdentity());
    }

    @Test
    void offlineSessionNeverInjectsProxyEnv() {
        ProxyRegistry.putForTesting(tempDir, 45678); // 代理在跑也不给 offline
        Exec exec = executorWith(CodexSandboxOptions.POLICY_OFFLINE);
        exec.executor.execute("echo hi", "powershell");
        Map<String, String> env = exec.capture.spec.env();
        assertNull(env.get("HTTP_PROXY"), "offline 身份拿不到代理地址(断网语义不渗透)");
        assertNull(env.get("http_proxy"));
        assertNull(env.get(ProxyEnv.ACTIVE_MARKER));
        assertEquals("offline", exec.capture.spec.networkIdentity());
    }

    @Test
    void onlineWithoutRunningProxyStaysDirect() {
        // 未注册任何代理(纯查询为空,零副作用)
        Exec exec = executorWith(null);
        exec.executor.execute("echo hi", "powershell");
        Map<String, String> env = exec.capture.spec.env();
        assertNull(env.get("HTTP_PROXY"), "代理不在跑 → 不注入,命令直连(降级不打断执行)");
        assertNull(env.get(ProxyEnv.ACTIVE_MARKER));
    }

    @Test
    void onlineWithProxyDisabledSkipsInjection() {
        ProxyRegistry.putForTesting(tempDir, 45678);
        Capture capture = new Capture();
        CodexSandboxManager manager = new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, null, null, false, null,
                        true, 2, 600_000, 1_800_000, 64,
                        false, 3128, false, false, java.util.List.of()), 30_000);
        FakeSession session = new FakeSession();
        new CodexCommandExecutor(manager, tempDir.resolve("ws"), false,
                (cfg, spec) -> {
                    capture.spec = spec;
                    return session;
                },
                (options, capSids, writeRoots, readRoots) -> new ArrayList<>(writeRoots),
                (options, username) -> new RunnerClient.RunnerConfig(
                        options.codexHome(), username, "pw", "cp", "java.home", "cwd"))
                        .execute("echo hi", "powershell");
        assertFalse(capture.spec.env().containsKey("HTTP_PROXY"),
                "codex.proxy-enabled=false → 回退直连行为(不注入)");
        assertFalse(capture.spec.env().containsKey(ProxyEnv.ACTIVE_MARKER));
    }

    @Test
    void staleManagedEnvFromOuterLayerIsReinjectedFresh() throws Exception {
        // 直接验证 childEnv 产物 + 注入的组合语义在「外层带旧托管 env」时不重放旧端口:
        // 这里以 spec.env 断言为主——外层 HTTP_PROXY 属用户态语义,由 ProxyEnvTest 覆盖;
        // 本用例验证执行层注入走的是注册表里的「当前」端点。
        ProxyRegistry.putForTesting(tempDir, 45679);
        Exec exec = executorWith(null);
        exec.executor.execute("echo hi", "powershell");
        assertEquals("http://127.0.0.1:45679", exec.capture.spec.env().get("HTTP_PROXY"),
                "注入地址取自注册表当前端点");
        assertTrue(ProxyEnv.isActive(exec.capture.spec.env()));
    }
}
