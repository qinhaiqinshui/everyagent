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
import java.net.URLClassLoader;
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

    /** 依赖 jar 文件名匹配子串（jna 运行时 + jackson 帧编解码 + slf4j 日志，设计 §2 模块依赖）。
     * 来源:classpath 与插件 classloader URLs 两路(见 materializationSources);打包形态下
     * 另有 fat jar 解包一路(见 {@link #extractFatJarEntries})。 */
    public static final List<String> DEPENDENCY_MATCHERS = List.of(
            "jna-", "jna-platform-",
            "jackson-core-", "jackson-databind-", "jackson-annotations-",
            "slf4j-api-", "slf4j-simple-");

    /**
     * helper/runner 运行的<b>必需依赖家族</b>（DEPENDENCY_MATCHERS 子集）。文件来源
     * （classpath/插件 classloader）覆盖全部家族 = 无需 fat jar 解包（dev/CLI 态）；
     * 缺任一家族 = 走 {@link #extractFatJarEntries} 解包（打包态）。slf4j-simple 是
     * 可选 provider（NOP 回退无害），不列必需。
     */
    static final List<String> REQUIRED_FAMILY_MATCHERS = List.of(
            "jna-", "jna-platform-",
            "jackson-core-", "jackson-databind-", "jackson-annotations-",
            "slf4j-api-");

    /** 指纹里携带的摘要前缀字节数（SHA-256 前 8 字节 = 16 个 hex 字符）。 */
    public static final int FINGERPRINT_PREFIX_BYTES = 8;

    private RunnerMaterializer() {
    }

    private static final System.Logger LOG =
            System.getLogger(RunnerMaterializer.class.getName());

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

    /**
     * 嵌套 codeSource 描述：插件 jar 位于 fat jar 的 {@code BOOT-INF/lib/} 内
     * （codeSource URL 形如 {@code jar:file:<fatJar>!/BOOT-INF/lib/<plugin>.jar!/}）。
     * 该形态下 {@link #pluginCodeSource()} 无法解析为真实文件路径（{@code Path.of}
     * 不接受 {@code jar:} 协议），插件本体也须经 {@link #extractFatJarEntries} 解包。
     *
     * @return 嵌套描述；file: 协议（dev classes 目录 / 桌面内置插件 target jar）返回 null
     */
    public static NestedCodeSource nestedCodeSource() {
        var codeSource = RunnerMaterializer.class.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return null;
        }
        var url = codeSource.getLocation();
        if (!"jar".equals(url.getProtocol())) {
            return null;
        }
        // jar:file:<fatJar>!/<entry>!/ → 剥 jar: 前缀与尾部 !/ 后按 "!/" 切分
        String spec = url.toString();
        if (spec.startsWith("jar:")) {
            spec = spec.substring(4);
        }
        if (spec.endsWith("!/")) {
            spec = spec.substring(0, spec.length() - 2);
        }
        int bang = spec.indexOf("!/");
        if (bang <= 0) {
            return null;
        }
        try {
            Path fatJar = Path.of(new java.net.URL(spec.substring(0, bang)).toURI());
            String entry = spec.substring(bang + 2);
            if (!entry.endsWith(".jar")) {
                return null;
            }
            return new NestedCodeSource(fatJar, entry);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.DEBUG,
                    "nested codeSource 解析失败(按非嵌套形态继续): {0}", e.toString());
            return null;
        }
    }

    /** 嵌套 codeSource：fat jar 路径 + 其 BOOT-INF/lib 内的插件 jar 条目名。 */
    public record NestedCodeSource(Path fatJar, String entryName) {
    }

    /**
     * 本插件 classloader 的 jar URLs（builtin 模式=target/ 下 findTargetJars 加载的全部
     * jar,external 模式=lib/*.jar）——slf4j-simple 这类「插件需要但 worker 依赖图没有」
     * 的 jar 只能从这里物化(父委派借不到,java.class.path 也没有;2026-10 实测 simple
     * 缺失导致 runner 落 NOP logger,debug 打点全部静默)。非 URLClassLoader 场景返回空。
     */
    public static List<Path> classloaderJarUrls() {
        ClassLoader cl = RunnerMaterializer.class.getClassLoader();
        if (!(cl instanceof URLClassLoader urls)) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        for (java.net.URL url : urls.getURLs()) {
            try {
                Path p = Path.of(url.toURI());
                if (p.toString().endsWith(".jar") && Files.isRegularFile(p)) {
                    out.add(p);
                }
            } catch (Exception ignore) {
                // 非 file: 协议等,跳过
            }
        }
        return out;
    }

    /**
     * 物化来源集 = 插件 codeSource（jar 或目录）+ classloader jar（见
     * {@link #classloaderJarUrls()}）+ classpath 里按 matcher 命中的依赖 jar
     * （canonical 去重保序）。classpath 仍是有效来源——builtin 模式下 jackson/jna
     * 等由 worker classpath 经父委派提供,物化后的 runner classpath 必须自含它们。
     */
    public static List<Path> materializationSources(String classpath, String separator) {
        return materializationSources(classpath, separator, classloaderJarUrls());
    }

    /** 可测重载:extraJars 由测试注入(绕开对真实 classloader 的依赖)。 */
    public static List<Path> materializationSources(String classpath, String separator,
            List<Path> extraJars) {
        Map<String, Path> sources = new LinkedHashMap<>();
        try {
            Path self = pluginCodeSource();
            if (Files.exists(self)) {
                sources.put(self.toAbsolutePath().normalize().toString(), self);
            }
        } catch (Exception e) {
            // 嵌套 fat jar 形态(codeSource 为 jar:…!/…!/,无真实文件可引):
            // 插件本体由 ensureRunnerClasspath 的 fat jar 解包补齐(见 nestedCodeSource)
            LOG.log(System.Logger.Level.DEBUG,
                    "plugin codeSource 不可解析为文件路径(嵌套 fat jar 形态?): {0}",
                    e.toString());
        }
        List<Path> selectable = new ArrayList<>(
                selectDependencies(extraJars, DEPENDENCY_MATCHERS));
        selectable.addAll(selectDependencies(parseClasspath(classpath, separator),
                DEPENDENCY_MATCHERS));
        for (Path dep : selectable) {
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

    // ---- fat jar 依赖解包（打包形态：依赖嵌在 BOOT-INF/lib，对 -cp 不可见） ----

    /**
     * 文件来源是否已覆盖全部必需依赖家族（jna/jackson2/slf4j-api）。
     * 覆盖 = dev/CLI 态（classpath 上有真实依赖 jar），零解包；缺任一家族 = 打包态
     * （classpath 只有 fat jar 本身），需 {@link #extractFatJarEntries} 解包。
     */
    static boolean hasRequiredDependencyFamilies(List<Path> sources) {
        java.util.Set<String> hit = new java.util.HashSet<>();
        for (Path p : sources) {
            String name = p.getFileName().toString();
            if (!name.endsWith(".jar") || !Files.isRegularFile(p)) {
                continue;
            }
            for (String matcher : REQUIRED_FAMILY_MATCHERS) {
                // 不 break：一个文件可同时满足多个家族(jna-platform-*.jar 同时记入
                // jna- 与 jna-platform-；先记先break会让后者永远缺失、误触发解包)
                if (name.contains(matcher)) {
                    hit.add(matcher);
                }
            }
        }
        return hit.containsAll(REQUIRED_FAMILY_MATCHERS);
    }

    /**
     * 纯函数：从 fat jar 的 BOOT-INF/lib 条目名里选出应解包的
     * （DEPENDENCY_MATCHERS 命中，或在 forceEntries 里——嵌套插件 jar 场景的本体）。
     */
    static List<String> selectFatJarEntries(List<String> bootInfLibEntries,
            List<String> forceEntries) {
        List<String> out = new ArrayList<>();
        for (String entry : bootInfLibEntries) {
            String name = entry.substring(entry.lastIndexOf('/') + 1);
            boolean selected = forceEntries.contains(name);
            if (!selected) {
                for (String matcher : DEPENDENCY_MATCHERS) {
                    if (name.contains(matcher)) {
                        selected = true;
                        break;
                    }
                }
            }
            if (selected) {
                out.add(entry);
            }
        }
        return out;
    }

    /**
     * 解包目标是否需要重写：缺失/大小不符直接重写；大小相等再比 CRC32
     * （同名字不同内容的重打包可检出；成本与既有 SHA-256 指纹校验同量级，且进程内缓存短路）。
     */
    static boolean needsExtract(Path target, long entrySize, long entryCrc) throws IOException {
        if (!Files.isRegularFile(target) || Files.size(target) != entrySize) {
            return true;
        }
        return fileCrc32(target) != entryCrc;
    }

    /** 文件内容 CRC32（ZipEntry.getCrc 同口径）。 */
    static long fileCrc32(Path file) throws IOException {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                crc.update(buf, 0, n);
            }
        }
        return crc.getValue();
    }

    /**
     * 把 fat jar（Spring Boot 可执行 jar）{@code BOOT-INF/lib/} 内的依赖条目（以及
     * forceEntries 指定的嵌套插件 jar 本体）解包到 {@code binDir}，返回解包后文件列表。
     *
     * <p><b>动机（2026-10 桌面打包态实测）</b>：worker 以 {@code javaw -jar worker.jar}
     * 启动时 {@code java.class.path} 只有 fat jar 一个条目，JNA/Jackson/slf4j 嵌在
     * {@code BOOT-INF/lib} 里对普通 {@code -cp} 完全不可见——setup helper 与 runner
     * 以 {@code -cp fat+插件jar} 启动会死在 {@code SetupPayload.<clinit>} 的
     * NoClassDefFoundError（早于任何日志，仅见 exit 1）。Spring Boot 的
     * PropertiesLauncher 也救不了：{@code loader.path} 只认文件系统路径，不解析 jar 内
     * 嵌套路径（CNFE）。唯一可靠形态 = 解包成真实文件再进 {@code -cp}。
     *
     * <p>幂等：目标已存在且「大小 + CRC32」与 zip 条目一致则跳过重写。
     * 非 fat jar（无命中条目）返回空表。
     */
    public static List<Path> extractFatJarEntries(Path fatJar, Path binDir, String groupSid,
            List<String> forceEntries) throws IOException {
        if (!Files.isRegularFile(fatJar) || !fatJar.toString().endsWith(".jar")) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(fatJar.toFile())) {
            List<String> candidates = zip.stream()
                    .map(java.util.zip.ZipEntry::getName)
                    .filter(n -> n.startsWith("BOOT-INF/lib/") && n.endsWith(".jar"))
                    .toList();
            for (String entryName : selectFatJarEntries(candidates, forceEntries)) {
                java.util.zip.ZipEntry entry = zip.getEntry(entryName);
                if (entry == null) {
                    continue;
                }
                String fileName = entryName.substring(entryName.lastIndexOf('/') + 1);
                Path target = binDir.resolve(fileName);
                if (needsExtract(target, entry.getSize(), entry.getCrc())) {
                    Files.createDirectories(binDir);
                    Path tmp = binDir.resolve(fileName + ".tmp");
                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                    }
                    try {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE);
                    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    grantGroupReadExecute(target, groupSid);
                }
                out.add(target);
            }
        }
        return out;
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
        purgeStaleArtifacts(binDir, sources);
        return entries;
    }

    /**
     * 清理物化目录里<b>同一 artifact 的陈旧版本 jar</b>。
     *
     * <p><b>为什么必须清</b>：{@link RunnerClient#collapseClasspathWildcard} 把 {@code -cp}
     * 收敛成 {@code <binDir>\*}（CreateProcessWithLogonW 命令行 1024 字符上限逼出来的），而
     * Java 的目录通配符展开该目录下<b>全部</b> .jar——文件名排序里 {@code *-0.11.0.jar} 排在
     * {@code *-1.0.0.jar} 之前，旧 jar 的同名类<b>优先命中</b>。本仓实测后果：新构建已物化进
     * 目录、worker 也已重启，runner 里却仍跑旧类，改动"看起来完全无效"，且没有任何报错。
     *
     * <p>删除失败（被正在运行的 runner JVM 锁定）只记日志不抛。兜底侧
     * {@link RunnerClient#collapseClasspathWildcard} 对"目录里有 classpath 之外的 jar"
     * <b>只警告不阻断收敛</b>——拒绝收敛会退回显式 classpath 并撞上 CreateProcessWithLogonW
     * 的 1024 命令行上限，那会让整个沙箱起不来，比遮蔽更糟。所以消除陈旧 jar 是这里的
     * <b>唯一</b>防线，不能指望调用方兜住。
     */
    static void purgeStaleArtifacts(Path binDir, List<Path> sources) {
        List<String> stale;
        try (java.util.stream.Stream<Path> walk = Files.list(binDir)) {
            List<String> existing = walk
                    .map(p -> p.getFileName().toString())
                    .toList();
            stale = staleArtifactsToPurge(existing, sources);
        } catch (IOException e) {
            // 目录不可枚举:本次不清理。收敛侧只警告不阻断(阻断=撞 1024 上限让沙箱起不来),
            // 所以这里必须留下痕迹,便于现场发现遮蔽。
            LOG.log(System.Logger.Level.WARNING,
                    "[runner] 物化目录不可枚举,跳过陈旧 jar 清理: {0}", e.getMessage());
            return;
        }
        for (String name : stale) {
            try {
                Files.deleteIfExists(binDir.resolve(name));
                LOG.log(System.Logger.Level.WARNING,
                        "[runner] 已清除物化目录里的陈旧 jar(通配符 -cp 会让它遮蔽新类): {0}",
                        name);
            } catch (IOException | RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING,
                        "[runner] 陈旧 jar 删不掉(被运行中的 runner 锁定?):{0} —— {1}"
                                + "；通配符 -cp 下它的同名类会优先命中,请停 worker 后重新构建",
                        name, e.getMessage());
            }
        }
    }

    /**
     * 纯函数（可测）：给定目录现有文件名与本次物化来源，返回应删除的陈旧 jar 名。
     *
     * <p>判据是 <b>artifact 基名相同、且该基名在 classpath 里只出现一次</b>
     *（{@code foo-0.11.0.jar} 与保留的 {@code foo-1.0.0.jar} → 基名同为 {@code foo}，删旧的）。
     * <b>同基名出现两次绝不删</b>:那是有意共存的双版本依赖——实测本插件 classpath 里就有
     * {@code jackson-core} 2.21.5 与 3.1.5 并列(Jackson 2/3 是不同 groupId,不是新旧替代),
     * 删掉任何一个都会改变 runner 行为。基名之外的一律不碰。

     */
    public static List<String> staleArtifactsToPurge(List<String> existingNames,
            List<Path> sources) {
        List<String> kept = sources.stream()
                .filter(p -> !Files.isDirectory(p))
                .map(p -> p.getFileName().toString())
                .toList();
        java.util.Set<String> keptSet = new java.util.HashSet<>(kept);
        // 每个 artifact 基名在 classpath 里出现几次:2 次说明"同 root 双版本"是有意依赖
        // (如 jackson-core 2.x 与 3.x 并存),那种绝不能删。
        java.util.Map<String, Integer> keptRootCount = new java.util.HashMap<>();
        for (String k : kept) {
            keptRootCount.merge(artifactRoot(k), 1, Integer::sum);
        }
        List<String> out = new ArrayList<>();
        for (String name : existingNames) {
            if (!name.endsWith(".jar") || keptSet.contains(name)) {
                continue;
            }
            String root = artifactRoot(name);
            if (keptRootCount.getOrDefault(root, 0) == 1) {
                out.add(name);
            }
        }
        return out;
    }

    /**
     * artifact 基名：剥掉尾部 {@code -<版本号>.jar}（版本号以数字开头）。
     * 例：{@code sandbox-windows-codex-0.11.0.jar} → {@code sandbox-windows-codex}；
     * 剥不掉（无版本段，如 {@code jna.jar}）则原样返回，从而只与同名文件配对、不误伤。
     */
    public static String artifactRoot(String jarFileName) {
        int dot = jarFileName.lastIndexOf(".jar");
        String head = dot > 0 ? jarFileName.substring(0, dot) : jarFileName;
        int dash = head.lastIndexOf('-');
        if (dash > 0 && dash + 1 < head.length()
                && Character.isDigit(head.charAt(dash + 1))) {
            return head.substring(0, dash);
        }
        return head;
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
                return target; // 已存在且大小一致 → 幂等复用，不再重拷
            }
            // 判据只比大小（不比内容哈希）：文件名必须是裸 jnidispatch.dll（System
            // .loadLibrary 按此名在 bootLibraryPath 目录内查找），无法把指纹编进文件名
            // 做版本区分。风险可接受——jna 版本由根 pom 锁定，且版本错配时 loadLibrary
            // 会显式失败（runner 发 error 帧），不会静默用错库。
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
            // 只在真正提取时打点（幂等复用路径静默，避免每条命令一行 INFO 刷屏）
            System.getLogger(RunnerMaterializer.class.getName()).log(System.Logger.Level.INFO,
                    "[runner] jnidispatch extracted to {0}", target);
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

    /**
     * 便捷入口：物化并返回 -cp 字符串（bin = {@link SandboxDirs#sandboxBinDir}）。
     *
     * <p><b>进程内只做一次</b>：产物按「大小 + SHA-256 前 8 字节」逐 jar 双份校验，8 个
     * 依赖合计约 8.3MB，一次完整校验实测 146-266ms——每条命令都付这笔钱毫无意义。缓存键
     * 是 {@code codexHome}；同一 worker 进程内 {@code java.class.path} 与插件 jar 内容不会
     * 变（换 jar 必须重启 JVM 才生效），故跳过重复校验不会让 runner 加载到陈旧类。
     *
     * <p>来源三路合流：文件来源（插件 codeSource + classpath/classloader 依赖）→
     * 必需家族齐备即止（dev/CLI 态）；打包态缺家族或插件本体嵌在 fat jar 内时，从
     * classpath（及嵌套 codeSource 指向的）fat jar 解包 BOOT-INF/lib 补齐。
     *
     * <p>失效：磁盘产物被外部清理/篡改时缓存会失真，由调用方在会话失败路径调
     * {@link #invalidateRunnerClasspath} 自愈（下一条命令重做物化）。
     */
    public static String ensureRunnerClasspath(Path codexHome, String groupSid)
            throws IOException {
        Path key = codexHome.toAbsolutePath().normalize();
        String cached = CACHED_CLASSPATHS.get(key);
        if (cached != null) {
            return cached;
        }
        List<Path> sources = new ArrayList<>(materializationSources(
                System.getProperty("java.class.path"), System.getProperty("path.separator")));
        sources.addAll(resolveFatJarSources(key, sources, groupSid));
        Path binDir = SandboxDirs.sandboxBinDir(key);
        String cp = classpathString(materialize(sources, binDir, groupSid));
        ensureJnidispatch(sources, binDir, groupSid);
        CACHED_CLASSPATHS.put(key, cp);
        System.getLogger(RunnerMaterializer.class.getName()).log(System.Logger.Level.INFO,
                "[runner] classpath 已物化（本进程不再重复校验）: {0}", binDir);
        return cp;
    }

    /**
     * setup helper 专用物化入口：与 runner 共用同一套产物与进程内缓存。
     * pre-setup 阶段沙箱组尚不存在，groupSid=null（不挂组 ACE——helper 是提权进程本就
     * 可读用户产物；setup 末尾 {@code lockBinDir} 的 OI|CI 继承会补上组 RX）。
     */
    public static String ensureHelperClasspath(Path codexHome) throws IOException {
        return ensureRunnerClasspath(codexHome, null);
    }

    /**
     * 打包态补源：必需依赖家族缺失（classpath 只有 fat jar），或插件本体嵌在 fat jar
     * 内（{@link #nestedCodeSource}）时，从 classpath 上的 fat jar 解包
     * BOOT-INF/lib 条目到 binDir。dev/CLI 态（家族齐备且非嵌套）零开销直通。
     */
    private static List<Path> resolveFatJarSources(Path codexHome, List<Path> fileSources,
            String groupSid) throws IOException {
        NestedCodeSource nested = nestedCodeSource();
        if (nested == null && hasRequiredDependencyFamilies(fileSources)) {
            return List.of();
        }
        List<String> force = nested == null ? List.of() : List.of(nested.entryName());
        Path binDir = SandboxDirs.sandboxBinDir(codexHome);
        List<Path> extracted = new ArrayList<>();
        for (Path candidate : parseClasspath(System.getProperty("java.class.path"),
                System.getProperty("path.separator"))) {
            extracted.addAll(extractFatJarEntries(candidate, binDir, groupSid, force));
        }
        if (nested != null) {
            // codeSource 的宿主 fat jar 不一定在 java.class.path 上(兜底再解一次,幂等)
            extracted.addAll(extractFatJarEntries(nested.fatJar(), binDir, groupSid, force));
        }
        if (!extracted.isEmpty()) {
            System.getLogger(RunnerMaterializer.class.getName()).log(System.Logger.Level.INFO,
                    "[runner] 打包形态:已从 fat jar 解包依赖 {0} 个 → {1}",
                    new Object[] { extracted.size(), binDir });
        }
        return extracted;
    }

    /** 丢弃 {@code codexHome} 的物化缓存（会话失败时自愈用）。 */
    public static void invalidateRunnerClasspath(Path codexHome) {
        if (codexHome != null) {
            CACHED_CLASSPATHS.remove(codexHome.toAbsolutePath().normalize());
        }
    }

    /** 清全部缓存（测试隔离用）。 */
    public static void invalidateAllRunnerClasspaths() {
        CACHED_CLASSPATHS.clear();
    }

    /** {@code codexHome}（canonical）→ 已物化的 -cp 字符串。 */
    private static final java.util.concurrent.ConcurrentHashMap<Path, String> CACHED_CLASSPATHS =
            new java.util.concurrent.ConcurrentHashMap<>();

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
