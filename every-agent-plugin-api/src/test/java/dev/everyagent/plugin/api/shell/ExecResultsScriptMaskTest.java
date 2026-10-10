package dev.everyagent.plugin.api.shell;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link ExecResults#maskScriptPath} 的掩蔽规则单测（ISSUES 四.9：codex 脚本文件承载
 * 的临时路径不得泄漏进模型可见输出;行号与正文保留）。
 */
class ExecResultsScriptMaskTest {

    private static final String ABS = "C:\\Users\\haigui\\.yu\\s23d84dsgl920dfbldf932gd\\eagent"
            + "\\.everyagent\\tmp\\ea-cmd-10472-53f65234ee4.ps1";

    @Test
    void 用户实报形态_绝对路径掩蔽且行号保留() {
        String err = "Get-ChildItem: " + ABS + ":3\r\nLine |\r\n"
                + "   3 |  Get-ChildItem \"C:\\definitely-not-exist-xyz\"\r\n"
                + "     |  找不到路径“C:\\definitely-not-exist-xyz”，因为该路径不存在";
        assertEquals("Get-ChildItem: <script>:3\r\nLine |\r\n"
                        + "   3 |  Get-ChildItem \"C:\\definitely-not-exist-xyz\"\r\n"
                        + "     |  找不到路径“C:\\definitely-not-exist-xyz”，因为该路径不存在",
                ExecResults.maskScriptPath(err, ABS,
                        ".everyagent\\tmp\\ea-cmd-10472-53f65234ee4.ps1",
                        "ea-cmd-10472-53f65234ee4.ps1"));
    }

    @Test
    void 相对路径与裸文件名形态也掩蔽() {
        assertEquals("At <script>:2 char:1",
                ExecResults.maskScriptPath("At .everyagent\\tmp\\ea-cmd-1-ab.ps1:2 char:1",
                        ".everyagent\\tmp\\ea-cmd-1-ab.ps1", "ea-cmd-1-ab.ps1"));
        assertEquals("读取 <script> 失败",
                ExecResults.maskScriptPath("读取 ea-cmd-1-ab.ps1 失败", "ea-cmd-1-ab.ps1"));
    }

    @Test
    void 大小写不敏感_盘符形态不一致也能命中() {
        assertEquals("err <script>:1",
                ExecResults.maskScriptPath("err c:\\WS\\.everyagent\\tmp\\ea-cmd-9-x.ps1:1",
                        "C:\\ws\\.everyagent\\tmp\\ea-cmd-9-x.ps1"));
    }

    @Test
    void 其它脚同名文件不误伤_空形态跳过() {
        // 只掩蔽本次形态;并列出别的 ea-cmd 脚本属合法输出,原样保留
        assertEquals("ea-cmd-777-beef.ps1",
                ExecResults.maskScriptPath("ea-cmd-777-beef.ps1", "ea-cmd-10472-53f65234ee4.ps1"));
        assertEquals("正文", ExecResults.maskScriptPath("正文", (String[]) null));
        assertEquals("正文", ExecResults.maskScriptPath("正文", "", null));
        assertEquals("", ExecResults.maskScriptPath(null, "x.ps1"));
    }
}
