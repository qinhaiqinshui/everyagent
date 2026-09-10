package dev.everyagent.worker.os.wsl;

import dev.everyagent.worker.config.WorkerProperties;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WslUmounter} 纯逻辑契约:命令拼装(wsl.exe -d <发行版> -u root -e umount
 * <挂载点>)、失败重试 lazy(umount -l)、best-effort 永不抛出、非 wsl 系配置直接跳过。
 * CommandRunner 全部伪造,零真实 wsl.exe 调用。
 */
class WslUmounterTest {

    /** 记录 argv 的伪造 runner。 */
    private static final class FakeRunner implements WslUmounter.CommandRunner {
        final List<List<String>> calls = new ArrayList<>();
        int rc = 0;
        RuntimeException boom;

        @Override
        public int run(List<String> argv, long timeoutMs) {
            if (boom != null) {
                throw boom;
            }
            calls.add(List.copyOf(argv));
            assertEquals(5_000, timeoutMs, "短超时约 5 秒");
            return rc;
        }
    }

    private WorkerProperties props() {
        WorkerProperties p = new WorkerProperties();
        p.setHomeDir(java.nio.file.Path.of("build/tmp-umounter-home").toString());
        p.getSandbox().getWsl().setDistro("eagent");
        return p;
    }

    @Test
    void buildsWslUmountCommandWithDirectMountPoint() {
        FakeRunner runner = new FakeRunner();
        new WslUmounter(props(), runner).umountQuietly(Path.of("C:\\a\\b"));
        assertEquals(List.of(List.of("wsl.exe", "-d", "eagent", "-u", "root", "-e", "umount", "/c/a/b")),
                runner.calls);
    }

    @Test
    void retriesLazyUmountOnFailureAndNeverThrows() {
        FakeRunner runner = new FakeRunner();
        runner.rc = 32; // umount: not mounted / 目标忙等,任何非 0 都重试
        WslUmounter u = new WslUmounter(props(), runner);
        u.umountQuietly(Path.of("C:\\a\\b"));
        assertEquals(2, runner.calls.size());
        assertEquals("umount", runner.calls.get(0).get(6));
        assertEquals("-l", runner.calls.get(1).get(7), "第二次为 lazy umount");
        // 启动失败 / runner 异常:仍不外泄(best-effort)。
        runner.rc = -127;
        u.umountQuietly(Path.of("C:\\a\\b"));
        runner.boom = new RuntimeException("wsl.exe 不存在");
        u.umountQuietly(Path.of("C:\\a\\b"));
        assertTrue(runner.calls.size() >= 4);
    }

    @Test
    void skipsWhenBackendNotWslOrPathUnmappable() {
        // windows-mic / none / sandbox 未启用:不发起任何命令。
        FakeRunner runner = new FakeRunner();
        WorkerProperties mic = props();
        mic.getSandbox().setType("windows-mic");
        new WslUmounter(mic, runner).umountQuietly(Path.of("C:\\a\\b"));
        WorkerProperties none = props();
        none.getSandbox().setType("none");
        new WslUmounter(none, runner).umountQuietly(Path.of("C:\\a\\b"));
        WorkerProperties off = props();
        off.getSandbox().setEnabled(false);
        new WslUmounter(off, runner).umountQuietly(Path.of("C:\\a\\b"));
        assertEquals(List.of(), runner.calls);

        // wsl 系(auto/wsl-direct/wsl-bwrap 及别名)放行;UNC 形态不可映射,跳过。
        for (String type : new String[] { "auto", "", "wsl-direct", "direct", "wsl-bwrap", "wsl" }) {
            WorkerProperties p = props();
            p.getSandbox().setType(type);
            new WslUmounter(p, runner).umountQuietly(Path.of("\\\\server\\share"));
        }
        assertEquals(List.of(), runner.calls);
        new WslUmounter(props(), runner).umountQuietly(Path.of("C:\\a\\b"));
        assertEquals(1, runner.calls.size());
    }

    @Test
    void normalizeTypeAliasesMatchOsSandbox() {
        assertEquals("wsl-bwrap", WslUmounter.normalizeType("WSL"));
        assertEquals("wsl-bwrap", WslUmounter.normalizeType(" bwrap "));
        assertEquals("wsl-direct", WslUmounter.normalizeType("direct"));
        assertEquals("windows-mic", WslUmounter.normalizeType("ACL"));
        assertEquals("windows-mic", WslUmounter.normalizeType("mic"));
        assertEquals("none", WslUmounter.normalizeType("none"));
        assertEquals("auto", WslUmounter.normalizeType(null));
        assertEquals("auto", WslUmounter.normalizeType(""));
        assertEquals("auto", WslUmounter.normalizeType("未来后端"));
    }
}
