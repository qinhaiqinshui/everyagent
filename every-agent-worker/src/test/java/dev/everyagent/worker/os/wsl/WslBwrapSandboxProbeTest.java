package dev.everyagent.worker.os.wsl;

import dev.everyagent.worker.config.WorkerProperties;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WslBwrapSandbox} 探测断因的纯函数契约:P/B/S 标记解析(缺包与 userns 失败的
 * 区分)、`wsl -l -v` 列表解析(UTF-8 与旧版 UTF-16LE 两种形态)、空发行版语义
 * (不带 -d = WSL 默认发行版)、L2 自动导入的目标解析(distro/tarball 优先级)与
 * sha256 文本解析。不发真实 wsl.exe 调用。
 */
class WslBwrapSandboxProbeTest {

    @Test
    void parseMarkersHealthySmokeReturnsNull() {
        assertNull(WslBwrapSandbox.parseMarkers("P=0\nB=0\nS=0\n"));
    }

    @Test
    void smokeBwrapMirrorsRuntimeShape() {
        // 冒烟必须与 eagent-run.py 的 RO_BASE 同构:bwrap 的 root 是全新 tmpfs,
        // 只有显式 bind 的路径存在。曾只 bind /usr,/bin、/lib64 不在 → ELF 解释器
        // 缺失 execvp ENOENT,把好端端的 userns 误报成 USERNS_FAILED
        assertTrue(WslBwrapSandbox.SMOKE_BWRAP.contains("--ro-bind-try /usr /usr"));
        assertTrue(WslBwrapSandbox.SMOKE_BWRAP.contains("--ro-bind-try /bin /bin"));
        assertTrue(WslBwrapSandbox.SMOKE_BWRAP.contains("--ro-bind-try /lib /lib"));
        assertTrue(WslBwrapSandbox.SMOKE_BWRAP.contains("--ro-bind-try /lib64 /lib64"));
        assertTrue(WslBwrapSandbox.SMOKE_BWRAP.contains("--ro-bind-try /etc /etc"));
        assertTrue(WslBwrapSandbox.SMOKE_BWRAP.contains("--proc /proc"));
        assertTrue(WslBwrapSandbox.SMOKE_BWRAP.contains("--dev /dev"));
        assertTrue(WslBwrapSandbox.SMOKE_BWRAP.contains("--tmpfs /tmp"));
        assertTrue(WslBwrapSandbox.SMOKE_BWRAP.endsWith("-- /bin/true"));
    }

    @Test
    void parseMarkersDistinguishesMissingDepsAndUserns() {
        assertEquals(WslBwrapSandbox.Cause.PYTHON3_MISSING,
                WslBwrapSandbox.parseMarkers("P=127\nB=0\nS=0\n"));
        assertEquals(WslBwrapSandbox.Cause.BWRAP_MISSING,
                WslBwrapSandbox.parseMarkers("P=0\nB=127\nS=0\n"));
        assertEquals(WslBwrapSandbox.Cause.USERNS_FAILED,
                WslBwrapSandbox.parseMarkers("P=0\nB=0\nS=1\n"));
        // python3 缺失时冒烟 rc 无意义:按 P 判,不落入 USERNS
        assertEquals(WslBwrapSandbox.Cause.PYTHON3_MISSING,
                WslBwrapSandbox.parseMarkers("P=1\nB=0\nS=1\n"));
    }

    @Test
    void parseMarkersRejectsIncompleteOrGarbledOutput() {
        assertNull(WslBwrapSandbox.parseMarkers("P=0\nB=0\n"));    // 缺 S
        assertNull(WslBwrapSandbox.parseMarkers(""));              // 空输出
        assertNull(WslBwrapSandbox.parseMarkers("P=x\nB=0\nS=0")); // 非数字
    }

