package dev.everyagent.worker.tools;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.modules.Sandbox;
import dev.everyagent.worker.modules.WorkspaceManager.Root;
import dev.everyagent.worker.rpc.SandboxViolationException;
import dev.everyagent.worker.tools.permission.CommandCheck;
import dev.everyagent.worker.tools.permission.GrantScope;
import dev.everyagent.worker.tools.permission.GrantRegistry;
import dev.everyagent.worker.tools.permission.OverBroadRootCheck;
import dev.everyagent.worker.tools.permission.PermissionContext;
import dev.everyagent.worker.tools.permission.SkillsReadAllowCheck;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 权限守卫静态判定面单测(责住链节点:GrantRegistry.parseScope / CommandCheck 命令扫描 /
 * OverBroadRootCheck 宽泛根谓词 / PermissionGate 提权静态面 / Sandbox 授权附加根)。
 * 阻塞式弹窗授权与两档生效范围的原集成测试依赖过时 task.sync 协议,已删除(待迁移 task.poll)。
 */
class PermissionGateTest {

    // ---- parseScope:稳定 token 优先,文案关键词兜底,未识别=拒绝(安全缺省) ----

    @Test
    void parseScopeTokensAndKeywords() {
        assertEquals(GrantScope.RUN, GrantRegistry.parseScope("run"));
        assertEquals(GrantScope.TASK, GrantRegistry.parseScope("task"));
        assertEquals(GrantScope.RUN, GrantRegistry.parseScope("本轮运行内允许"));
        assertEquals(GrantScope.TASK, GrantRegistry.parseScope("本任务全程允许"));
        assertEquals(GrantScope.RUN, GrantRegistry.parseScope(" RUN "));
    }

    @Test
    void parseScopeUnrecognizedIsNullOrEmptyIsDeny() {
        assertEquals(GrantScope.DENY, GrantRegistry.parseScope("deny"));
        assertEquals(GrantScope.DENY, GrantRegistry.parseScope("随便答的"));
        assertEquals(GrantScope.DENY, GrantRegistry.parseScope(""));
        assertEquals(GrantScope.DENY, GrantRegistry.parseScope(null));
        // 前端异常路径兜底:未带选项提交 → 空 token
        assertEquals(GrantScope.DENY, GrantRegistry.parseScope("undefined"));
    }

    // ---- extractPathCandidates:引号段(含空格路径)+ 绝对/UNC/../ 相对 token ----

    @Test
    void extractPathCandidatesAbsoluteQuotedAndDotDot() {
        List<String> c1 = CommandCheck.extractPathCandidates("type C:\\docs\\a.txt | findstr x");
        assertTrue(c1.contains("C:\\docs\\a.txt"), c1.toString());

        // 引号内含空格路径必须整段提取(不能按空白截断)
        List<String> c2 = CommandCheck.extractPathCandidates("del \"C:/my docs/x.txt\"");
        assertTrue(c2.contains("C:/my docs/x.txt"), c2.toString());

        List<String> c3 = CommandCheck.extractPathCandidates("cd ..\\..\\escape");
        assertTrue(c3.stream().anyMatch(s -> s.contains("..\\..\\escape")), c3.toString());

        List<String> c4 = CommandCheck.extractPathCandidates("grep foo /etc/hosts");
        assertTrue(c4.contains("/etc/hosts"), c4.toString());

        List<String> c5 = CommandCheck.extractPathCandidates("git commit -m \"清理代码\"");
        assertTrue(c5.isEmpty(), "无路径特征不产出候选: " + c5);

        // 普通裸文件名(无分隔符)不是候选(del foo.txt 不触发路径扫描)
        List<String> c6 = CommandCheck.extractPathCandidates("del foo.txt");
        assertTrue(c6.isEmpty(), c6.toString());
    }

    // ---- §13.2 修复 A:转义感知引号切分 / 终止符 ;, / 退化 token 拒收 ----

