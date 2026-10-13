package dev.everyagent.plugin.api.shell;

import dev.everyagent.plugin.api.spi.ExecResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExecResults#looksFailed} 判据单测：工具结果「成功 / 失败」判定是 worker
 * {@code tool.result} 事件 {@code data.status} 的唯一来源（前端据此把工具块标红），
 * 判据漂移会让失败静默显示成成功，故在此钉死三类边界：
 * 命中的强失败文案、被框架/链上改写包一层后仍命中、以及**不得**误判的正常语义（非零退出码）。
 */
class ExecResultsFailureStatusTest {

    @Test
    void 异常回灌前缀命中() {
        assertTrue(ExecResults.looksFailed(ExecResults.TOOL_FAILURE_PREFIX + " boom"));
        assertTrue(ExecResults.looksFailed(ExecResults.TOOL_FAILURE_PREFIX
                + " No ToolCallback found for tool name: fs.nope"));
    }

    @Test
    void 超时尾注命中() {
        assertTrue(ExecResults.looksFailed("部分输出\n" + ExecResults.TIMEOUT_NOTE));
    }

    @Test
    void 前缀被包一层也能命中() {
        // contains 语义:responseData 可能被框架/链上改写(如 secret-redaction)包一层
        assertTrue(ExecResults.looksFailed("Error: " + ExecResults.TOOL_FAILURE_PREFIX + " boom"));
    }

    @Test
    void 非零退出码不算失败() {
        // rg 无匹配=1、grep/diff 同理:把退出码当失败会让「搜不到」误报成红色错误
        String rgNoMatch = ExecResults.format(new ExecResult("", "", 1, false));
        assertFalse(ExecResults.looksFailed(rgNoMatch), "rg 无匹配(exit 1)不得判为失败");
        String rgMisuse = ExecResults.format(new ExecResult("", "regex parse error", 2, false));
        assertFalse(ExecResults.looksFailed(rgMisuse), "stderr 段存在也不等于工具失败");
    }

    @Test
    void 正常与空文本不命中() {
        assertFalse(ExecResults.looksFailed(null));
        assertFalse(ExecResults.looksFailed(""));
        assertFalse(ExecResults.looksFailed("普通输出\n[exit code: 0]"));
        assertFalse(ExecResults.looksFailed(ExecResults.format(new ExecResult("ok", "", 0, false))));
    }

    @Test
    void 与format产物同源() {
        // format 的超时产物必须被同一判据识别(常量单点:文案与判定同处维护)
        assertTrue(ExecResults.looksFailed(ExecResults.format(new ExecResult("半截输出", "", 0, true))));
        assertFalse(ExecResults.looksFailed(ExecResults.format(new ExecResult("完成", "", 0, false))));
    }
}