    @Test
    void parseMarkersToleratesCRLFAndPadding() {
        assertEquals(WslBwrapSandbox.Cause.BWRAP_MISSING,
                WslBwrapSandbox.parseMarkers("P=0\r\nB=1\r\nS=0\r\n"));
        assertEquals(WslBwrapSandbox.Cause.BWRAP_MISSING,
                WslBwrapSandbox.parseMarkers("  P=0\n  B=1\n  S=0\n"));
    }

    @Test
    void parseDistrosReadsDefaultMarkerAndNames() {
        String out = "  NAME              STATE           VERSION\r\n"
                + "* Ubuntu            Running         2\r\n"
                + "  docker-desktop    Running         2\r\n";
        WslBwrapSandbox.DistroList dl = WslBwrapSandbox.parseDistros(out);
        assertEquals(List.of("Ubuntu", "docker-desktop"), dl.names());
        assertEquals("Ubuntu", dl.def());
    }

    @Test
    void parseDistrosReadsLegacyUtf16LeOutput() {
        // 旧版 wsl.exe 不识别 WSLENV/WSL_UTF8 时自身消息为 UTF-16LE:字节流掺 \0
        String out = utf16le("  NAME              STATE           VERSION\r\n"
                + "* Ubuntu            Running         2\r\n");
        WslBwrapSandbox.DistroList dl = WslBwrapSandbox.parseDistros(out);
        assertEquals(List.of("Ubuntu"), dl.names());
        assertEquals("Ubuntu", dl.def());
    }

    @Test
    void parseDistrosHandlesNoDistros() {
        WslBwrapSandbox.DistroList dl = WslBwrapSandbox.parseDistros(
                "适用于 Linux 的 Windows 子系统没有已安装的分发。\r\n");
        assertEquals(List.of(), dl.names());
        assertNull(dl.def());
    }

    @Test
    void asciiStripsUtf16NullBytes() {
        assertEquals("Wsl/E_ACCESSDENIED",
                WslBwrapSandbox.ascii("W\0s\0l\0/\0E\0_\0A\0C\0C\0E\0S\0S\0D\0E\0N\0I\0E\0D\0"));
        assertEquals("", WslBwrapSandbox.ascii(null));
    }

    @Test
    void isDistroNotFoundMatchesErrorCodesAndLocales() {
        // WSL 2.6 对不存在发行版报的错误码(真机实测,UTF-16 掺 NUL)
        assertTrue(WslBwrapSandbox.isDistroNotFound(
                "不存在具有所提供名称的分发。\n错误代码: Wsl/Service/WSL_E_DISTRO_NOT_FOUND"));
        // 剥 NUL 后的中文文案
        assertTrue(WslBwrapSandbox.isDistroNotFound(utf16le("不存在具有所提供名称的分发。")));
        // 英文文案与旧错误码
        assertTrue(WslBwrapSandbox.isDistroNotFound("There is no distribution with the supplied name."));
        assertTrue(WslBwrapSandbox.isDistroNotFound("no distribution named eagent"));
        // 无任何发行版时的提示
        assertTrue(WslBwrapSandbox.isDistroNotFound("适用于 Linux 的 Windows 子系统没有已安装的分发。"));
        // 无关输出不误判
        assertTrue(!WslBwrapSandbox.isDistroNotFound(""));
        assertTrue(!WslBwrapSandbox.isDistroNotFound("command not found"));
    }

    @Test
    void isAccessDeniedMatchesErrorCode() {
        assertTrue(WslBwrapSandbox.isAccessDenied("拒绝访问。\n错误代码: Wsl/E_ACCESSDENIED"));
        assertTrue(WslBwrapSandbox.isAccessDenied(utf16le("Wsl/E_ACCESSDENIED")));
        assertTrue(!WslBwrapSandbox.isAccessDenied("ok"));
    }

