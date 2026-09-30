package dev.everyagent.plugin.sandbox.codex.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.CloseStdin;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Error;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.ErrorStage;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Exit;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Output;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Resize;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.SpawnReady;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.SpawnRequest;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Stdin;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Stream;
import dev.everyagent.plugin.sandbox.codex.runner.IpcMessage.Terminate;

/**
 * IPC 消息的 Jackson JSON 编解码（设计文档 §2.7 IpcMessages）。
 *
 * <p>手工 tree 映射（不用注解绑定）：字段名集中一处、与 codex ipc_framed.rs 的
 * serde 命名逐一对齐（snake_case + base64 字段 {@code data_b64}），wire 兼容性
 * 可直接单测断言。帧结构（version 平铺 + type/payload）由本类负责组装/解析，
 * 长度前缀分帧由 {@link FrameCodec} 负责。
 */
final class IpcJson {

    private static final ObjectMapper JSON = new ObjectMapper();

    private IpcJson() {
    }

    /** 消息 → {@code {"version":6,"type":…,"payload":…}} JSON 树。 */
    static ObjectNode toJson(IpcMessage message) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", IpcMessage.IPC_PROTOCOL_VERSION);
        root.put("type", message.tag());
        ObjectNode payload = root.putObject("payload");
        switch (message) {
            case SpawnRequest r -> {
                addStrings(payload.putArray("command"), r.command());
                putNullable(payload, "cwd", r.cwd());
                ObjectNode env = payload.putObject("env");
                r.env().forEach((k, v) -> env.put(k, v));
                addStrings(payload.putArray("cap_sids"), r.capSids());
                addStrings(payload.putArray("write_roots"), r.writeRoots());
                addStrings(payload.putArray("deny_write_paths"), r.denyWritePaths());
                putNullable(payload, "network_identity", r.networkIdentity());
                if (r.timeoutMs() == null) {
                    payload.putNull("timeout_ms");
                } else {
                    payload.put("timeout_ms", r.timeoutMs());
                }
                payload.put("tty", r.tty());
                payload.put("stdin_open", r.stdinOpen());
                putNullable(payload, "private_desktop_name", r.privateDesktopName());
            }
            case SpawnReady r -> payload.put("process_id", r.processId());
            case Output r -> {
                payload.put("data_b64", r.dataBase64());
                payload.put("stream", r.stream().wireName());
            }
            case Stdin r -> payload.put("data_b64", r.dataBase64());
            case CloseStdin r -> { /* EmptyPayload：空对象 */ }
            case Resize r -> {
                payload.put("rows", r.rows());
                payload.put("cols", r.cols());
            }
            case Exit r -> {
                payload.put("exit_code", r.exitCode());
                payload.put("timed_out", r.timedOut());
            }
            case Error r -> {
                payload.put("message", r.message());
                payload.put("stage", r.stage().wireName());
                if (r.windowsErrorCode() == null) {
                    payload.putNull("windows_error_code");
                } else {
                    payload.put("windows_error_code", r.windowsErrorCode());
                }
            }
            case Terminate r -> { /* EmptyPayload：空对象 */ }
        }
        return root;
    }

    /** JSON 树 → 消息；type 缺失/未知抛 {@link FrameCodec.FrameException}。 */
    static IpcMessage fromJson(JsonNode root) {
        String type = root.path("type").asText(null);
        JsonNode p = root.path("payload");
        if (type == null) {
            throw new FrameCodec.FrameException("IPC frame missing 'type'");
        }
        return switch (type) {
            case IpcMessage.TAG_SPAWN_REQUEST -> new SpawnRequest(
                    readStrings(p.path("command")),
                    textOrNull(p.path("cwd")),
                    readEnv(p.path("env")),
                    readStrings(p.path("cap_sids")),
                    readStrings(p.path("write_roots")),
                    readStrings(p.path("deny_write_paths")),
                    textOrNull(p.path("network_identity")),
                    p.path("timeout_ms").isNumber() ? p.path("timeout_ms").asLong() : null,
                    p.path("tty").asBoolean(false),
                    p.path("stdin_open").asBoolean(false),
                    textOrNull(p.path("private_desktop_name")));
            case IpcMessage.TAG_SPAWN_READY -> new SpawnReady(p.path("process_id").asInt());
            case IpcMessage.TAG_OUTPUT -> new Output(
                    p.path("data_b64").asText(""),
                    Stream.fromWire(p.path("stream").asText(null)));
            case IpcMessage.TAG_STDIN -> new Stdin(p.path("data_b64").asText(""));
            case IpcMessage.TAG_CLOSE_STDIN -> new CloseStdin();
            case IpcMessage.TAG_RESIZE -> new Resize(
                    p.path("rows").asInt(0), p.path("cols").asInt(0));
            case IpcMessage.TAG_EXIT -> new Exit(
                    p.path("exit_code").asInt(0), p.path("timed_out").asBoolean(false));
            case IpcMessage.TAG_ERROR -> new Error(
                    p.path("message").asText(""),
                    ErrorStage.fromWire(p.path("stage").asText(null)),
                    p.path("windows_error_code").isNumber()
                            ? p.path("windows_error_code").asInt() : null);
            case IpcMessage.TAG_TERMINATE -> new Terminate();
            default -> throw new FrameCodec.FrameException("unknown IPC message type: " + type);
        };
    }

    static ObjectMapper mapper() {
        return JSON;
    }

    // ---- 私有工具 ----

    private static void addStrings(ArrayNode arr, java.util.List<String> values) {
        if (values != null) {
            values.forEach(v -> {
                if (v != null) {
                    arr.add(v);
                }
            });
        }
    }

    private static java.util.List<String> readStrings(JsonNode arr) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        if (arr.isArray()) {
            arr.forEach(n -> {
                if (n.isTextual()) {
                    out.add(n.asText());
                }
            });
        }
        return java.util.Collections.unmodifiableList(out);
    }

    private static java.util.Map<String, String> readEnv(JsonNode obj) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (obj.isObject()) {
            obj.fields().forEachRemaining(e -> {
                if (e.getValue() != null && e.getValue().isTextual()) {
                    out.put(e.getKey(), e.getValue().asText());
                }
            });
        }
        return java.util.Collections.unmodifiableMap(out);
    }

    private static void putNullable(ObjectNode node, String field, String value) {
        if (value == null) {
            node.putNull(field);
        } else {
            node.put(field, value);
        }
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }
}
