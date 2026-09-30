package dev.everyagent.plugin.sandbox.codex.runner;

import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * runner IPC 消息模型（设计文档 §4.2，对齐 codex elevated/ipc_framed.rs，协议版本 6）。
 *
 * <p>wire 形态：{@code {"version":6,"type":"&lt;snake_case tag&gt;","payload":{…}}}——
 * version 与消息体平铺在同一 JSON 对象（codex {@code #[serde(flatten)]} 语义）；二进制
 * （stdout/stderr/stdin 字节）一律 STANDARD base64。字段名与 codex 完全一致
 * （snake_case），唯 SpawnRequest 不搬 codex 的 {@code permission_profile} 结构，改为自有
 * {@code write_roots/deny_write_paths/network_identity}（两端都是本插件，见设计 §4.2）。
 *
 * <p>方向：父→runner = {@link SpawnRequest}/{@link Stdin}/{@link CloseStdin}/
 * {@link Resize}/{@link Terminate}；runner→父 = {@link SpawnReady}/{@link Output}/
 * {@link Exit}/{@link Error}。JSON 编解码见 {@link IpcJson}，分帧见 {@link FrameCodec}。
 */
public sealed interface IpcMessage {

    /** 协议版本常量（codex ipc_framed.rs::IPC_PROTOCOL_VERSION = 6）。 */
    int IPC_PROTOCOL_VERSION = 6;

    /** wire tag 常量（serde rename_all = "snake_case"）。 */
    String TAG_SPAWN_REQUEST = "spawn_request";
    String TAG_SPAWN_READY = "spawn_ready";
    String TAG_OUTPUT = "output";
    String TAG_STDIN = "stdin";
    String TAG_CLOSE_STDIN = "close_stdin";
    String TAG_RESIZE = "resize";
    String TAG_EXIT = "exit";
    String TAG_ERROR = "error";
    String TAG_TERMINATE = "terminate";

    /** 本消息的 wire tag。 */
    String tag();

    /** spawn 参数（父→runner）。cwd/env/command 语义与 codex SpawnRequest 一致。 */
    record SpawnRequest(
            List<String> command,
            String cwd,
            Map<String, String> env,
            /** capability SID 列表（codex 字段 {@code cap_sids}），至少 1 个。 */
            List<String> capSids,
            /** 写根（本插件替代 permission_profile 的自有字段）。 */
            List<String> writeRoots,
            /** deny-write 路径（自有字段）。 */
            List<String> denyWritePaths,
            /** 网络身份："offline"/"online"（自有字段，缺省 offline 语义由 broker 决定）。 */
            String networkIdentity,
            /** 超时毫秒；null = 无限等待（codex {@code timeout_ms: Option&lt;u64&gt;}）。 */
            Long timeoutMs,
            /** 是否 TTY（本插件固定 false，ConPTY 暂缓，设计 §1.2）。 */
            boolean tty,
            /** stdin 是否保持打开（codex {@code stdin_open}，默认 false）。 */
            boolean stdinOpen,
            /** 父进程存活的私有桌面名（codex {@code private_desktop_name}），可空。 */
            String privateDesktopName) implements IpcMessage {

        public SpawnRequest {
            command = command == null ? List.of() : List.copyOf(command);
            env = env == null ? Map.of() : Map.copyOf(env);
            capSids = capSids == null ? List.of() : List.copyOf(capSids);
            writeRoots = writeRoots == null ? List.of() : List.copyOf(writeRoots);
            denyWritePaths = denyWritePaths == null ? List.of() : List.copyOf(denyWritePaths);
        }

        @Override
        public String tag() {
            return TAG_SPAWN_REQUEST;
        }
    }

    /** runner 成功派生子进程后的 ack（runner→父）。 */
    record SpawnReady(int processId) implements IpcMessage {
        @Override
        public String tag() {
            return TAG_SPAWN_READY;
        }
    }

    /** 子进程输出块（runner→父），字节以 base64 携带。 */
    record Output(String dataBase64, Stream stream) implements IpcMessage {
        @Override
        public String tag() {
            return TAG_OUTPUT;
        }
    }

    /** 父进程写入子进程 stdin 的字节（父→runner）。 */
    record Stdin(String dataBase64) implements IpcMessage {
        @Override
        public String tag() {
            return TAG_STDIN;
        }
    }

    /** 关闭子进程 stdin（父→runner，空载荷）。 */
    record CloseStdin() implements IpcMessage {
        @Override
        public String tag() {
            return TAG_CLOSE_STDIN;
        }
    }

    /** PTY resize（父→runner；ConPTY 暂缓，runner 侧 no-op，设计 §1.2）。 */
    record Resize(int rows, int cols) implements IpcMessage {
        @Override
        public String tag() {
            return TAG_RESIZE;
        }
    }

    /** 子进程退出结果（runner→父）；超时 exit_code=192（128+64）、timed_out=true。 */
    record Exit(int exitCode, boolean timedOut) implements IpcMessage {
        @Override
        public String tag() {
            return TAG_EXIT;
        }
    }

    /** runner 启动失败报告（runner→父）。 */
    record Error(
            String message,
            ErrorStage stage,
            /** Win32 错误码；null 表示无（非 Win32 失败）。 */
            Integer windowsErrorCode) implements IpcMessage {
        @Override
        public String tag() {
            return TAG_ERROR;
        }
    }

    /** 请求终止子进程（父→runner，空载荷）。 */
    record Terminate() implements IpcMessage {
        @Override
        public String tag() {
            return TAG_TERMINATE;
        }
    }

    /** 输出流标识（codex OutputStream，snake_case wire 值）。 */
    enum Stream {
        STDOUT("stdout"),
        STDERR("stderr");

        private final String wireName;

        Stream(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static Stream fromWire(String name) {
            for (Stream s : values()) {
                if (s.wireName.equals(name)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("unknown output stream: " + name);
        }
    }

    /** runner 启动失败阶段（codex ErrorStage，snake_case wire 值）。 */
    enum ErrorStage {
        READ_SPAWN_REQUEST("read_spawn_request"),
        SPAWN_CHILD("spawn_child"),
        WRITE_SPAWN_READY("write_spawn_ready");

        private final String wireName;

        ErrorStage(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        static ErrorStage fromWire(String name) {
            for (ErrorStage s : values()) {
                if (s.wireName.equals(name)) {
                    return s;
                }
            }
            throw new IllegalArgumentException("unknown error stage: " + name);
        }
    }

    /** 二进制 → STANDARD base64（codex encode_bytes）。 */
    static String encodeBytes(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    /** STANDARD base64 → 二进制（codex decode_bytes）。 */
    static byte[] decodeBytes(String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