    /** G1:事故原命令(2026-08-31 实锤)——转义引号串不得切出裸 \,工作区路径不得带 ; 尾巴。 */
    @Test
    void incidentCommandYieldsNoDegenerateCandidate(@TempDir Path ws) throws IOException {
        Path realWs = ws.toRealPath();
        String cmd = "cd " + realWs + "; rg -n \"\\\"changes\\\"|fileName|filePath\" a b 2>&1 | Select-Object -First 80";
        List<String> c = CommandCheck.extractPathCandidates(cmd);

        // 不再产出裸 \\(转义切分伪候选,曾坍缩成盘根弹 p::exec::C:\)
        assertFalse(c.stream().anyMatch(s -> s.matches("[\\\\/ .:]+")), "不得含退化 token: " + c);
        // 折叠后含引号/管道的整段 rg 正则串不是路径
        assertFalse(c.stream().anyMatch(s -> s.contains("changes")), "正则串不是路径: " + c);
        // 工作区路径以 ; 为终止符完整提取(曾带 ; 尾巴导致 exists=false 静默丢弃)
        assertTrue(c.contains(realWs.toString()), "工作区路径完整提取: " + c);
        // 全部候选均不越界 → touchesOutside=false → 危险动词放行、② 无弹窗输入
        Root root = new Root(realWs, realWs);
        assertFalse(CommandCheck.referencesOutsideWorkspace(root, c), "工作区内命令零授权: " + c);
    }

    /** G2:引号内工作区相对路径正常提取且不算越界。 */
    @Test
    void quotedRelativePathInsideWorkspaceIsNotOutside(@TempDir Path ws) throws IOException {
        List<String> c = CommandCheck.extractPathCandidates("rg -n \"foo\\bar\" .");
        assertTrue(c.contains("foo\\bar"), c.toString());
        Root root = new Root(ws, ws.toRealPath());
        assertFalse(CommandCheck.referencesOutsideWorkspace(root, c));
    }

    /** 转义折叠:双引号串内 \" 与 "" 与 `"(PowerShell)是字面引号,不得切断引号段。 */
    @Test
    void scanQuotedFoldsEscapesInsteadOfSplitting() {
        CommandCheck.QuotedScan q1 = CommandCheck.scanQuoted("echo \"a\\\"b|c\" rest");
        assertEquals(List.of("a\"b|c"), q1.segments());
        CommandCheck.QuotedScan q2 = CommandCheck.scanQuoted("echo \"a\"\"b\" rest");
        assertEquals(List.of("a\"b"), q2.segments());
        CommandCheck.QuotedScan q3 = CommandCheck.scanQuoted("echo \"a`\"b\" rest");
        assertEquals(List.of("a\"b"), q3.segments());
        // 剥除文本不再把引号内残留物暴露给动词扫描(引号内容是字面量,不可能是命令)
        CommandCheck.QuotedScan q4 = CommandCheck.scanQuoted("echo \"rm -rf x\" ; git status");
        assertEquals("echo   ; git status", q4.stripped());
        // 未闭合引号(cmd 中 ' 非引号,后半仍会执行):内容回填 stripped,危险动词保守可见
        CommandCheck.QuotedScan q5 = CommandCheck.scanQuoted("echo it's fine && rm -rf build");
        assertTrue(q5.stripped().contains("rm -rf"), q5.stripped());
    }

    /** 退化 token 拒收:仅由 \ / . : 与空白组成的不算路径。 */
    @Test
    void degenerateTokensAreNotPaths() {
        assertFalse(CommandCheck.looksLikePath("\\"), "裸 \\");
        assertFalse(CommandCheck.looksLikePath("/"));
        assertFalse(CommandCheck.looksLikePath("\\\\"));
        assertFalse(CommandCheck.looksLikePath("\\."));
        assertFalse(CommandCheck.looksLikePath(". . : /"), "分隔符点冒号空白混排");
        assertTrue(CommandCheck.looksLikePath("C:\\"), "盘根仍算路径(交 L1 谓词拒收)");
        assertTrue(CommandCheck.looksLikePath("a/b"));
        assertFalse(CommandCheck.looksLikePath("foo.txt"), "无分隔符不是候选(既有语义)");
    }

    /** G6:引号内 .. 逃逸仍提取(现状保留);G4:盘根候选仍提取(交 L1 拒收)。 */
    @Test
    void dotDotAndDriveRootStillExtracted() {
        List<String> c1 = CommandCheck.extractPathCandidates("del \"..\\..\\x\"");
        assertTrue(c1.contains("..\\..\\x"), c1.toString());
        List<String> c2 = CommandCheck.extractPathCandidates("dir C:\\");
        assertTrue(c2.contains("C:\\"), c2.toString());
    }

