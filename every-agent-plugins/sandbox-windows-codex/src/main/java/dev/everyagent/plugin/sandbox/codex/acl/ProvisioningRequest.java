package dev.everyagent.plugin.sandbox.codex.acl;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 宿主侧 ACL 施加请求——{@link ProvisioningAcl#applyProvisioning} 的入参
 * （对齐 codex {@code setup_provisioning.rs::Payload} 的 ACL 相关字段子集）。
 *
 * <p>由 setup 编排层（步骤 6a/6c 适配器）组装；SID/根集合来自
 * CapSidStore/账户供应/工作区登记。
 *
 * @param groupSid      沙箱组 SID（如 {@code S-1-5-21-…}，EveryAgentCodexUsers）——
 *                      读授权（RX allow）与 deny-read 的主体
 * @param capSids       写根路径 → 该根 capability SID（与 {@code writeRoots} 键集对齐；
 *                      deny-write 按重叠根选择 cap；值为字符串键的 canonical 归一由
 *                      {@link ProvisioningAcl} 内部处理）
 * @param writeRoots    写根（组 SID + root cap SID 双主体 GRANT；缺失根跳过）
 * @param denyWritePaths deny-write carveout（缺失则物化为目录；含 .git/
 *                      .everyagent 等只读子路径模型，由调用方按 AllowDenyPaths 算出）
 * @param denyReadPaths deny-read 目标（精确路径；glob 需先经
 *                      {@link DenyReadGlobs#resolve} 展开——物化与状态对账在内部完成）
 * @param readRoots     读根（组 RX allow；内建主体已持完整 RX 则跳过）
 * @param stateRoot     状态目录（codexHome/.sandbox——deny_read_acl_state.json 所在；
 *                      不存在则创建）
 */
public record ProvisioningRequest(
        String groupSid,
        Map<String, String> capSids,
        List<String> writeRoots,
        List<String> denyWritePaths,
        List<String> denyReadPaths,
        List<String> readRoots,
        Path stateRoot) {

    public ProvisioningRequest {
        Objects.requireNonNull(groupSid, "groupSid");
        Objects.requireNonNull(capSids, "capSids");
        Objects.requireNonNull(writeRoots, "writeRoots");
        Objects.requireNonNull(denyWritePaths, "denyWritePaths");
        Objects.requireNonNull(denyReadPaths, "denyReadPaths");
        Objects.requireNonNull(readRoots, "readRoots");
        Objects.requireNonNull(stateRoot, "stateRoot");
        capSids = Map.copyOf(capSids);
        writeRoots = List.copyOf(writeRoots);
        denyWritePaths = List.copyOf(denyWritePaths);
        denyReadPaths = List.copyOf(denyReadPaths);
        readRoots = List.copyOf(readRoots);
    }

    /** 增量组装便捷形态（适配器/测试用；等价于直接构造 record）。 */
    public static Builder builder(String groupSid, Path stateRoot) {
        return new Builder(groupSid, stateRoot);
    }

    /** {@link #builder} 的收集器。 */
    public static final class Builder {
        private final String groupSid;
        private final Path stateRoot;
        private final Map<String, String> capSids = new java.util.LinkedHashMap<>();
        private final List<String> writeRoots = new java.util.ArrayList<>();
        private final List<String> denyWritePaths = new java.util.ArrayList<>();
        private final List<String> denyReadPaths = new java.util.ArrayList<>();
        private final List<String> readRoots = new java.util.ArrayList<>();

        private Builder(String groupSid, Path stateRoot) {
            this.groupSid = groupSid;
            this.stateRoot = stateRoot;
        }

        /** 追加写根与其 capability SID（同键覆盖）。 */
        public Builder writeRoot(String root, String capSid) {
            writeRoots.add(root);
            capSids.put(root, capSid);
            return this;
        }

        /** 追加 deny-write carveout。 */
        public Builder denyWritePaths(List<String> paths) {
            denyWritePaths.addAll(paths);
            return this;
        }

        /** 追加 deny-read 目标。 */
        public Builder denyReadPaths(List<String> paths) {
            denyReadPaths.addAll(paths);
            return this;
        }

        /** 追加读根。 */
        public Builder readRoots(List<String> roots) {
            readRoots.addAll(roots);
            return this;
        }

        /** 构建。 */
        public ProvisioningRequest build() {
            return new ProvisioningRequest(groupSid, capSids, writeRoots, denyWritePaths,
                    denyReadPaths, readRoots, stateRoot);
        }
    }
}