    @Test
    void wslCmdOmitsDForBlankDistro() {
        // 空 distro = WSL 默认发行版(wsl.exe 不带 -d)
        assertEquals(List.of("wsl.exe", "-e", "python3", "/mnt/c/x/eagent-run.py"),
                WslBwrapSandbox.wslCmd("", "-e", "python3", "/mnt/c/x/eagent-run.py"));
        assertEquals(List.of("wsl.exe", "-e", "/bin/sh", "-c", "true"),
                WslBwrapSandbox.wslCmd(null, "-e", "/bin/sh", "-c", "true"));
        assertEquals(List.of("wsl.exe", "-d", "eagent", "-e", "python3", "r.py"),
                WslBwrapSandbox.wslCmd(" eagent ", "-e", "python3", "r.py"));
    }

    @Test
    void distroLabelShowsDefaultForBlank() {
        assertEquals("默认发行版", WslBwrapSandbox.distroLabel(""));
        assertEquals("默认发行版", WslBwrapSandbox.distroLabel(null));
        assertEquals("Ubuntu", WslBwrapSandbox.distroLabel("Ubuntu"));
    }

    @Test
    void probeResultBriefCollapsesDetailToOneLine() {
        WslBwrapSandbox.ProbeResult r = new WslBwrapSandbox.ProbeResult(false,
                WslBwrapSandbox.Cause.DISTRO_NOT_FOUND, "已装: Ubuntu,\r\ndocker-desktop");
        assertEquals("发行版不存在: 已装: Ubuntu,; docker-desktop", r.brief());
        assertEquals(WslBwrapSandbox.Cause.DISTRO_NOT_FOUND.fix(), r.fix());
        assertEquals("ok", new WslBwrapSandbox.ProbeResult(true, null, "").brief());
        // 无细节:只报断因
        assertEquals("发行版缺 python3", new WslBwrapSandbox.ProbeResult(false,
                WslBwrapSandbox.Cause.PYTHON3_MISSING, "").brief());
    }

    /** 模拟 UTF-16LE ASCII 段的字节流形态(每个字符后掺 \0)。 */
    private static String utf16le(String s) {
        return s.replace("", "\0");
    }

    @Test
    void parseSha256AcceptsSha256sumAndBareHex() {
        String hex = "a".repeat(64);
        assertEquals(hex, WslBwrapSandbox.parseSha256(hex + "  eagent-rootfs.tar.gz\n"));
        assertEquals(hex, WslBwrapSandbox.parseSha256("  " + hex.toUpperCase() + "  x.tar"));
        assertEquals(hex, WslBwrapSandbox.parseSha256(hex));
    }

    @Test
    void parseSha256RejectsGarbage() {
        assertNull(WslBwrapSandbox.parseSha256(null));
        assertNull(WslBwrapSandbox.parseSha256(""));
        assertNull(WslBwrapSandbox.parseSha256("   "));
        assertNull(WslBwrapSandbox.parseSha256("abc"));          // 太短
        assertNull(WslBwrapSandbox.parseSha256("z".repeat(64))); // 非 hex
    }

    @Test
    void tarballForPrefersRuntimeThenConfigured() throws IOException {
        Path home = Files.createTempDirectory("eagent-home");
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        // 程序根(= user.dir)/runtime/wsl 无镜像 + 无配置:自动导入关闭(开发机零打扰)
        assertNull(WslBwrapSandbox.tarballFor(props));
        // 配置 tarball(绝对路径)兜底命中
        Path other = Files.createTempFile("rootfs", ".tar.gz");
        props.getSandbox().getWsl().setTarball(other.toString());
        assertEquals(other, WslBwrapSandbox.tarballFor(props));
        // 配置空串 = 关闭(且程序根无镜像)
        props.getSandbox().getWsl().setTarball(" ");
        assertNull(WslBwrapSandbox.tarballFor(props));
    }