    // ---- §13.3 修复 B:isOverBroadRoot 谓词(只拒盘根/工作区祖先;§13.8 放开后系统目录可授权) ----

    @Test
    void overBroadRootPredicateMatrix(@TempDir Path ws) throws IOException {
        Path wsReal = ws.toRealPath();

        // ① 文件系统根(从临时目录推导真实根,Windows C:\ / Unix / 均可跑)
        assertTrue(OverBroadRootCheck.isOverBroadRoot(wsReal.getRoot(), wsReal, wsReal),
                "文件系统根");
        // ② 工作区自身 / 祖先
        assertTrue(OverBroadRootCheck.isOverBroadRoot(wsReal, wsReal, wsReal),
                "工作区自身");
        assertTrue(OverBroadRootCheck.isOverBroadRoot(wsReal.getParent(), wsReal, wsReal),
                "工作区父目录(P1 事故形态)");
        // 工作区兄弟目录(真实越界授权场景,G3)不拒
        Path sibling = wsReal.resolveSibling("sib-" + System.nanoTime());
        Files.createDirectories(sibling);
        assertFalse(OverBroadRootCheck.isOverBroadRoot(sibling, wsReal, wsReal),
                "工作区外兄弟目录照常授权");
        // 参数空安全(消费层缺上下文退化)
        assertFalse(OverBroadRootCheck.isOverBroadRoot(null, null, null));
        assertFalse(OverBroadRootCheck.isOverBroadRoot(sibling, null, null),
                "无上下文时不误伤");
        // §13.8 放开:系统目录/home 不再视为「过度宽泛根」,与普通工作区外目录同权走授权链
        Path homeDir = wsReal.resolveSibling("home-" + System.nanoTime());
        Files.createDirectories(homeDir);
        assertFalse(OverBroadRootCheck.isOverBroadRoot(homeDir, wsReal, wsReal),
                "系统目录可授权(不再拒收)");
    }

    // ---- §13.8:SkillsReadAllowCheck——读取 skills 目录内容直接放行 ----

    @Test
    void skillsReadAllowCheckAllowsReadOnlyInsideSkillsDir(@TempDir Path skills, @TempDir Path outside)
            throws IOException {
        Path skillFile = skills.resolve("agent-dispatch.md");
        Files.writeString(skillFile, "x");
        WorkerProperties props = new WorkerProperties();
        props.setSkillsDir(skills.toString());
        SkillsReadAllowCheck check = new SkillsReadAllowCheck(props);

        // ① skills 目录内 READ → ALLOW
        PermissionContext readCtx = PermissionContext.builder()
                .kind(PermissionContext.Kind.PATH)
                .op(PermissionGate.Op.READ)
                .realPath(skillFile.toRealPath())
                .build();
        assertTrue(check.check(readCtx).isAllow(), "skills 目录读取直接放行");

        // ② skills 目录内 WRITE → SKIP(不放行,交授权决议链)
        PermissionContext writeCtx = PermissionContext.builder()
                .kind(PermissionContext.Kind.PATH)
                .op(PermissionGate.Op.WRITE)
                .realPath(skillFile.toRealPath())
                .build();
        assertTrue(check.check(writeCtx).isSkip(), "skills 目录写不在此放行");

        // ③ skills 目录外 READ → SKIP(交授权决议链)
        Path outsideFile = outside.resolve("x.txt");
        Files.writeString(outsideFile, "x");
        PermissionContext outsideCtx = PermissionContext.builder()
                .kind(PermissionContext.Kind.PATH)
                .op(PermissionGate.Op.READ)
                .realPath(outsideFile.toRealPath())
                .build();
        assertTrue(check.check(outsideCtx).isSkip(), "skills 目录外读取不在此放行");

        // ④ skills 目录尚未物化(不存在)→ SKIP(不误放行)
        WorkerProperties props2 = new WorkerProperties();
        props2.setSkillsDir(outside.resolve("no-such-skills").toString());
        SkillsReadAllowCheck check2 = new SkillsReadAllowCheck(props2);
        assertTrue(check2.check(readCtx).isSkip(), "skills 目录未物化不误放行");
    }

