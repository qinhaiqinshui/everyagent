package dev.everyagent.worker.os.wsl;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * eagent-run.py 的命令 stdin 契约(docs/ARCHITECTURE.md §7.10):AI 命令(bash /
 * bwrap 内进程)的 stdin 一律接 /dev/null,不得是「打开的空管道」。
 *
 * <p>背景 bug:载荷经 stdin 传入后已读毕,但 wsl.exe→发行版 的 stdio 桥接保持
 * Linux 侧管道写端打开(worker 侧关闭管道也不传播 EOF)。bash 继承该空管道时,
 * {@code rg <pattern>}(无路径参数)据 stdin 可读判定改读 stdin——静默返回空结果,
 * 与「确实无匹配」不可区分;{@code cat} 等阻塞读 stdin 的命令则挂到超时。
 *
 * <p>本测试以源码关键要素断言钉住三条执行路径(wsl-direct / bwrap / seccomp 子进程)
 * 在 exec 前都调用 {@code _stdin_null()},且 seccomp supervisor 的 priv-ans stdin
 * 通道不被误伤。脚本本身的行为级验证在 WSL 真机上完成(修复 commit 附实测记录)。
 */
class EagentRunStdinContractTest {

    private static final Path RUNNER = Path.of("..", "runtime", "wsl", "eagent-run.py");

    private static String runner() throws Exception {
        assumeTrue(Files.isRegularFile(RUNNER), "源码树外运行(打包环境),跳过");
        return Files.readString(RUNNER);
    }

    @Test
    void stdinNullHelperDup2sDevNullOntoFd0() throws Exception {
        String src = runner();
        // 辅助函数:open(/dev/null) + dup2 到 fd 0;OSError 静默不阻断命令
        int def = src.indexOf("def _stdin_null():");
        assertTrue(def > 0, "缺少 _stdin_null 定义");
        String body = src.substring(def, src.indexOf("def ", def + 10));
        assertTrue(body.contains("os.open(os.devnull, os.O_RDONLY)"));
        assertTrue(body.contains("os.dup2(fd, 0)"), "必须 dup2 到 fd 0,不能只 close(会留下非法句柄)");
    }

    @Test
    void directModeRedirectsStdinBeforeExecBash() throws Exception {
        String src = runner();
        int direct = src.indexOf("def direct_main(");
        int execBash = src.indexOf("os.execvp(\"bash\"", direct);
        assertTrue(direct > 0 && execBash > direct, "direct_main 结构异常");
        assertTrue(src.substring(direct, execBash).contains("_stdin_null()"),
                "direct 模式须在 exec bash 前重定向 stdin");
    }

    @Test
    void bwrapPathRedirectsStdinBeforeExec() throws Exception {
        String src = runner();
        // main() 非 direct 非 seccomp 的 bwrap 路径:set_limits 后 execvp bwrap 前调用
        int execBwrap = src.indexOf("os.execvp(\"bwrap\", build_bwrap_argv(payload))");
        assertTrue(execBwrap > 0, "bwrap 路径结构异常");
        assertTrue(src.substring(0, execBwrap).contains("_stdin_null()"),
                "bwrap 路径须在 exec 前重定向 stdin");
    }

    @Test
    void seccompSandboxChildRedirectsStdinButSupervisorKeepsChannel() throws Exception {
        String src = runner();
        // seccomp 子进程分支:execvp("bwrap", bwrap_argv) 前 _stdin_null()
        int seccomp = src.indexOf("def seccomp_main(");
        int execChild = src.indexOf("os.execvp(\"bwrap\", bwrap_argv)", seccomp);
        assertTrue(seccomp > 0 && execChild > seccomp, "seccomp_main 结构异常");
        assertTrue(src.substring(seccomp, execChild).contains("_stdin_null()"),
                "seccomp sandbox 子进程须在 exec bwrap 前重定向 stdin");
        // supervisor 保留 stdin 承载 priv-ans 控制帧;supervisor 主循环(子进程分支之后)
        // 不得调用 _stdin_null(),否则应答通道被切断、提权授权全部 fail-closed 拒绝
        int supervisor = src.indexOf("supervisor(父)", seccomp);
        assertTrue(supervisor > 0, "seccomp_main 结构异常(缺 supervisor 分段)");
        int loopEnd = src.indexOf("def ", supervisor);
        String loopBody = src.substring(supervisor, loopEnd < 0 ? src.length() : loopEnd);
        assertTrue(!loopBody.contains("_stdin_null()"),
                "seccomp supervisor 不得重定向 stdin(会切断 priv-ans 控制帧)");
        assertTrue(loopBody.contains("ans = sys.stdin.readline()"),
                "seccomp supervisor 须保留 priv-ans stdin 读取");
        // 全文件恰好三处独立调用行(direct / bwrap / seccomp 子进程),不多不少;
        // 多行模式只匹配"仅含调用"的行,排除定义行(def _stdin_null():)与注释
        int calls = src.split("(?m)^\\s*_stdin_null\\(\\)\\s*$", -1).length - 1;
        assertTrue(calls == 3, "期望恰好 3 处 _stdin_null() 调用行,实际 " + calls);
    }
}
