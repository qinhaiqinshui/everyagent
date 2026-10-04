package dev.everyagent.plugin.sandbox.codex.session;

import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.Advapi32;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.PointerByReference;

import dev.everyagent.plugin.sandbox.codex.runner.CodexRunnerMain;
import dev.everyagent.plugin.sandbox.codex.setup.SandboxDirs;
import dev.everyagent.plugin.sandbox.codex.win.Advapi32Ex;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.EXPLICIT_ACCESS_W;
import dev.everyagent.plugin.sandbox.codex.win.struct.AclStructs.TRUSTEE_W;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * runner 依赖物化（设计文档 §2.7/§4.1，对齐 codex helper_materialization.rs）。
 *
 * <p>runner 以沙箱账户经 CreateProcessWithLogonW 启动，插件 jar 可能落在沙箱账户
 * 不可读的用户私有目录——把 runner 运行所需 jar 复制到
 * {@code <codexHome>/.sandbox-bin}（组 R+X、Protected DACL，见
 * {@link SandboxDirs#lockBinDir}）后以其为 {@code -cp}，与 codex 物化 helper 的
 * ACL 矩阵一致。来源 = 当前 JVM classpath（jna/jackson 依赖）+ 本插件自身 jar
 * （ProtectionDomain codeSource，容忍插件由自定义加载器挂载、不在 classpath 上）。
 *
 * <p>去重：目标已存在且「大小 + SHA-256 前 8 字节」指纹一致则不重拷（jar 内容
 * 随构建变，靠哈希而非文件名判新旧）。哈希/选择/组装均为纯函数，跨平台可单测；
 * 只有 {@link #grantGroupReadExecute} 是 Windows 原生调用。
 */
public final class RunnerMaterializer {

    /** runner main 类（同 jar 的第二 main，见 {@link CodexRunnerMain}）。 */
    public static final String RUNNER_MAIN = CodexRunnerMain.class.getName();

    /** 依赖 jar 文件名匹配子串（jna 运行时 + jackson 帧编解码，设计 §2 模块依赖）。 */
    public static final List<String> DEPENDENCY_MATCHERS = List.of(
            "jna-", "jna-platform-",
            "jackson-core-", "jackson-databind-", "jackson-annotations-");

    /** 指纹里携带的摘要前缀字节数（SHA-256 前 8 字节 = 16 个 hex 字符）。 */
    public static final int FINGERPRINT_PREFIX_BYTES = 8;

    private RunnerMaterializer() {
    }

    // ---- 纯函数（跨平台单测） ----

    /** classpath 属性 → 已存在的文件/目录条目（空段/缺失项剔除，保序去重）。 */
    public static List<Path> parseClasspath(String classpath, String separator) {
        Map<String, Path> seen = new LinkedHashMap<>();
        if (classpath != null && !classpath.isBlank()) {
            for (String entry : classpath.split(java.util.regex.Pattern.quote(separator))) {
                String trimmed = entry.strip();
                if (trimmed.isEmpty()) {
                    continue;
                }
                Path path = Path.of(trimmed);
                if (Files.exists(path)) {
                    seen.putIfAbsent(path.toAbsolutePath().normalize().toString(), path);
                }
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** 条目中文件名含任一 matcher 的 jar 文件（目录条目不参与依赖匹配）。 */
    public static List<Path> selectDependencies(List<Path> entries, List<String> matchers) {
        List<Path> out = new ArrayList<>();
        for (Path entry : entries) {
            String name = entry.getFileName().toString();
            if (!name.endsWith(".jar") || !Files.isRegularFile(entry)) {
                continue;
            }
            for (String matcher : matchers) {
                if (name.contains(matcher)) {
                    out.add(entry);
                    break;
                }
            }
        }
        return out;
    }

    /** 本插件自身 jar/classes 目录（ProtectionDomain codeSource）。 */
    public static Path pluginCodeSource() {
        try {
            return Path.of(RunnerMaterializer.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
        } catch (Exception e) {
            throw new IllegalStateException("resolve plugin code source failed", e);
        }
    }

    /** 物化来源集 = 插件 codeSource（jar 或目录）+ 依赖 jar（canonical 去重保序）。 */
    public static List<Path> materializationSources(String classpath, String separator) {
        Map<String, Path> sources = new LinkedHashMap<>();
        Path self = pluginCodeSource();
        if (Files.exists(self)) {
            sources.put(self.toAbsolutePath().normalize().toString(), self);
        }
        for (Path dep : selectDependencies(parseClasspath(classpath, separator),
                DEPENDENCY_MATCHERS)) {
            sources.putIfAbsent(dep.toAbsolutePath().normalize().toString(), dep);
        }
        return new ArrayList<>(sources.values());
    }

    /** 指纹 = {@code size + ":" + hex(SHA-256 前 8 字节)}（缓存键，哈希足以辨构建差异）。 */
    public static String fingerprint(Path file) throws IOException {
        long size = Files.size(file);
        byte[] digest = sha256(file);
        return fingerprint(size, digest);
    }

    static String fingerprint(long size, byte[] sha256) {
        StringBuilder sb = new StringBuilder(32);
        sb.append(size).append(':');
        for (int i = 0; i < FINGERPRINT_PREFIX_BYTES; i++) {
            sb.append(String.format("%02x", sha256[i]));
        }
        return sb.toString();
    }

    private static byte[] sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                digest.update(buf, 0, n);
            }
        }
        return digest.digest();
    }

    /** 已存在且指纹一致 → 无需重拷（「文件名+大小+SHA-256 前 8 字节」缓存语义）。 */
    public static boolean needsCopy(Path source, Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return true;
        }
        return !fingerprint(source).equals(fingerprint(target));
    }

    /** Windows -cp 拼接（';' 分隔；Windows classpath 语义固定分号）。 */
    public static String classpathString(List<Path> entries) {
        StringBuilder sb = new StringBuilder();
        for (Path entry : entries) {
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(entry);
        }
        return sb.toString();
    }

    // ---- 物化（文件复制，跨平台；ACL 授予仅 Windows 生效） ----

    /**
     * 物化来源集到 {@code binDir}；返回物化后的文件/目录路径列表（-cp 条目）。
     * jar/文件按指纹去重复制；目录来源（开发态 classes/）不复制、原样引用。
     */
    public static List<Path> materialize(List<Path> sources, Path binDir, String groupSid)
            throws IOException {
        Files.createDirectories(binDir);
        List<Path> entries = new ArrayList<>();
        for (Path source : sources) {
            if (Files.isDirectory(source)) {
                entries.add(source); // 开态 classes 目录：本机同盘可见，直接进 -cp
                continue;
            }
            Path target = binDir.resolve(source.getFileName().toString());
            if (needsCopy(source, target)) {
                Path tmp = binDir.resolve(source.getFileName() + ".tmp");
                Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                grantGroupReadExecute(target, groupSid);
            }
            entries.add(target);
        }
        return entries;
    }

    /**
     * JNA 原生库预提取到 {@code binDir}，供 runner JVM 以 {@code -Djna.boot.library.path}
     * 直指该目录（见 {@link RunnerClient#bootLibraryPathProperty}）。
     *
     * <p><b>动机</b>：JNA 默认在<b>每个进程</b>首次触碰 native 时，把 jnidispatch.dll 从
     * jar 解压到自身 {@code java.io.tmpdir} 再 LoadLibrary，并在进程退出时删除。runner 的
     * TEMP 是沙箱账户私有的 {@code .sandbox/tmp}——于是每条命令都产生一次「用户可写目录里
     * 出现一个陌生新 DLL」，既是固定启动开销，也是 EDR/AMSI 强查特征（实测 runner 启动
     * 卡 8.2s，落在 tee 之后、首次 JNA native 调用附近，见 CodexRunnerMain stage 打点）。
     * 预提取到 ACL 锁定的 {@code .sandbox-bin}（组 R+X、沙箱账户不可写）后解压路径不再
     * 发生。定位不到 jna 核心 jar 或 entry 时返回 null，调用方按原行为走。
     */
    public static Path ensureJnidispatch(List<Path> sources, Path binDir, String groupSid)
            throws IOException {
        Path jnaJar = jnaCoreJar(sources);
        if (jnaJar == null) {
            return null;
        }
        String entryName = "com/sun/jna/" + nativeResourceDir() + "/jnidispatch.dll";
        Path target = binDir.resolve("jnidispatch.dll");
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(jnaJar.toFile())) {
            java.util.zip.ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            if (Files.isRegularFile(target) && entry.getSize() > 0
                    && Files.size(target) == entry.getSize()) {
                return target; // 已提取且大小一致
            }
            Path tmp = binDir.resolve("jnidispatch.dll.tmp");
            try (InputStream in = zip.getInputStream(entry)) {
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            grantGroupReadExecute(target, groupSid);
            return target;
        }
    }

    /** jna 核心 jar（排除 jna-platform-*；由 classpath 条目里取第一个匹配的）。 */
    static Path jnaCoreJar(List<Path> sources) {
        for (Path p : sources) {
            String name = p.getFileName().toString();
            if (name.startsWith("jna-") && !name.startsWith("jna-platform-")
                    && name.endsWith(".jar") && Files.isRegularFile(p)) {
                return p;
            }
        }
        return null;
    }

    /**
     * JNA jar 内原生库资源目录名——恒取 win32 变体（按 runner 宿主 os.arch 判定）。
     * runner 只在 Windows 被拉起，故此处不按 {@code os.name} 分支；非 Windows 开发机上
     * jar 内该 win32 目录缺少对应平台名时提取自然跳过（返回 null）。
     */
    static String nativeResourceDir() {
        String arch = System.getProperty("os.arch", "").toLowerCase();
        if (arch.contains("aarch64") || arch.equals("arm64")) {
            return "win32-aarch64";
        }
        return arch.contains("64") ? "win32-x86-64" : "win32-i386";
    }

    /** 便捷入口：物化并返回 -cp 字符串（bin = {@link SandboxDirs#sandboxBinDir}）。 */
    public static String ensureRunnerClasspath(Path codexHome, String groupSid)
            throws IOException {
        List<Path> sources = materializationSources(System.getProperty("java.class.path"),
                System.getProperty("path.separator"));
        Path binDir = SandboxDirs.sandboxBinDir(codexHome);
        String cp = classpathString(materialize(sources, binDir, groupSid));
        Path jnidispatch = ensureJnidispatch(sources, binDir, groupSid);
        if (jnidispatch != null) {
            System.getLogger(RunnerMaterializer.class.getName()).log(System.Logger.Level.INFO,
                    "[runner] jnidispatch pre-extracted to {0}", jnidispatch);
        }
        return cp;
    }

    /**
     * 给物化产物挂组 SID GRANT R+X ACE（文件级、无继承位——目录级
     * {@link SandboxDirs#lockBinDir} 的 OI|CI 已覆盖常规情况，此处显式补授防
     * 「先拷贝后锁目录」顺序或继承被剥的窗口）。非 Windows 无操作（单测路径）。
     */
    static void grantGroupReadExecute(Path file, String groupSid) {
        if (!com.sun.jna.Platform.isWindows() || groupSid == null) {
            return;
        }
        WinNT.PSIDByReference psidRef = new WinNT.PSIDByReference();
        if (!Advapi32.INSTANCE.ConvertStringSidToSid(groupSid, psidRef)) {
            throw new IllegalStateException("ConvertStringSidToSid failed for " + groupSid
                    + ": " + Kernel32.INSTANCE.GetLastError());
        }
        Pointer psid = psidRef.getValue().getPointer();
        EXPLICIT_ACCESS_W ea = new EXPLICIT_ACCESS_W();
        ea.grfAccessPermissions = SandboxDirs.MASK_RX;
        ea.grfAccessMode = Advapi32Ex.GRANT_ACCESS;
        ea.grfInheritance = 0; // 文件本身
        TRUSTEE_W trustee = ea.Trustee;
        trustee.pMultipleTrustee = null;
        trustee.MultipleTrusteeOperation = 0;
        trustee.TrusteeForm = 0; // TRUSTEE_IS_SID
        trustee.TrusteeType = 2; // TRUSTEE_IS_GROUP
        trustee.ptstrName = psid;
        ea.write();
        PointerByReference newAcl = new PointerByReference();
        int set = Advapi32Ex.INSTANCE.SetEntriesInAclW(1, new EXPLICIT_ACCESS_W[] { ea },
                null, newAcl);
        if (set != 0) {
            Kernel32.INSTANCE.LocalFree(psid);
            throw new IllegalStateException("SetEntriesInAclW(bin artifact) failed: " + set);
        }
        try {
            int result = Advapi32.INSTANCE.SetNamedSecurityInfo(file.toString(),
                    1 /* SE_FILE_OBJECT */, Advapi32Ex.DACL_SECURITY_INFORMATION,
                    null, null, newAcl.getValue(), null);
            if (result != 0) {
                throw new IllegalStateException("SetNamedSecurityInfoW(bin artifact "
                        + file + ") failed: " + result);
            }
        } finally {
            Kernel32.INSTANCE.LocalFree(newAcl.getValue());
            Kernel32.INSTANCE.LocalFree(psid);
        }
    }
}