    // ---- 危险动词默认清单:命中/不命中 ----

    private static CommandCheck cmdCheckWithDefaults() {
        // 静态判定面不触 workspaces/grants,构造传 null 即可
        return new CommandCheck(new WorkerProperties(), null, null);
    }

    private static boolean hits(String command) {
        String stripped = command.replaceAll("\"([^\"]*)\"|'([^']*)'", " ");
        for (Pattern p : cmdCheckWithDefaults().dangerousPatterns()) {
            if (p.matcher(stripped).find()) {
                return true;
            }
        }
        return false;
    }

    // ---- referencesOutsideWorkspace:工作区内/外判定(危险动词是否需授权的依据) ----

    @Test
    void referencesOutsideWorkspaceInWsIsFalseOutsideIsTrue(@TempDir Path ws, @TempDir Path outside)
            throws IOException {
        Root root = new Root(ws, ws.toRealPath());
        Path inFile = ws.resolve("a.txt");
        Files.writeString(inFile, "x");
        Path outFile = outside.resolve("b.txt");
        Files.writeString(outFile, "x");
        String inAbs = inFile.toRealPath().toString();
        String outAbs = outFile.toRealPath().toString();

        // 工作区内绝对路径 → false(危险动词放行)
        assertFalse(CommandCheck.referencesOutsideWorkspace(root,
                List.of(inAbs)), "工作区内已存在目标不算越界");
        // 工作区外已存在 → true(危险动词须授权)
        assertTrue(CommandCheck.referencesOutsideWorkspace(root,
                List.of(outAbs)), "工作区外已存在目标算越界");
        // 相对 .. 逃逸(词法已在工作区外,即使不存在)→ true
        assertTrue(CommandCheck.referencesOutsideWorkspace(root,
                List.of("..\\escape\\x.txt")), "相对 .. 逃逸算越界");
        // 工作区内相对路径(不存在)→ false
        assertFalse(CommandCheck.referencesOutsideWorkspace(root,
                List.of("sub\\new.txt")), "工作区内相对路径不算越界");
    }

    @Test
    void referencesHomeDirVarsTriggersOnEnvMarkers() {
        assertTrue(CommandCheck.referencesHomeDirVars("Remove-Item $env:TEMP\\x -Recurse -Force"));
        assertTrue(CommandCheck.referencesHomeDirVars("del %USERPROFILE%\\x"));
        assertTrue(CommandCheck.referencesHomeDirVars("rm -rf $HOME/data"));
        assertFalse(CommandCheck.referencesHomeDirVars("del .\\build\\x.txt"));
        assertFalse(CommandCheck.referencesHomeDirVars("git status"));
    }

    @Test
    void dangerousVerbsHit() {
        assertTrue(hits("del x.txt"), "cmd del");
        assertTrue(hits("DEL /q x.txt"), "大小写不敏感");
        assertTrue(hits("rmdir /s /q build"), "rmdir");
        assertTrue(hits("Remove-Item -Recurse -Force x"), "PowerShell Remove-Item");
        assertTrue(hits("rm -rf /tmp/x"), "POSIX rm");
        assertTrue(hits("shred secret.txt"), "shred");
        assertTrue(hits("echo hi && rm -rf build"), "复合命令仍命中");
    }

    @Test
    void dangerousVerbsDoNotHitFalsePositives() {
        assertFalse(hits("echo delete something"), "delete 单词含 del 但 \\b 不命中");
        assertFalse(hits("git log --format=%H -5"), "format 需后跟卷参数");
        assertFalse(hits("git commit -m \"rm 很危险\""), "引号内文案不参与动词扫描");
        assertFalse(hits("git status"), "普通命令");
        assertFalse(hits("mvn -q compile"), "构建命令");
        assertFalse(hits("formatters.js run"), "format 单词边界");
    }

    // ---- Sandbox 授权附加根 ----

