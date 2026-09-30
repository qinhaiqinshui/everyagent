package dev.everyagent.plugin.sandbox.codex.setup;

import dev.everyagent.plugin.sandbox.codex.accounts.CapSids;

/**
 * setup helper 的 ACL 授权阶段接口（步骤 6c 集成点）。
 *
 * <p>本接口由 setup 域定义、由 {@code acl} 包（ACL 授权域）实现：实现方经
 * {@code META-INF/services} 注册（{@link java.util.ServiceLoader}），在
 * {@code SetupHelperMain} 的提权流程中于「网络限制就绪之后、目录锁定与 marker
 * 提交之前」被调用（对齐 run_setup_full 的 deny-read 同步先行 + 写根授权顺序）。
 * 步骤 6a 未注册实现时按可选阶段跳过；实现抛错即整个 setup fail-closed。
 *
 * <p>实现约定（对齐 acl 包职责边界）：
 * <ul>
 *   <li>读根展开/组授 RX（ReadAclInstaller 语义）；</li>
 *   <li>deny-read / deny-write ACE（含哨兵目录预建）；</li>
 *   <li>写根 allow-write ACE（沙箱组 + 每根 capability SID）。</li>
 * </ul>
 */
public interface AclApplier {

    /**
     * 在提权 helper 内应用 ACL 授权。
     *
     * @param payload  编排层下发的完整载荷（read/write roots、deny 路径、真实用户等）
     * @param groupSid 沙箱组 SID 字符串（EveryAgentCodexSandboxUsers）
     * @param capSids  已加载的 capability SID 集合（含惰性新建的写根 cap）
     * @throws Exception 任何失败——setup fail-closed，账户修复路径不解禁
     */
    void applyProvisioning(SetupPayload payload, String groupSid, CapSids capSids)
            throws Exception;
}
