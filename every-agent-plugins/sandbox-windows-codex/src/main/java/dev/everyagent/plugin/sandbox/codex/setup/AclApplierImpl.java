package dev.everyagent.plugin.sandbox.codex.setup;

import dev.everyagent.plugin.sandbox.codex.accounts.CapSids;
import dev.everyagent.plugin.sandbox.codex.acl.DenyReadGlobs;
import dev.everyagent.plugin.sandbox.codex.acl.ProvisioningAcl;
import dev.everyagent.plugin.sandbox.codex.acl.ProvisioningRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AclApplier} 的 acl 域实现（步骤 6c 集成适配器；经
 * {@code META-INF/services} 注册，由 SetupHelperMain 在「网络限制就绪之后、目录
 * 锁定与 marker 提交之前」调用）。
 *
 * <p>职责：把 {@link SetupPayload}（编排层策略）+ groupSid + {@link CapSids} 组装成
 * {@link ProvisioningRequest}（acl 域入参）并施加：
 * <ul>
 *   <li>writeRoots：capSid 用 {@link #capSidForRoot}（对齐
 *       {@code CapSids.workspaceWriteCapSidForRoot} 的 cwd/非 cwd 分键语义，但在
 *       helper 已加载的 {@link CapSids} 实例上就地惰性创建，避免每根一次盘读）；</li>
 *   <li>denyReadPaths：先经 {@link DenyReadGlobs#resolve} 展开——glob 条目以其
 *       <b>最长非通配前缀</b>为扫描根（DenyReadGlobs.scanPlan 语义），
 *       相对 glob 以 commandCwd（缺省 codexHome）为根。<b>简化说明</b>：设计上
 *       deny-read glob 根可以是 stateRoot 所在盘或任一 writeRoot，此处统一以
 *       「条目内最长非通配前缀」定根、裸相对模式锚 commandCwd，不跨根合并扫描；</li>
 *   <li>writeRoots/denyWritePaths/readRoots 原样透传（缺失写根由
 *       {@link ProvisioningAcl} 幂等跳过）；</li>
 *   <li>stateRoot = {@code codexHome/.sandbox}（deny_read_acl_state.json 所在）。</li>
 * </ul>
 *
 * <p>组装为纯静态方法（{@link #toProvisioningRequest}），acl 域注入式单测可跨平台
 * 验证映射；生产构造用 {@link ProvisioningAcl} 的 Windows 原语单例。
 */
public final class AclApplierImpl implements AclApplier {

    private final ProvisioningSink sink;

    /** 生产构造（ServiceLoader 装载；Windows 原语实现）。 */
    public AclApplierImpl() {
        this(request -> new ProvisioningAcl().applyProvisioning(request));
    }

    /** 测试构造：注入 {@link ProvisioningAcl}（acl 域可见的假操作版本）。 */
    public AclApplierImpl(ProvisioningSink sink) {
        this.sink = sink;
    }

    /** {@link ProvisioningAcl#applyProvisioning} 的可注入门面。 */
    @FunctionalInterface
    public interface ProvisioningSink {
        void applyProvisioning(ProvisioningRequest request) throws IOException;
    }

    @Override
    public void applyProvisioning(SetupPayload payload, String groupSid, CapSids capSids)
            throws Exception {
        sink.applyProvisioning(toProvisioningRequest(payload, groupSid, capSids));
    }

    /** 纯组装（跨平台可测）：payload → ProvisioningRequest。 */
    public static ProvisioningRequest toProvisioningRequest(SetupPayload payload,
            String groupSid, CapSids capSids) throws IOException {
        SetupPayload.Model model = payload.model();
        Path codexHome = Path.of(model.codexHome);
        Path cwd = model.commandCwd != null ? Path.of(model.commandCwd) : null;
        ProvisioningRequest.Builder builder = ProvisioningRequest.builder(
                groupSid, SandboxDirs.sandboxDir(codexHome));
        for (String root : model.writeRoots) {
            builder.writeRoot(root, capSidForRoot(capSids, codexHome, cwd, Path.of(root)));
        }
        builder.denyWritePaths(model.denyWritePaths)
                .denyReadPaths(expandDenyReadPaths(model.denyReadPaths, cwd, codexHome))
                .readRoots(model.readRoots);
        return builder.build();
    }

    /**
     * deny-read 条目拆分：含通配符（{@code * ? [}）的进 glob 展开（快照物化为
     * 精确路径——Windows ACL 不理解 glob），其余原样透传（含缺失路径）。
     */
    static List<String> expandDenyReadPaths(List<String> denyReadPaths, Path cwd,
            Path codexHome) throws IOException {
        List<Path> exact = new ArrayList<>();
        List<String> globs = new ArrayList<>();
        for (String entry : denyReadPaths) {
            if (hasWildcard(entry)) {
                globs.add(entry);
            } else {
                exact.add(Path.of(entry));
            }
        }
        // 相对 glob 锚 commandCwd；缺省锚 codexHome（简化，见类注释）。
        Path anchor = cwd != null ? cwd : codexHome;
        List<String> out = new ArrayList<>();
        for (Path path : DenyReadGlobs.resolve(exact, globs, anchor, null)) {
            out.add(path.toString());
        }
        return out;
    }

    static boolean hasWildcard(String entry) {
        return entry.indexOf('*') >= 0 || entry.indexOf('?') >= 0 || entry.indexOf('[') >= 0;
    }

    /**
     * 写根 cap SID：root==cwd → workspace_by_cwd 键（工作区隔离 capability），否则
     * writable_root_by_path 键；缺失惰性创建并持久化（对齐
     * {@code CapSids.workspaceWriteCapSidForRoot}，就地创建免重复盘读）。
     */
    static String capSidForRoot(CapSids caps, Path codexHome, Path cwd, Path root)
            throws IOException {
        String key = CapSids.canonicalPathKey(root);
        String sid;
        if (cwd != null && key.equals(CapSids.canonicalPathKey(cwd))) {
            sid = caps.workspaceByCwd.get(key);
            if (sid == null) {
                sid = CapSids.randomCapSid();
                caps.workspaceByCwd.put(key, sid);
                caps.persist(CapSids.capSidFile(codexHome));
            }
            return sid;
        }
        sid = caps.writableRootByPath.get(key);
        if (sid == null) {
            sid = CapSids.randomCapSid();
            caps.writableRootByPath.put(key, sid);
            caps.persist(CapSids.capSidFile(codexHome));
        }
        return sid;
    }

    /** 物化便捷入口（单测/诊断用）：确保 codexHome 存在并组装一次请求。 */
    public static ProvisioningRequest dryRun(SetupPayload payload, String groupSid,
            CapSids capSids) throws IOException {
        Path codexHome = Path.of(payload.model().codexHome);
        Files.createDirectories(codexHome);
        return toProvisioningRequest(payload, groupSid, capSids);
    }
}
