package dev.everyagent.plugin.sandbox.codex.acl;

import dev.everyagent.plugin.sandbox.codex.accounts.CapSids;
import dev.everyagent.plugin.sandbox.codex.setup.AclApplierImpl;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AclApplierImpl（步骤 6c setup↔acl 适配器）组装映射单测：payload →
 * ProvisioningRequest → ProvisioningAcl 的调用面，用 acl 包测试假件
 * RecordingAclOperations 验证（跨平台；对齐 run_setup_full 的 ACL 段）。
 */
class AclApplierImplTest {

    private static final String GROUP = "S-1-5-21-9-9-9-9";
    private static final String WS_CAP = "S-1-5-21-1-1-1-2";
    private static final String EXTRA_CAP = "S-1-5-21-1-1-1-3";

    @TempDir
    Path tmp;

    private Path codexHome() throws IOException {
        return Files.createDirectories(tmp.resolve("codex"));
    }

    private SetupPayload payload(Path codexHome, Path ws, Path extra) {
        SetupPayload payload = SetupPayload.create()
                .accounts("EveryAgentCodex", codexHome.toString(), "real-user");
        payload.model().commandCwd = ws.toString();
        payload.model().writeRoots = new java.util.ArrayList<>(
                List.of(ws.toString(), extra.toString()));
        return payload;
    }

    private CapSids seededCaps(Path ws, Path extra) {
        CapSids caps = new CapSids();
        caps.workspace = "S-1-5-21-4-4-4-4";
        caps.readonly = "S-1-5-21-5-5-5-5";
        caps.workspaceByCwd.put(CapSids.canonicalPathKey(ws), WS_CAP);
        caps.writableRootByPath.put(CapSids.canonicalPathKey(extra), EXTRA_CAP);
        return caps;
    }

    @Test
    void mapsWriteRootsToGroupAndCapSidsViaCwdKeying() throws Exception {
        Path codexHome = codexHome();
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path extra = Files.createDirectories(tmp.resolve("extra"));
        RecordingAclOperations ops = new RecordingAclOperations();

        new AclApplierImpl(req -> new ProvisioningAcl(ops).applyProvisioning(req))
                .applyProvisioning(payload(codexHome, ws, extra), GROUP,
                        seededCaps(ws, extra));

        assertTrue(ops.calls.stream().anyMatch(c -> c.op().equals("allowWrite")
                && c.path().equals(ws) && c.sids().equals(List.of(GROUP, WS_CAP))),
                "cwd 根 → workspace_by_cwd cap：" + ops.calls);
        assertTrue(ops.calls.stream().anyMatch(c -> c.op().equals("allowWrite")
                && c.path().equals(extra) && c.sids().equals(List.of(GROUP, EXTRA_CAP))),
                "额外根 → writable_root_by_path cap：" + ops.calls);
    }

    @Test
    void expandsDenyReadGlobsAndKeepsExactPaths() throws Exception {
        Path codexHome = codexHome();
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path extra = Files.createDirectories(tmp.resolve("extra"));
        Path secret = Files.writeString(ws.resolve("secret.env"), "s");
        Path secrets = Files.createDirectories(ws.resolve("secrets"));
        Files.writeString(secrets.resolve("a.txt"), "a");
        Files.writeString(Files.createDirectories(secrets.resolve("sub")).resolve("b.txt"), "b");
        SetupPayload payload = payload(codexHome, ws, extra);
        payload.model().denyReadPaths = List.of(secret.toString(),
                secrets + "/**"); // glob：最长非通配前缀（secrets/）为根（简化见类注释）
        RecordingAclOperations ops = new RecordingAclOperations();

        new AclApplierImpl(req -> new ProvisioningAcl(ops).applyProvisioning(req))
                .applyProvisioning(payload, GROUP, seededCaps(ws, extra));

        List<Path> denyReads = ops.calls.stream()
                .filter(c -> c.op().equals("denyRead")).map(RecordingAclOperations.Call::path)
                .toList();
        assertTrue(denyReads.contains(secret), "精确路径透传：" + denyReads);
        assertTrue(denyReads.contains(secrets.resolve("a.txt"))
                && denyReads.contains(secrets.resolve("sub").resolve("b.txt")),
                "glob 快照展开为逐路径 deny：" + denyReads);
        assertTrue(Files.exists(codexHome.resolve(".sandbox")
                .resolve(DenyReadState.STATE_FILE)), "stateRoot = codexHome/.sandbox");
    }

    @Test
    void passthroughDenyWriteAndReadRoots() throws Exception {
        Path codexHome = codexHome();
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path extra = Files.createDirectories(tmp.resolve("extra"));
        Path gitDir = ws.resolve(".git");
        SetupPayload payload = payload(codexHome, ws, extra);
        payload.model().denyWritePaths = List.of(gitDir.toString());
        payload.model().readRoots = List.of(ws.toString());
        RecordingAclOperations ops = new RecordingAclOperations();

        new AclApplierImpl(req -> new ProvisioningAcl(ops).applyProvisioning(req))
                .applyProvisioning(payload, GROUP, seededCaps(ws, extra));

        assertTrue(ops.calls.stream().anyMatch(c -> c.op().equals("denyWrite")
                && c.path().equals(gitDir) && c.sids().equals(List.of(WS_CAP))),
                ".git 在 ws 根下 → 仅重叠根 cap：" + ops.calls);
        assertTrue(ops.calls.stream().anyMatch(c -> c.op().equals("readExec")
                && c.path().equals(ws) && c.sids().equals(List.of(GROUP))),
                "读根组授权：" + ops.calls);
    }

    @Test
    void lazilyCreatesAndPersistsMissingRootCapSids() throws Exception {
        Path codexHome = codexHome();
        Path ws = Files.createDirectories(tmp.resolve("ws"));
        Path extra = Files.createDirectories(tmp.resolve("extra"));
        CapSids caps = seededCaps(ws, extra);
        caps.writableRootByPath.clear(); // extra 根无 cap → 惰性创建

        var request = AclApplierImpl.toProvisioningRequest(payload(codexHome, ws, extra),
                GROUP, caps);

        String created = request.capSids().get(extra.toString());
        assertNotNull(created, "缺失根 cap 惰性补齐进请求");
        assertTrue(CapSids.CAP_SID_PATTERN.matcher(created).matches(), created);
        assertEquals(created, caps.writableRootByPath.get(CapSids.canonicalPathKey(extra)),
                "就地写回已加载的 CapSids 实例");
        assertTrue(Files.exists(CapSids.capSidFile(codexHome)), "cap_sid 全量重写持久化");
        assertEquals(codexHome.resolve(".sandbox"), request.stateRoot());
        assertEquals(List.of(ws.toString(), extra.toString()), request.writeRoots());
    }
}
