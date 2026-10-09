package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.api.shell.ShellExecutor;
import dev.everyagent.plugin.api.spi.CommandGate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门禁包装契约单测（§7.8）。
 *
 * <p>后端的命令执行器归插件所有，worker 无法拦命令串——故门禁必须由插件在 spawn 前调用。
 * 本组测试钉死两条:
 * <ol>
 *   <li><b>门禁先于执行</b>：授权检查必须在委托执行之前发生（授权通过后 worker 已把授权范围
 *       下发沙箱，执行时才能用上）；</li>
 *   <li><b>拒绝即短路</b>：授权被拒 → 命令<b>绝不执行</b>，异常向上回灌模型。</li>
 * </ol>
 */
class CodexBashToolProviderGateTest {

    /** 记录调用序列的假执行器 / 假门禁。 */
    private static final class Recorder {
        final List<String> events = new ArrayList<>();

        ShellExecutor delegate() {
            return (command, shell) -> {
                events.add("exec:" + command);
                return "ok";
            };
        }

        CommandGate gate(boolean allow) {
            return command -> {
                events.add("gate:" + command);
                if (!allow) {
                    throw new RuntimeException("拒绝");
                }
            };
        }
    }

    @Test
    void gateRunsBeforeExecution() {
        Recorder r = new Recorder();
        ShellExecutor gated = CodexBashToolProvider.gated(r.gate(true), r.delegate());

        assertEquals("ok", gated.execute("Remove-Item C:\\other\\x.txt", "powershell"));
        assertEquals(List.of("gate:Remove-Item C:\\other\\x.txt",
                "exec:Remove-Item C:\\other\\x.txt"), r.events,
                "授权检查必须先于委托执行");
    }

    @Test
    void denialShortCircuitsBeforeExecution() {
        Recorder r = new Recorder();
        ShellExecutor gated = CodexBashToolProvider.gated(r.gate(false), r.delegate());

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> gated.execute("Remove-Item C:\\other\\x.txt", "powershell"));
        assertEquals("拒绝", e.getMessage(), "拒绝原因回灌模型");
        assertEquals(List.of("gate:Remove-Item C:\\other\\x.txt"), r.events,
                "被拒命令绝不执行(执行器不得被调用)");
        assertTrue(r.events.stream().noneMatch(s -> s.startsWith("exec:")), "无任何 exec 事件");
    }

    @Test
    void gateSeesOriginalCommandNotPathInjectedCopy() {
        Recorder r = new Recorder();
        // 门禁必须看到用户原始命令(引号保留),不得看到加工后的副本——否则授权请求文案与
        // 危险动词扫描都会走形。
        String original = "Get-Content \"C:\\Windows\\win.ini\"";
        ShellExecutor gated = CodexBashToolProvider.gated(r.gate(true), r.delegate());
        gated.execute(original, "powershell");
        assertEquals("gate:" + original, r.events.get(0), "门禁收到原始命令");
    }
}