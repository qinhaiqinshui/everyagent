package dev.everyagent.plugin.sandbox.codex.runner;

import java.security.SecureRandom;

/**
 * runner 命名管道名约定（设计文档 §2.7/§4，对齐 codex runner_pipe.rs::pipe_pair）。
 *
 * <p>broker（worker 侧）以 {@code \\.\pipe\every-agent-codex-runner-<128bit nonce>-in/-out}
 * 创建两条管道实例：{@code -in} 为父写 runner 读（PIPE_ACCESS_OUTBOUND），{@code -out} 为
 * runner 写父读（PIPE_ACCESS_INBOUND）；128 位随机 nonce 使名字不可猜测 ⇒ 不可预占、不可枚举。
 * runner 进程（本包 {@link CodexRunnerMain}）只是普通客户端，用 CreateFileW 连接。
 *
 * <p>方向常量与缓冲参数供 broker 侧 CreateNamedPipeW 使用（windows-sys 0.52 未导出，
 * codex 在 runner_pipe.rs 文件头手写，此处同款手写）。
 */
public final class RunnerPaths {

    /** 管道名前缀（本插件自有命名，对应 codex 的 {@code \\.\pipe\codex-runner-}）。 */
    public static final String PIPE_PREFIX = "\\\\.\\pipe\\every-agent-codex-runner-";
    /** 父→runner 管道后缀（runner 读）。 */
    public static final String IN_SUFFIX = "-in";
    /** runner→父管道后缀（runner 写）。 */
    public static final String OUT_SUFFIX = "-out";
    /** nonce 位数（128 bit = 32 个小写 hex 字符）。 */
    public static final int NONCE_HEX_LEN = 32;

    /** CreateNamedPipeW：管道缓冲（codex 出/入各 65536）。 */
    public static final int PIPE_BUFFER_BYTES = 65536;
    /** CreateNamedPipeW：实例数 1（一次性会话）。 */
    public static final int PIPE_INSTANCES = 1;
    /** PIPE_ACCESS_OUTBOUND=0x2：服务端只写（-in 管，父写 runner 读）。 */
    public static final int PIPE_ACCESS_OUTBOUND = 0x00000002;
    /** PIPE_ACCESS_INBOUND=0x1：服务端只读（-out 管，runner 写父读）。 */
    public static final int PIPE_ACCESS_INBOUND = 0x00000001;

    /**
     * 管道 DACL SDDL 模板：只授沙箱账户 GENERIC_ALL（GA）。
     * {@code String.format(PIPE_SDDL_FORMAT, sandboxSid)}——其余账户默认拒绝连接。
     */
    public static final String PIPE_SDDL_FORMAT = "D:(A;;GA;;;%s)";

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private RunnerPaths() {
    }

    /** 生成 128 位随机 nonce（32 个小写 hex 字符）。 */
    public static String newNonce() {
        StringBuilder sb = new StringBuilder(NONCE_HEX_LEN);
        for (int i = 0; i < NONCE_HEX_LEN; i++) {
            sb.append(HEX[RNG.nextInt(16)]);
        }
        return sb.toString();
    }

    /** {@code -in} 管道全名（父写 runner 读）。 */
    public static String inPipeName(String nonce) {
        return PIPE_PREFIX + requireNonce(nonce) + IN_SUFFIX;
    }

    /** {@code -out} 管道全名（runner 写父读）。 */
    public static String outPipeName(String nonce) {
        return PIPE_PREFIX + requireNonce(nonce) + OUT_SUFFIX;
    }

    /** nonce 合法性：恰 {@value #NONCE_HEX_LEN} 个小写 hex 字符（防注入畸形管道名）。 */
    public static boolean isValidNonce(String nonce) {
        if (nonce == null || nonce.length() != NONCE_HEX_LEN) {
            return false;
        }
        for (int i = 0; i < nonce.length(); i++) {
            char c = nonce.charAt(i);
            if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    private static String requireNonce(String nonce) {
        if (!isValidNonce(nonce)) {
            throw new IllegalArgumentException("invalid runner pipe nonce: " + nonce);
        }
        return nonce;
    }
}
