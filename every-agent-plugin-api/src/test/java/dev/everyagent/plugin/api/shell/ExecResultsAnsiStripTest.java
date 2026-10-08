package dev.everyagent.plugin.api.shell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ExecResults#stripAnsi} 与 stderr 解码链出口的 VT 剥离单测
 * （ISSUES 四.8：pwsh 7 错误流直出的 ANSI/VT 着色残留不得进结果）。
 */
class ExecResultsAnsiStripTest {

    private static final String ESC = "\u001B";

    @Test
    void 剥离CSI着色序列() {
        assertEquals("rg: 找不到路径",
                ExecResults.stripAnsi(ESC + "[31;1mrg: 找不到路径" + ESC + "[0m"));
        assertEquals("red\nnormal",
                ExecResults.stripAnsi(ESC + "[31mred" + ESC + "[0m\nnormal"));
    }

    @Test
    void 剥离OSC标题序列() {
        assertEquals("正文", ExecResults.stripAnsi(ESC + "]0;title\u0007正文"));
        assertEquals("正文", ExecResults.stripAnsi(ESC + "]0;t" + ESC + "\\正文"));
        assertEquals("", ExecResults.stripAnsi(ESC + "]0;截断在串尾"));
    }

    @Test
    void 剥离两字符ESC序列() {
        assertEquals("x", ExecResults.stripAnsi(ESC + "Mx"));
    }

    @Test
    void 纯文本与中文与BEL不动() {
        assertEquals("普通错误 中文 OK", ExecResults.stripAnsi("普通错误 中文 OK"));
        assertEquals("a\u0007b", ExecResults.stripAnsi("a\u0007b"));
        assertEquals("", ExecResults.stripAnsi(""));
        assertEquals("", ExecResults.stripAnsi(null));
    }

    @Test
    void decodeClixml无CLIXML分支也剥净() {
        // 复现形态:pwsh 7 错误流直出,CSI 着色包裹中文错误文本,无 CLIXML 头
        assertEquals("Get-ChildItem : 找不到路径“C:\\no-such”，因为该路径不存在。",
                ExecResults.decodeClixml(ESC + "[31;1mGet-ChildItem : "
                        + "找不到路径“C:\\no-such”，因为该路径不存在。" + ESC + "[0m"));
    }

    @Test
    void decodeClixml段外原生文本与载荷都剥净() {
        String s = ESC + "[31mrg: IO error (os error 2)" + ESC + "[0m\r\n"
                + "#< CLIXML\r\n<Objs><S S=\"Error\">cmdlet " + ESC + "[33m炸了" + ESC + "[0m</S></Objs>";
        String out = ExecResults.decodeClixml(s);
        assertEquals("rg: IO error (os error 2)\r\ncmdlet 炸了", out);
    }
}
