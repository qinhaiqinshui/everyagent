package dev.everyagent.plugin.sandbox.codex.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IpcMessage JSON wire 字段名逐一对齐测试（对齐 codex ipc_framed.rs 的 serde 命名：
 * snake_case、data_b64、stage/wire tag 值；SpawnRequest 的自有扩展字段见设计 §4.2）。
 */
class IpcMessageJsonTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void spawnRequestFieldNamesAlign() {
        IpcMessage.SpawnRequest req = new IpcMessage.SpawnRequest(
                List.of("cmd.exe", "/c", "echo hi"), "C:\\work", Map.of("PATH", "x"),
                List.of("S-1-5-21-9-9-9-9"), List.of("C:\\w"), List.of("C:\\w\\.git"),
                "online", 60_000L, false, true, "EveryAgentCodexDesktop-0011");
        JsonNode payload = payloadOf(req, "spawn_request");
        assertEquals(List.of("cmd.exe", "/c", "echo hi"), texts(payload.get("command")));
        assertEquals("C:\\work", payload.get("cwd").asText());
        assertEquals("x", payload.get("env").get("PATH").asText());
        assertEquals(1, payload.get("env").size());
        assertEquals(List.of("S-1-5-21-9-9-9-9"), texts(payload.get("cap_sids")));
        assertEquals(List.of("C:\\w"), texts(payload.get("write_roots")));
        assertEquals(List.of("C:\\w\\.git"), texts(payload.get("deny_write_paths")));
        assertEquals("online", payload.get("network_identity").asText());
        assertEquals(60_000L, payload.get("timeout_ms").asLong());
        assertFalse(payload.get("tty").asBoolean());
        assertTrue(payload.get("stdin_open").asBoolean());
        assertEquals("EveryAgentCodexDesktop-0011", payload.get("private_desktop_name").asText());
        assertEquals(Set.of("command", "cwd", "env", "cap_sids", "write_roots", "deny_write_paths",
                "network_identity", "timeout_ms", "tty", "stdin_open", "private_desktop_name"),
                fieldNames(payload));
    }

    @Test
    void spawnRequestOptionalFieldsSerializeAsNull() {
        // codex Option 无 skip_serializing_if：None 序列化为 null（serde 默认行为）
        IpcMessage.SpawnRequest req = new IpcMessage.SpawnRequest(List.of("cmd.exe"), null,
                Map.of(), List.of("S-1-5-21-9-9-9-9"), null, null, null, null, false, false, null);
        JsonNode payload = payloadOf(req, "spawn_request");
        assertTrue(payload.get("cwd").isNull());
        assertTrue(payload.get("network_identity").isNull());
        assertTrue(payload.get("timeout_ms").isNull());
        assertTrue(payload.get("private_desktop_name").isNull());
        assertTrue(payload.get("write_roots").isEmpty());
        assertTrue(payload.get("deny_write_paths").isEmpty());
    }

    @Test
    void spawnReadyHasProcessId() {
        JsonNode payload = payloadOf(new IpcMessage.SpawnReady(31337), "spawn_ready");
        assertEquals(31337, payload.get("process_id").asInt());
        assertEquals(Set.of("process_id"), fieldNames(payload));
    }

    @Test
    void outputCarriesBase64DataAndSnakeCaseStream() {
        JsonNode stdout = payloadOf(
                new IpcMessage.Output(IpcMessage.encodeBytes("hello".getBytes()), IpcMessage.Stream.STDOUT),
                "output");
        assertEquals("aGVsbG8=", stdout.get("data_b64").asText(), "STANDARD base64");
        assertEquals("stdout", stdout.get("stream").asText());
        JsonNode stderr = payloadOf(new IpcMessage.Output("AA==", IpcMessage.Stream.STDERR), "output");
        assertEquals("stderr", stderr.get("stream").asText());
        // 二进制安全往返
        byte[] binary = { 0, 1, 2, (byte) 0xFF, '\n', 0x7F };
        assertEquals(java.util.Base64.getEncoder().encodeToString(binary),
                IpcMessage.encodeBytes(binary));
        assertArrayEquals(binary, IpcMessage.decodeBytes(IpcMessage.encodeBytes(binary)));
    }

    @Test
    void stdinCarriesBase64Data() {
        JsonNode payload = payloadOf(new IpcMessage.Stdin("eHg="), "stdin");
        assertEquals("eHg=", payload.get("data_b64").asText());
        assertEquals(Set.of("data_b64"), fieldNames(payload));
    }

    @Test
    void closeStdinAndTerminateHaveEmptyPayload() {
        assertTrue(payloadOf(new IpcMessage.CloseStdin(), "close_stdin").isEmpty());
        assertTrue(payloadOf(new IpcMessage.Terminate(), "terminate").isEmpty());
    }

    @Test
    void resizeKeepsRowsCols() {
        JsonNode payload = payloadOf(new IpcMessage.Resize(30, 120), "resize");
        assertEquals(30, payload.get("rows").asInt());
        assertEquals(120, payload.get("cols").asInt());
        assertEquals(Set.of("rows", "cols"), fieldNames(payload));
    }

    @Test
    void exitHasExitCodeAndTimedOut() {
        JsonNode payload = payloadOf(new IpcMessage.Exit(192, true), "exit");
        assertEquals(192, payload.get("exit_code").asInt());
        assertTrue(payload.get("timed_out").asBoolean());
        assertEquals(Set.of("exit_code", "timed_out"), fieldNames(payload));
    }

    @Test
    void errorWireShapeMatchesCodexExactly() throws Exception {
        // codex ipc_framed.rs 单测同款断言：整树逐字段相等
        JsonNode actual = IpcJson.toJson(new IpcMessage.Error("CreateProcessAsUserW failed",
                IpcMessage.ErrorStage.SPAWN_CHILD, 1312));
        JsonNode expected = JSON.readTree("""
                {"version":6,"type":"error","payload":{
                  "message":"CreateProcessAsUserW failed","stage":"spawn_child",
                  "windows_error_code":1312}}
                """);
        assertEquals(expected, actual);

        JsonNode nullCode = payloadOf(new IpcMessage.Error("late",
                IpcMessage.ErrorStage.WRITE_SPAWN_READY, null), "error");
        assertTrue(nullCode.get("windows_error_code").isNull());
        assertEquals("write_spawn_ready", nullCode.get("stage").asText());
        assertEquals(Set.of("message", "stage", "windows_error_code"), fieldNames(nullCode));
    }

    @Test
    void errorStageAndStreamWireValuesAlignWithCodex() {
        assertEquals("read_spawn_request", IpcMessage.ErrorStage.READ_SPAWN_REQUEST.wireName());
        assertEquals("spawn_child", IpcMessage.ErrorStage.SPAWN_CHILD.wireName());
        assertEquals("write_spawn_ready", IpcMessage.ErrorStage.WRITE_SPAWN_READY.wireName());
        assertEquals("stdout", IpcMessage.Stream.STDOUT.wireName());
        assertEquals("stderr", IpcMessage.Stream.STDERR.wireName());
        assertEquals(6, IpcMessage.IPC_PROTOCOL_VERSION);
    }

    @Test
    void decodeToleratesMissingOptionalSpawnRequestFields() {
        JsonNode root = parse("{\"version\":6,\"type\":\"spawn_request\",\"payload\":{"
                + "\"command\":[\"cmd.exe\"],\"cwd\":\"C:/x\",\"env\":{},\"cap_sids\":[\"S-1-1-0\"]}}");
        IpcMessage.SpawnRequest req = (IpcMessage.SpawnRequest) IpcJson.fromJson(root);
        assertNull(req.networkIdentity());
        assertNull(req.timeoutMs());
        assertFalse(req.stdinOpen());
        assertFalse(req.tty());
        assertNull(req.privateDesktopName());
        assertTrue(req.writeRoots().isEmpty());
        assertEquals(List.of("S-1-1-0"), req.capSids());
    }

    // ---- helpers ----

    private static JsonNode payloadOf(IpcMessage message, String expectedTag) {
        JsonNode root = IpcJson.toJson(message);
        assertEquals(IpcMessage.IPC_PROTOCOL_VERSION, root.get("version").asInt());
        assertEquals(expectedTag, root.get("type").asText());
        return root.get("payload");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Iterator<String> it = node.fieldNames(); it.hasNext();) {
            names.add(it.next());
        }
        return names;
    }

    private static List<String> texts(JsonNode arr) {
        List<String> out = new java.util.ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