    @Test
    void effectiveDistroPrefersExplicitThenManagedThenDefault() throws IOException {
        Path home = Files.createTempDirectory("eagent-home");
        WorkerProperties props = new WorkerProperties();
        props.setHomeDir(home.toString());
        // 无配置无镜像:WSL 默认发行版(空)
        assertEquals("", WslBwrapSandbox.effectiveDistro(props));
        // 配置 tarball 兜底命中 → 托管 eagent(打包产品的零配置形态)
        Path other = Files.createTempFile("rootfs", ".tar.gz");
        props.getSandbox().getWsl().setTarball(other.toString());
        assertEquals(WslBwrapSandbox.MANAGED_DISTRO, WslBwrapSandbox.effectiveDistro(props));
        // 显式配置最优先(即使镜像在位)
        props.getSandbox().getWsl().setDistro("Ubuntu");
        assertEquals("Ubuntu", WslBwrapSandbox.effectiveDistro(props));
    }

    @Test
    void applyPersistentStateCreatesDirsAndDefaultFiles() throws IOException {
        Path root = Files.createTempDirectory("persist");
        WorkerProperties props = new WorkerProperties();
        props.getSandbox().setPersistentRoot(root.toString());
        List<Map<String, String>> binds = new ArrayList<>();
        WslBwrapSandbox.applyPersistentState(props, binds, new LinkedHashSet<>());
        // 目录与默认文件均预建
        assertTrue(Files.isDirectory(root.resolve("home")));
        assertTrue(Files.isDirectory(root.resolve("opt")));
        assertTrue(Files.isDirectory(root.resolve("usr-local")));
        assertTrue(Files.isRegularFile(root.resolve("resolv.conf")));
        assertTrue(Files.isRegularFile(root.resolve("env")));
        assertTrue(Files.readString(root.resolve("resolv.conf")).contains("nameserver"));
        assertTrue(Files.readString(root.resolve("env")).contains("KEY=VALUE"));
    }

    @Test
    void applyPersistentEnvParsesKeyValueAndSkipsComments() throws IOException {
        Path root = Files.createTempDirectory("persist-env");
        Files.writeString(root.resolve("env"), "# 注释\n\nJAVA_HOME=/opt/jdk25\nMAVEN_HOME=/opt/maven\n");
        WorkerProperties props = new WorkerProperties();
        props.getSandbox().setPersistentRoot(root.toString());
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/usr/bin");
        WslBwrapSandbox.applyPersistentEnv(props, env);
        assertEquals("/opt/jdk25", env.get("JAVA_HOME"));
        assertEquals("/opt/maven", env.get("MAVEN_HOME"));
        assertEquals("/usr/bin", env.get("PATH")); // 已有键不受影响
    }

    @Test
    void mergeDiagnosticsCombinesStdoutAndStderr() {
        // 空侧直接取另一侧
        assertEquals("", WslBwrapSandbox.mergeDiagnostics("", ""));
        assertEquals("out", WslBwrapSandbox.mergeDiagnostics("out", ""));
        assertEquals("err", WslBwrapSandbox.mergeDiagnostics("", "err"));
        // 两路都有:以换行拼接,供 contains 错误码匹配
        assertEquals("0\nwsl: 检测到 localhost 代理配置", WslBwrapSandbox.mergeDiagnostics("0", "wsl: 检测到 localhost 代理配置"));
        // null 视为空
        assertEquals("", WslBwrapSandbox.mergeDiagnostics(null, null));
        assertEquals("err", WslBwrapSandbox.mergeDiagnostics(null, "err"));
        // 合并视图必须保留错误码(可被 isAccessDenied/isDistroNotFound 命中)
        String merged = WslBwrapSandbox.mergeDiagnostics("", "拒绝访问。\n错误代码: Wsl/E_ACCESSDENIED");
        assertTrue(WslBwrapSandbox.isAccessDenied(merged));
        String merged2 = WslBwrapSandbox.mergeDiagnostics("no distribution named eagent", "");
        assertTrue(WslBwrapSandbox.isDistroNotFound(merged2));
    }
}
