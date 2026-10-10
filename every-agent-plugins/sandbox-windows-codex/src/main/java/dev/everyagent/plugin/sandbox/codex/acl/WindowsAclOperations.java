package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.sun.jna.Memory;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32Util;

/**
 * {@link AclOperations} 的 Windows 实现——SID 字符串 ↔ PSID 转换 +
 * 委托 {@link AclPrimitives}/{@link DenyAcePrimitives}。
 *
 * <p>PSID 缓存为 {@link Memory}（强引用防 GC 回收底层内存——SetEntriesInAclW
 * 只在调用期间借用指针，但缓存让重复施加同一主体的 ACE 无需反复转换）。
 *
 * <p>仅 Windows 运行时被实际调用；构造不触发原生库加载（方法内按需加载），
 * 跨平台单测可安全持有 {@link #INSTANCE} 注入编排层。
 */
public final class WindowsAclOperations implements AclOperations {

    /** 单例（无状态，仅缓存 PSID 内存）。 */
    public static final WindowsAclOperations INSTANCE = new WindowsAclOperations();

    private final ConcurrentMap<String, Memory> psidCache = new ConcurrentHashMap<>();

    private WindowsAclOperations() {
    }

    /**
     * SID 字符串（如 {@code S-1-5-21-…}）→ PSID 指针。
     * 转换失败（非法 SID 串）抛 {@link IllegalArgumentException}。
     * 返回的指针由本类缓存持有，进程内有效。
     */
    public Pointer psid(String sid) {
        return psidCache.computeIfAbsent(sid, key -> {
            byte[] bytes = Advapi32Util.convertStringSidToSid(key);
            Memory memory = new Memory(bytes.length);
            memory.write(0, bytes, 0, bytes.length);
            return memory;
        });
    }

    private List<Pointer> psids(List<String> sids) {
        return sids.stream().map(this::psid).toList();
    }

    @Override
    public boolean addDenyReadAce(Path path, String sid) throws IOException {
        return DenyAcePrimitives.addDenyReadAce(path, psid(sid));
    }

    @Override
    public boolean addDenyWriteAce(Path path, String sid) throws IOException {
        return DenyAcePrimitives.addDenyWriteAce(path, psid(sid));
    }

    @Override
    public void revokeAce(Path path, String sid) throws IOException {
        DenyAcePrimitives.revokeAce(path, psid(sid));
    }

    @Override
    public boolean ensureAllowWriteAces(Path path, List<String> sids) throws IOException {
        return AclPrimitives.ensureAllowWriteAces(path, psids(sids));
    }

    @Override
    public boolean ensureReadExecuteAces(Path path, List<String> sids) throws IOException {
        return AclPrimitives.ensureReadExecuteAces(path, psids(sids));
    }

    @Override
    public boolean pathWriteAcesNeedRefresh(Path path, List<String> sids) throws IOException {
        return AclPrimitives.pathWriteAcesNeedRefresh(path, psids(sids));
    }

    @Override
    public boolean pathMaskAllows(Path path, List<String> sids, int mask, boolean requireAllBits)
            throws IOException {
        return AclPrimitives.pathMaskAllows(path, psids(sids), mask, requireAllBits);
    }
}