    @Test
    void sandboxExtraRootsAllowOutsideAccess(@TempDir Path ws, @TempDir Path extra) throws IOException {
        Path file = extra.resolve("data.txt");
        Files.writeString(file, "outside");
        Path extraReal = extra.toRealPath();
        Root root = new Root(ws, ws.toRealPath());
        // 入参与附加根同形态(realpath),对应授权后重试的解析路径
        Path fileReal = extraReal.resolve("data.txt");

        // 无附加根:工作区外(词法即外)拒绝
        Sandbox bare = new Sandbox(root);
        assertThrows(SandboxViolationException.class, () -> bare.resolveExisting(fileReal.toString()),
                "无授权根时越界拒绝");

        // 附加根(absolute 形式)放行,返回 realpath
        Sandbox granted = new Sandbox(root, List.of(extraReal));
        Path resolved = granted.resolveExisting(fileReal.toString());
        assertEquals(file.toRealPath(), resolved);

        // 授权根本身不可删/移,其内文件可以解析
        assertThrows(SandboxViolationException.class, () -> granted.requireNotRoot(extraReal));
        granted.requireNotRoot(resolved); // 文件不受根保护限制

        // display:工作区外返回绝对路径(/ 分隔)
        String display = granted.display(resolved);
        assertTrue(display.contains("/") && display.contains("data.txt"), display);
    }

    @Test
    void sandboxExtraRootRejectsSiblingOfGrantedRoot(@TempDir Path ws, @TempDir Path extra)
            throws IOException {
        // 授权 extra,但访问 extra 的兄弟目录(同为工作区外)仍拒绝
        Path sibling = extra.resolveSibling("sibling-" + System.nanoTime());
        Files.createDirectories(sibling);
        Path f = sibling.resolve("x.txt");
        Files.writeString(f, "x");
        Sandbox granted = new Sandbox(new Root(ws, ws.toRealPath()), List.of(extra.toRealPath()));
        assertThrows(SandboxViolationException.class, () -> granted.resolveExisting(f.toString()),
                "附加根外的路径仍越界");
    }

    // ---- usesPrivilege:提权动词词边界检测(默认拒、按命令弹窗授权) ----

    @Test
    void usesPrivilegeDetectsElevationVerbs() {
        assertTrue(PermissionGate.usesPrivilege("sudo apt install curl"));
        assertTrue(PermissionGate.usesPrivilege("sudo -u root whoami"));
        assertTrue(PermissionGate.usesPrivilege("su -c 'ls'"));
        assertTrue(PermissionGate.usesPrivilege("doas pacman -Syu"));
        assertTrue(PermissionGate.usesPrivilege("pkexec nano /etc/hosts"));
        assertTrue(PermissionGate.usesPrivilege("runas /user:admin cmd"));
        assertTrue(PermissionGate.usesPrivilege("gsudo notepad"));
        // 大小写不敏感
        assertTrue(PermissionGate.usesPrivilege("SUDO whoami"));
        assertTrue(PermissionGate.usesPrivilege("Sudo -V"));
    }

    @Test
    void usesPrivilegeRejectsFalsePositivesAndPlainCommands() {
        assertFalse(PermissionGate.usesPrivilege("lsusb"));        // su 在词中间
        assertFalse(PermissionGate.usesPrivilege("sudoers edit")); // "sudo"+"ers" 无词边界,不命中 sudo
        assertFalse(PermissionGate.usesPrivilege("echo hello"));
        assertFalse(PermissionGate.usesPrivilege("git status"));
        assertFalse(PermissionGate.usesPrivilege(""));
        assertFalse(PermissionGate.usesPrivilege(null));
    }

    // ---- baseName:seccomp 提权授权从 exec 路径提取 basename 作为 grant key ----

    @Test
    void baseNameExtractsProgramNameFromPath() {
        assertEquals("sudo", PermissionGate.baseName("/usr/bin/sudo"));
        assertEquals("su", PermissionGate.baseName("/usr/bin/su"));
        assertEquals("sudo.exe", PermissionGate.baseName("C:\\Windows\\System32\\sudo.exe"));
        assertEquals("doas", PermissionGate.baseName("doas"));
        assertEquals("", PermissionGate.baseName(""));
        assertEquals("", PermissionGate.baseName(null));
        assertEquals("", PermissionGate.baseName("   "));
    }
}