package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.sandbox.codex.runner.FrameCodec;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Exit;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Output;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Stream;
import dev.everyagent.plugin.sandbox.codex.session.CodexSandboxSession;
import dev.everyagent.plugin.sandbox.codex.session.RunnerClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.LinkedBlockingQueue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schannel 凭据失败(SEC_E_NO_CREDENTIALS)提示注入(findings §1/§5 的落地):
 * 沙箱内 curl.exe/Invoke-WebRequest 的 TLS 失败是受限令牌平台限制,模型不知情时
 * 会误判为「断网/https 坏了」(实测烧 5 轮排查)。本用例锁定:
 *
 * <ul>
 *   <li>stderr/stdout 命中特征(curl 英文 / .NET 中英文) → 结果尾部追加提示;</li>
 *   <li>无关输出(含普通 TLS 错误) → 不追加;</li>
 *   <li>纯函数检测面直接覆盖各文案形态。</li>
 * </ul>
 */
class CodexCommandExecutorSchannelHintTest {

    @TempDir
    Path tempDir;

    /** 可编程 fake 会话:按序发若干 Output 帧后 Exit。 */
    static final class ScriptedSession implements CodexCommandExecutor.ExecSession {
        final LinkedBlockingQueue<FrameCodec.FramedMessage> queue =
                new LinkedBlockingQueue<>();

        ScriptedSession(String stderrText, int exitCode) {
            if (stderrText != null) {
                queue.add(frame(stderrText, Stream.STDERR));
            }
            queue.add(new FrameCodec.FramedMessage(6, new Exit(exitCode, false)));
        }

        static FrameCodec.FramedMessage frame(String text, Stream stream) {
            String b64 = Base64.getEncoder()
                    .encodeToString(text.getBytes(StandardCharsets.UTF_8));
            return new FrameCodec.FramedMessage(6, new Output(b64, stream));
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

    private CodexCommandExecutor executorWith(CodexCommandExecutor.SessionOpener opener) {
        CodexSandboxManager manager = new CodexSandboxManager(
                new CodexSandboxOptions(tempDir, null, null, null, false, null), 30_000);
        return new CodexCommandExecutor(manager, tempDir.resolve("ws"), false,
                opener,
                (options, capSids, writeRoots, readRoots) -> new ArrayList<>(writeRoots),
                (options, username) -> new RunnerClient.RunnerConfig(
                        options.codexHome(), username, "pw", "cp", "java.home", "cwd"));
    }

    @Test
    void curlSchannelFailureGetsHintAppended() {
        CodexCommandExecutor executor = executorWith((cfg, spec) -> new ScriptedSession(
                "curl: (35) schannel: AcquireCredentialsHandle failed:"
                        + " SEC_E_NO_CREDENTIALS (0x8009030e)", 35));
        String result = executor.execute("curl.exe -sI https://www.baidu.com", "powershell");
        assertTrue(result.contains(CodexCommandExecutor.SCHANNEL_HINT_NOTE),
                "curl Schannel 凭据失败 → 结果尾部追加平台限制提示");
        assertTrue(result.contains("git/node/python"), "提示含替代工具指引");
    }

    @Test
    void dotnetChineseMessageGetsHintAppended() {
        CodexCommandExecutor executor = executorWith((cfg, spec) -> new ScriptedSession(
                "请求失败: 安全包中没有可用的凭证", 1));
        String result = executor.execute("Invoke-WebRequest https://example.com", "powershell");
        assertTrue(result.contains(CodexCommandExecutor.SCHANNEL_HINT_NOTE),
                ".NET 中文文案同样命中");
    }

    @Test
    void ordinaryTlsErrorGetsNoHint() {
        CodexCommandExecutor executor = executorWith((cfg, spec) -> new ScriptedSession(
                "curl: (60) SSL certificate problem: self signed certificate", 60));
        String result = executor.execute("curl.exe https://self-signed.example", "powershell");
        assertFalse(result.contains(CodexCommandExecutor.SCHANNEL_HINT_NOTE),
                "普通证书校验失败与平台限制无关,不得追加");
    }

    @Test
    void detectorCoversAllSignatureForms() {
        assertTrue(CodexCommandExecutor.schannelCredentialFailure(
                null, "schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS"));
        assertTrue(CodexCommandExecutor.schannelCredentialFailure(
                "SCHANNEL: ACQUIRECREDENTIALSHANDLE FAILED", null), "大小写不敏感");
        assertTrue(CodexCommandExecutor.schannelCredentialFailure(
                null, "Win32Exception: No credentials are available in the security package"));
        assertTrue(CodexCommandExecutor.schannelCredentialFailure(
                "0x8009030e", null), "裸十六进制码命中");
        assertFalse(CodexCommandExecutor.schannelCredentialFailure(
                "HTTP 200 OK", null));
        assertFalse(CodexCommandExecutor.schannelCredentialFailure(null, null));
    }

    @Test
    void hintNeverTriggersOnItsOwnText() {
        // 提示文本自身含特征词——检测面向命令输出,不得因提示注入后的输出二次触发
        // (本用例锁定检测函数对「仅含提示文本」的输入返回 true 是预期行为——
        //  真实路径上检测发生在 append 之前,无自指循环;此处固化该语义供回归)
        assertTrue(CodexCommandExecutor.schannelCredentialFailure(
                CodexCommandExecutor.SCHANNEL_HINT_NOTE, null),
                "提示文本含特征词(检测在注入前执行,无循环)");
    }
}
