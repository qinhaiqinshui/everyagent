package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProvisioningAcl 编排单测（跨平台假件；对齐 setup_provisioning.rs ACL 段：
 * deny-read 先行、双主体写授权、deny-write 物化+重叠 cap 选择、读根内建跳过）。
 */
class ProvisioningAclTest {

    private static final String GROUP = "S-1-5-21-1-1-1-1";
    private static final String WS_CAP = "S-1-5-21-1-1-1-2";
    private static final String EXTRA_CAP = "S-1-5-21-1-1-1-3";

    @TempDir
    Path tmp;

    private ProvisioningRequest.Builder request(Path ws) throws IOException {
        Files.createDirectories(tmp.resolve("extra"));
        return ProvisioningRequest.builder(GROUP, tmp.resolve(".sandbox"))
                .writeRoot(ws.toString(), WS_CAP)
                .writeRoot(tmp.resolve("extra").toString(), EXTRA_CAP);
    }

    @Test
    void appliesInCodexOrderDenyReadFirst() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("workspace"));
        Path secret = Files.writeString(tmp.resolve("secret.env"), "s");
        RecordingAclOperations ops = new RecordingAclOperations();

        new ProvisioningAcl(ops).applyProvisioning(request(ws)
                .denyReadPaths(List.of(secret.toString()))
                .readRoots(List.of(tmp.toString()))
                .build());

        List<String> opsOrder = ops.calls.stream().map(RecordingAclOperations.Call::op).toList();
        int denyRead = opsOrder.indexOf("denyRead");
        int allowWrite = opsOrder.indexOf("allowWrite");
        int readExec = opsOrder.indexOf("readExec");
        assertTrue(denyRead >= 0 && allowWrite >= 0 && readExec >= 0, opsOrder.toString());
        assertTrue(denyRead < allowWrite, "deny-read 必须先于写授权: " + opsOrder);
        assertTrue(allowWrite < readExec, "读根授权最后: " + opsOrder);
        assertTrue(Files.exists(tmp.resolve(".sandbox").resolve(DenyReadState.STATE_FILE)),
                "deny-read 状态文件落在 stateRoot");
    }

    @Test
    void writeGrantUsesGroupAndCapSids() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("workspace"));
        RecordingAclOperations ops = new RecordingAclOperations();

        new ProvisioningAcl(ops).applyProvisioning(request(ws).build());

        RecordingAclOperations.Call grant = ops.calls.stream()
                .filter(c -> c.op().equals("allowWrite")).findFirst().orElseThrow();
        assertEquals(ws, grant.path());
        assertEquals(List.of(GROUP, WS_CAP), grant.sids(), "组 SID + root cap SID 双主体");
    }

    @Test
    void missingWriteRootSkipped() throws IOException {
        RecordingAclOperations ops = new RecordingAclOperations();
        Path missing = tmp.resolve("no-such-root");
        new ProvisioningAcl(ops).applyProvisioning(request(missing).build());
        assertTrue(ops.calls.stream().filter(c -> c.op().equals("allowWrite"))
                .noneMatch(c -> c.path().equals(missing)), "缺失写根跳过授权");
    }

    @Test
    void writeRootWithoutCapSidFailsClosed() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("workspace"));
        RecordingAclOperations ops = new RecordingAclOperations();
        IOException error = assertThrows(IOException.class,
                () -> new ProvisioningAcl(ops).applyProvisioning(
                        new ProvisioningRequest(GROUP, Map.of(), List.of(ws.toString()),
                                List.of(), List.of(), List.of(), tmp.resolve(".sandbox"))));
        assertTrue(error.getMessage().contains("no capability SID"), error.getMessage());
    }

    @Test
    void denyWriteCarveoutMaterializedAndCapSelectedByOverlap() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("workspace"));
        Path gitDir = ws.resolve(".git");
        Path outside = tmp.resolve("outside-protect");
        RecordingAclOperations ops = new RecordingAclOperations();

        new ProvisioningAcl(ops).applyProvisioning(request(ws)
                .denyWritePaths(List.of(gitDir.toString(), outside.toString())).build());

        assertTrue(Files.isDirectory(gitDir), "缺失 carveout 物化为目录");
        assertTrue(Files.isDirectory(outside));

        List<RecordingAclOperations.Call> denies = ops.calls.stream()
                .filter(c -> c.op().equals("denyWrite")).toList();
        // .git 在 workspace 根下 → 仅 WS_CAP；outside 无重叠根 → 回退全部活动根 cap
        assertTrue(denies.stream().anyMatch(
                c -> c.path().equals(gitDir) && c.sids().equals(List.of(WS_CAP))),
                denies.toString());
        Set<String> outsideSids = new HashSet<>();
        denies.stream().filter(c -> c.path().equals(outside))
                .forEach(c -> outsideSids.addAll(c.sids()));
        assertEquals(Set.of(WS_CAP, EXTRA_CAP), outsideSids, "回退全部活动根 cap（逐 SID 一条）");
        assertFalse(denies.stream().anyMatch(c -> c.path().equals(gitDir)
                && c.sids().contains(EXTRA_CAP)), "非重叠根的 cap 不该压到 .git 上");
    }

    @Test
    void readRootSkippedWhenBuiltinSubjectsHoldRx() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("workspace"));
        RecordingAclOperations ops = new RecordingAclOperations();
        ops.maskAllows = true; // 内建主体已持完整 RX

        new ProvisioningAcl(ops).applyProvisioning(request(ws)
                .readRoots(List.of(ws.toString())).build());

        assertTrue(ops.calls.stream().noneMatch(c -> c.op().equals("readExec")),
                "系统本就放行的读根不触碰 ACL");
    }

    @Test
    void denyReadFailureAbortsProvisioning() throws IOException {
        Path ws = Files.createDirectories(tmp.resolve("workspace"));
        Path secret = Files.writeString(tmp.resolve("secret.env"), "s");
        RecordingAclOperations ops = new RecordingAclOperations();
        ops.failOn(secret, "denyRead");

        IOException error = assertThrows(IOException.class,
                () -> new ProvisioningAcl(ops).applyProvisioning(
                        request(ws).denyReadPaths(List.of(secret.toString())).build()));
        assertTrue(error.getMessage().contains("apply deny-read ACLs"), error.getMessage());
    }

    @Test
    void denyWriteCapSelectionHelperMirrorsCodex() {
        List<String> overlapping = ProvisioningAcl.denyWriteCapSids(
                Map.of("C:/ws", WS_CAP, "C:/extra", EXTRA_CAP),
                List.of("C:\\ws", "C:\\extra"), Path.of("C:\\ws\\.git"));
        assertEquals(List.of(WS_CAP), overlapping, "重叠根选择 + 键大小写/分隔符归一");

        List<String> fallback = ProvisioningAcl.denyWriteCapSids(
                Map.of("C:/ws", WS_CAP, "C:/extra", EXTRA_CAP),
                List.of("C:\\ws", "C:\\extra"), Path.of("D:\\elsewhere"));
        assertEquals(List.of(WS_CAP, EXTRA_CAP), fallback, "无命中回退全部活动根");
    }
}

