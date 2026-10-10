package dev.everyagent.plugin.api.shell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExecResults} 的 PowerShell 辅助件单测：CLIXML 还原、退出码传导尾部、格式化尾注。
 */
class ExecResultsClixmlTest {

    @Test
    void decodeClixml恢复错误文本而非静默丢弃() {
        String clixml = "#< CLIXML\r\n"
                + "<Objs Version=\"1.1.0.4\" xmlns=\"http://schemas.microsoft.com/powershell/2004/04\">"
                + "<S S=\"Error\">rg : regex parse error:_x000D__x000A_    (?:()_x000D__x000A_"
                + "error: unclosed group_x000D__x000A_</S>"
                + "<Obj S=\"progress\" RefId=\"0\"><TN RefId=\"0\">"
                + "<T>System.Management.Automation.PSCustomObject</T></TN></Obj>"
                + "</Objs>";
        String out = ExecResults.decodeClixml(clixml);
        assertTrue(out.contains("regex parse error"), "必须留命令真实错误文本,实际: " + out);
        assertTrue(out.contains("unclosed group"), "必须留错误细节");
        assertTrue(out.contains("(?:()"), "错误载荷内容不得被当成标签吃掉");
        assertFalse(out.contains("<Objs"), "XML 骨架不得泄漏进结果");
        assertFalse(out.contains("PSCustomObject"), "类型名等流记录元数据属噪声,应剥除");
    }

    @Test
    void decodeClixml保留段外原生文本() {
        String s = "rg: IO error for operation (os error 2)\r\n"
                + "#< CLIXML\r\n<Objs><S S=\"Error\">cmdlet 炸了</S></Objs>";
        String out = ExecResults.decodeClixml(s);
        assertTrue(out.contains("os error 2"), "CLIXML 段外的原生命令 stderr 必须原样保留");
        assertTrue(out.contains("cmdlet 炸了"), "段内错误文本要抽出来");
    }

    @Test
    void decodeClixml对无CLIXML与纯噪声安全() {
        assertEquals("普通错误", ExecResults.decodeClixml("普通错误"));
        assertEquals("", ExecResults.decodeClixml(null));
        String onlyProgress = "#< CLIXML\r\n<Objs><Obj S=\"progress\"><TN><T>x</T></TN></Obj></Objs>";
        assertEquals("", ExecResults.decodeClixml(onlyProgress), "纯 progress 噪声应归零");
    }

    @Test
    void decodeClixml处理被截断的残块() {
        String truncated = "#< CLIXML\r\n<Objs><S S=\"Error\">未闭合的错误_x000D__x000A_";
        String out = ExecResults.decodeClixml(truncated);
        assertTrue(out.contains("未闭合的错误"), "截断残块也要尽量救回错误文本,实际: " + out);
    }

    @Test
    void 退出码尾部把原生退出码转成进程退出码() {
        String tail = ExecResults.POWERSHELL_EXIT_TAIL;
        assertTrue(tail.contains("$LASTEXITCODE"), "尾部须取最后一个原生子进程的退出码");
        assertTrue(tail.contains("exit "), "尾部须显式 exit,让 powershell.exe 进程码可被父进程读到");
        // 纯 cmdlet 命令时 $LASTEXITCODE 为 null,须归 0(不能报上一次命令的残留值)
        assertTrue(tail.contains("$null -ne $LASTEXITCODE"), "null 安全判定");
    }

    @Test
    void 前缀保留关键编码偏好() {
        String p = ExecResults.POWERSHELL_PREFIX;
        assertTrue(p.contains("Get-Content:Encoding"), "Get-Content 默认 UTF-8 必须保留(读中文文件)");
        assertTrue(p.contains("Out-File:Encoding"), "Out-File 默认 UTF-8 必须保留");
    }

    @Test
    void 格式化仅在非零退出码加尾注() {
        String ok = ExecResults.format(new dev.everyagent.plugin.api.spi.ExecResult(
                "out", "", 0, false));
        assertEquals("out", ok, "exit 0 不该产生噪音尾注");
        String noMatch = ExecResults.format(new dev.everyagent.plugin.api.spi.ExecResult(
                "", "", 1, false));
        assertTrue(noMatch.contains("[exit code: 1]"), "rg 无匹配=1 是判读信号,必须保留");
    }
}
