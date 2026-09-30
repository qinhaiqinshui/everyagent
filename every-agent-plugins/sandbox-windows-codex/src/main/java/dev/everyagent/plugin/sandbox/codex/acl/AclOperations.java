package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * ACL 施加操作——SID 字符串形态的门面（Windows 实现见 {@link WindowsAclOperations}）。
 *
 * <p>把「Win32 PSID 裸指针原语」（{@link AclPrimitives}/{@link DenyAcePrimitives}）与
 * 「纯逻辑编排」（{@link DenyReadPlanner}/{@link DenyReadState}/
 * {@link ProvisioningAcl} 等）解耦：编排层只依赖本接口，跨平台单测用假实现，
 * Windows 上由 {@link WindowsAclOperations} 单例落地（对应 codex 各编排模块
 * 直接调 acl.rs unsafe 原语的层次）。
 */
public interface AclOperations {

    /** 给路径挂 deny-read ACE（DENY_READ_MASK、DENY_ACCESS、CI|OI、幂等）；返回是否新增。 */
    boolean addDenyReadAce(Path path, String sid) throws IOException;

    /** 给路径挂 deny-write ACE（DENY_WRITE_MASK、DENY_ACCESS、CI|OI、幂等）；返回是否新增。 */
    boolean addDenyWriteAce(Path path, String sid) throws IOException;

    /** 移除该 SID 在路径上的全部显式 ACE（REVOKE_ACCESS；AceCount 不变不落盘）。 */
    void revokeAce(Path path, String sid) throws IOException;

    /**
     * 写根授权——组 SID + capability SID 双主体、WRITE_ALLOW_MASK、SET_ACCESS、CI|OI、
     * 父目录不授 FILE_DELETE_CHILD；返回是否确有写入。
     */
    boolean ensureAllowWriteAces(Path path, List<String> sids) throws IOException;

    /** 读根组授权——RX、SET_ACCESS、CI|OI（内建主体/组已持完整 RX 时为无操作）。 */
    boolean ensureReadExecuteAces(Path path, List<String> sids) throws IOException;

    /** 任一 SID 的写授权 ACE 是否待刷新（检查失败按需刷新处理由调用方决定）。 */
    boolean pathWriteAcesNeedRefresh(Path path, List<String> sids) throws IOException;

    /**
     * 路径级掩码判定（MapGenericMask 折算 GENERIC 位后比较）——
     * 读根「内建主体已持 RX 则跳过组授权」的自检用。
     *
     * @param requireAllBits true 需全部位齐备；false 任一位即真
     */
    boolean pathMaskAllows(Path path, List<String> sids, int mask, boolean requireAllBits)
            throws IOException;
}
