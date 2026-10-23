package dev.everyagent.worker.tools.permission;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PathSupport#accessDeniedHint} 文案单测(§7.8 可读化):
 * 裸路径 message → 带原因与排查方向的可读错误。
 */
class PathSupportAccessDeniedHintTest {

    @Test
    void hintCarriesPathAndReason() {
        String hint = PathSupport.accessDeniedHint(Path.of("C:", "some", "path"));
        assertTrue(hint.contains("C:"), "文案必须携带原路径: " + hint);
        assertTrue(hint.contains("安全软件拦截"), "文案必须给出常见原因: " + hint);
        assertTrue(hint.contains("ACL"), "文案必须指向 ACL 损坏可能: " + hint);
    }

    @Test
    void commandFlavorPrefixesExecuteContext() {
        String hint = PathSupport.accessDeniedHint((String) null);
        assertTrue(hint.startsWith("授权检查失败"), "命令链形态带上下文前缀: " + hint);
        assertTrue(hint.contains("安全软件拦截"), "null file 退化为通用文案而非异常: " + hint);
        String withFile = PathSupport.accessDeniedHint("C:\\some\\dir");
        assertTrue(withFile.startsWith("授权检查失败") && withFile.contains("some"),
                "带 file 形态携带路径: " + withFile);
    }
}
