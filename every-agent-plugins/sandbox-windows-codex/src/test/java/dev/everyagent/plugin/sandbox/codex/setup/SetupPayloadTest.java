package dev.everyagent.plugin.sandbox.codex.setup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 载荷/marker/错误报告协议单测（跨平台纯逻辑；对齐 ElevationPayload、SetupMarker、
 * SetupErrorReport 的 wire 形态与两阶段哨兵语义）。
 */
class SetupPayloadTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void payloadEncodeDecodeRoundTrip() throws IOException {
        SetupPayload payload = SetupPayload.create()
                .accounts(SandboxAccounts.DEFAULT_PREFIX,
                        "C:\\Users\\dev\\.everyagent-codex-sandbox", "dev")
                .proxyPorts(List.of(8080, 8443))
                .writeRoots(List.of("C:\\ws\\repo"))
                .mode(SetupPayload.Mode.FULL);
        payload.model().commandCwd = "C:\\ws\\repo";
        payload.model().denyWritePaths = List.of("C:\\Users\\dev\\.ssh");
        payload.model().readRoots = List.of("C:\\Windows");
        payload.model().allowLocalBinding = true;

        SetupPayload decoded = SetupPayload.decodeBase64(payload.encodeBase64());
        assertEquals(SetupPayload.SETUP_VERSION, decoded.model().version);
        assertEquals("EveryAgentCodexOffline", decoded.model().offlineUsername);
        assertEquals("EveryAgentCodexOnline", decoded.model().onlineUsername);
        assertEquals("EveryAgentCodexSandboxUsers", decoded.model().groupName);
        assertEquals(List.of(8080, 8443), decoded.model().proxyPorts);
        assertEquals(List.of("C:\\ws\\repo"), decoded.model().writeRoots);
        assertEquals(List.of("C:\\Users\\dev\\.ssh"), decoded.model().denyWritePaths);
        assertEquals(SetupPayload.Mode.FULL, decoded.mode());
        assertTrue(decoded.model().allowLocalBinding);
    }

    @Test
    void payloadJsonUsesSnakeCaseWireNames() throws IOException {
        SetupPayload payload = SetupPayload.create()
                .accounts(SandboxAccounts.DEFAULT_PREFIX, "home", "user")
                .mode(SetupPayload.Mode.PROVISION_ONLY);
        JsonNode node = MAPPER.readTree(payload.toJson());
        assertTrue(node.has("offline_username"), "对齐 ElevationPayload 字段名");
        assertTrue(node.has("online_username"));
        assertTrue(node.has("codex_home"));
        assertTrue(node.has("proxy_ports"));
        assertTrue(node.has("allow_local_binding"));
        assertTrue(node.has("refresh_only"));
        assertEquals("provision-only", node.get("mode").asText());
        assertEquals(SetupPayload.SETUP_VERSION, node.get("version").asInt());
    }

    @Test
    void payloadRejectsVersionMismatch() {
        String forged = java.util.Base64.getEncoder().encodeToString(
                ("{\"version\":1,\"codex_home\":\"h\",\"mode\":\"full\"}").getBytes());
        assertThrows(IOException.class, () -> SetupPayload.decodeBase64(forged),
                "SETUP_VERSION 闸门（对齐 real_main 版本校验）");
    }

    @Test
    void payloadFileFallbackRoundTrip() throws IOException {
        SetupPayload payload = SetupPayload.create()
                .accounts(SandboxAccounts.DEFAULT_PREFIX, tempDir.toString(), "user")
                .mode(SetupPayload.Mode.REMOVE);
        Path file = SetupPayload.writePayloadFile(tempDir, payload);
        assertEquals(tempDir.resolve(".sandbox").resolve(SetupPayload.PAYLOAD_FILE_NAME), file);
        assertEquals(SetupPayload.Mode.REMOVE, SetupPayload.readPayloadFile(file).mode());
        assertTrue(SetupPayload.fitsSingleArg("short"));
        assertFalse(SetupPayload.fitsSingleArg("x".repeat(SetupPayload.MAX_ARG_UTF16_UNITS + 1)));
    }

    @Test
    void markerSchemaMatchesCodexShape() throws IOException {
        Path codexHome = tempDir;
        SetupMarker.commit(codexHome, SetupPayload.SETUP_VERSION,
                "EveryAgentCodexOffline", "EveryAgentCodexOnline",
                List.of(8080), false);
        Path file = SetupMarker.markerFile(codexHome);
        assertTrue(Files.exists(file));
        JsonNode node = MAPPER.readTree(Files.readString(file));
        assertEquals(SetupPayload.SETUP_VERSION, node.get("version").asInt());
        assertEquals("EveryAgentCodexOffline", node.get("offline_username").asText());
        assertEquals("EveryAgentCodexOnline", node.get("online_username").asText());
        assertTrue(node.has("created_at"), "RFC3339 时间戳");
        assertTrue(node.get("proxy_ports").isArray());
        assertTrue(node.has("allow_local_binding"));
        assertTrue(node.get("read_roots").isArray(), "恒空占位（对齐写端 SetupMarker）");
        assertTrue(node.get("write_roots").isArray());
        assertEquals(List.of(8080),
                new ObjectMapper().convertValue(node.get("proxy_ports"), List.class));

        SetupMarker.Model read = SetupMarker.read(codexHome);
        assertNotNull(read);
        assertEquals("EveryAgentCodexOffline", read.offlineUsername);
    }

    @Test
    void markerReadinessRequiresVersionAndSecretsDoubleGate() throws IOException {
        Path codexHome = tempDir;
        assertFalse(SetupMarker.isComplete(codexHome, SetupPayload.SETUP_VERSION),
                "无 marker 即未就绪");
        SetupMarker.commit(codexHome, SetupPayload.SETUP_VERSION, "o", "n", List.of(), false);
        assertFalse(SetupMarker.isComplete(codexHome, SetupPayload.SETUP_VERSION),
                "marker 有但凭据缺失：双闸门不放行（对齐 sandbox_setup_is_complete）");
        assertFalse(SetupMarker.isComplete(codexHome, SetupPayload.SETUP_VERSION + 1),
                "版本不匹配不放行");
    }

    @Test
    void emptyOrCorruptMarkerActsAsUnfinishedSentinel() throws IOException {
        Path codexHome = tempDir;
        Files.createDirectories(codexHome.resolve(".sandbox"));
        Files.writeString(SetupMarker.markerFile(codexHome), "");
        assertNull(SetupMarker.read(codexHome), "空 sentinel → null（阶段一语义）");
        Files.writeString(SetupMarker.markerFile(codexHome), "{ broken");
        assertNull(SetupMarker.read(codexHome), "损坏 → null");
    }

    @Test
    void errorReportProtocolRoundTrip() throws IOException {
        Path codexHome = tempDir;
        assertFalse(SetupErrorReport.read(codexHome).isPresent(), "缺失 → empty");
        SetupErrorReport.write(codexHome, SetupErrorReport.HELPER_FIREWALL_POLICY_INEFFECTIVE,
                "gp override");
        Optional<SetupErrorReport.Report> report = SetupErrorReport.read(codexHome);
        assertTrue(report.isPresent());
        assertEquals(SetupErrorReport.HELPER_FIREWALL_POLICY_INEFFECTIVE, report.get().code);
        assertEquals("gp override", report.get().message);
        // {code,message} 形态（snake_case code）
        JsonNode node = MAPPER.readTree(
                Files.readString(SetupErrorReport.errorFile(codexHome)));
        assertEquals(2, node.size());
        SetupErrorReport.clear(codexHome);
        assertFalse(SetupErrorReport.read(codexHome).isPresent(), "clear 幂等");
        SetupErrorReport.clear(codexHome); // NotFound 视作已清，不抛
    }

    @Test
    void setupExceptionCarriesStructuredCode() {
        SetupErrorReport.SetupException e = new SetupErrorReport.SetupException(
                SetupErrorReport.ORCHESTRATOR_HELPER_LAUNCH_CANCELED, "用户拒绝了 UAC 提权请求");
        assertEquals(SetupErrorReport.ORCHESTRATOR_HELPER_LAUNCH_CANCELED, e.code());
        assertTrue(e.getMessage().contains(SetupErrorReport.ORCHESTRATOR_HELPER_LAUNCH_CANCELED));
    }
}
