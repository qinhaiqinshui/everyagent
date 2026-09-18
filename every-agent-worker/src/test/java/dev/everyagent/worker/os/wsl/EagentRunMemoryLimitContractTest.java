package dev.everyagent.worker.os.wsl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * eagent-run.py 的内存上限契约(docs/ARCHITECTURE.md §7.10):wsl 系后端的
 * {@code worker.sandbox.memory-limit-mb} 经 **cgroup v2 memory.max** 实现真实
 * 内存占用上限;**明确弃用 RLIMIT_AS**。
 *
 * <p>背景 bug:RLIMIT_AS 限的是虚拟地址空间而非内存占用——V8 指针压缩 cage
 * 保留 4GB、每个 Wasm memory 带 GB 级 guard region(均为保留不提交),4GB as
 * 上限下任何含 Wasm 的 Node 工作负载(undici llhttp / node fetch / vite build)
 * 一实例化 Wasm 即 {@code RangeError: WebAssembly.Memory(): could not allocate
 * memory} 崩溃(vite build 全量失败,空脚本同位崩溃证实为环境问题)。
 *
 * <p>本测试以源码关键要素断言钉住:①runner 不得再设 RLIMIT_AS;②内存走
 * cgroup(memory.max + swap.max=0 + 自迁移 cgroup.procs);③worker↔runner
 * 载荷键为 memMb(两侧同步);④死会话 cgroup 组有回收路径。脚本行为级验证在
 * WSL 真机上完成(修复 commit 附实测记录)。
 */
class EagentRunMemoryLimitContractTest {

    private static final Path RUNNER = Path.of("..", "runtime", "wsl", "eagent-run.py");
    private static final Path DIRECT = Path.of("src", "main", "java", "dev",
            "everyagent", "worker", "os", "wsl", "WslDirectSandbox.java");
    private static final Path BWRAP = Path.of("src", "main", "java", "dev",
            "everyagent", "worker", "os", "wsl", "WslBwrapSandbox.java");

    private static String read(Path p) throws Exception {
        assumeTrue(Files.isRegularFile(p), "源码树外运行(打包环境),跳过");
        return Files.readString(p);
    }

    @Test
    void neverSetsRlimitAs() throws Exception {
        String src = read(RUNNER);
        assertFalse(src.contains("resource.RLIMIT_AS"),
                "不得再设 RLIMIT_AS:虚拟地址空间限制对 V8/Wasm 是毒药"
                        + "(V8 cage 4GB + Wasm guard region,undici/vite 必崩,§7.10)");
    }

    @Test
    void memoryGoesThroughCgroupV2() throws Exception {
        String src = read(RUNNER);
        int def = src.indexOf("def apply_memory_cgroup(");
        assertTrue(def > 0, "缺少 apply_memory_cgroup 定义");
        String body = src.substring(def, src.indexOf("\ndef ", def + 10));
        assertTrue(body.contains("memory.max"), "须写 memory.max(真实内存上限)");
        assertTrue(body.contains("memory.swap.max"),
                "须写 memory.swap.max=0:WSL 默认带 swap,不关则超限页被换出而非 OOM,上限形同虚设");
        assertTrue(body.contains("cgroup.procs"),
                "须把自身 pid 迁入组:exec 后整棵命令树在组内");
        assertTrue(body.contains("re.match"),
                "runId 作目录名须白名单校验,防路径注入");
        // set_limits 读 memMb 为主(旧键 asMb 仅作 worker 升级过渡期的兼容回退)
        int setLimits = src.indexOf("def set_limits(");
        assertTrue(setLimits > 0, "缺少 set_limits 定义");
        String sl = src.substring(setLimits, src.indexOf("\ndef ", setLimits + 10));
        assertTrue(sl.contains("apply_memory_cgroup(run_id, lim.get(\"memMb\")"),
                "set_limits 须把 memMb 交给 cgroup 实现");
        assertTrue(sl.contains("lim.get(\"asMb\")"),
                "须兼容旧键 asMb(旧版 worker 过渡期,值语义同为内存上限 MB,防内存限制静默丢失)");
    }

    @Test
    void allThreeExecPathsPassRunId() throws Exception {
        String src = read(RUNNER);
        // direct / seccomp sandbox 子进程 / bwrap fallback 三条路径都调用,且带 run_id
        int calls = src.split("(?m)^\\s*set_limits\\(payload\\.get\\(\"limits\"\\) or \\{\\}, run_id\\)\\s*$", -1).length - 1;
        assertTrue(calls == 3, "期望恰好 3 处 set_limits 调用行(且传 run_id),实际 " + calls);
    }

    @Test
    void deadSessionCgroupGroupsAreSwept() throws Exception {
        String src = read(RUNNER);
        int def = src.indexOf("def sweep_stale()");
        assertTrue(def > 0, "缺少 sweep_stale 定义");
        String body = src.substring(def, src.indexOf("\ndef ", def + 10));
        assertTrue(body.contains("os.rmdir(os.path.join(CGROUP_RUN_PARENT, run_id))"),
                "死会话的 cgroup 组须 best-effort 回收(EBUSY 跳过)");
    }

    @Test
    void workerPayloadKeyIsMemMbOnBothBackends() throws Exception {
        // worker↔runner 内部契约:两侧键名必须同步(改一侧不改另一侧 = 静默失去内存限制)
        for (Path java : new Path[] { DIRECT, BWRAP }) {
            String src = read(java);
            assertTrue(src.contains("\"memMb\""), java.getFileName() + " 载荷须用 memMb 键");
            assertFalse(src.contains("\"asMb\""), java.getFileName() + " 不得残留 asMb 键");
        }
    }
}
