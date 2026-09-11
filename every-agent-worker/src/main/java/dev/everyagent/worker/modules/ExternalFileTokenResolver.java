package dev.everyagent.worker.modules;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.os.wsl.WslPathMapper;
import dev.everyagent.worker.rpc.BadParamsException;
import dev.everyagent.worker.slash.SlashTokenHandler;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.tools.permission.OverBroadRootCheck;
import tools.jackson.databind.JsonNode;

/**
 * `@` 弹窗外部文件引用 token(kind={@code system.external_file})的提交解析器
 * (架构 §7.16):引用指向任务工作区之外的文件/目录,前端不做提交解析、opaque 原串上行,
 * 由本 resolver 在用户消息进入模型前统一裁决并替换为 AI 可读文本。
 *
 * <p>规则(§7.16):
 * <ul>
 *   <li>payload 缺 absolutePath / 缺任务上下文 → 返回 null(handler 兜底保留原串,
 *       绝不误删半成品 token);</li>
 *   <li>realpath 不存在(已被移动/删除)→ 替换为失效提示文本;</li>
 *   <li>realpath 落在工作区内(词法/realpath 双形态判定,同 {@link Sandbox} 的 allowed
 *       双判定思路)→ 退化为「工作区相对路径」明文,与前端 {@code system.workspace_file}
 *       提交语义逐字一致;此分支不注册授权根;</li>
 *   <li>工作区外 → 授权根 = 目录自身/文件父目录,先经 {@link OverBroadRootCheck} 拒收
 *       过宽根,再 {@link WorkspaceManager#addExternalRoot} 幂等注册为该工作区外部授权根
 *       (§7.17),替换文本 = 原生绝对路径(realpath 形态)+ 按沙箱后端附「wsl 沙箱内」
 *       路径——模型既能用文件工具按原生绝对路径读,也能在 bash 里用沙箱内路径引用。</li>
 * </ul>
 */
@Component
public class ExternalFileTokenResolver implements SlashTokenHandler.SlashTokenResolver {

    /** 外部文件引用 token 的固定 kind(与前端 externalFileToken.ts 一致)。 */
    public static final String KIND = "system.external_file";

    private static final Logger log = LoggerFactory.getLogger(ExternalFileTokenResolver.class);

    private final WorkspaceManager workspaces;
    /** 只读后端判定(isWslDirect/isWslBwrap),决定是否附沙箱内路径后缀。 */
    private final OsSandbox osSandbox;

    public ExternalFileTokenResolver(WorkspaceManager workspaces, OsSandbox osSandbox) {
        this.workspaces = workspaces;
        this.osSandbox = osSandbox;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        return resolveSubmissionText(payload, null);
    }

    @Override
    public String resolveSubmissionText(JsonNode payload, TaskEntry task) {
        String absolutePath = payload.path("absolutePath").asString("");
        if (absolutePath.isBlank()) {
            return null; // payload 不完整:交 handler 保留原串
        }
        if (task == null || task.workspaceRoot == null || task.workspaceRoot.isBlank()) {
            return null; // 无任务上下文无法判定归属/注册:保留原串
        }
        String trimmed = absolutePath.trim();
        Path real;
        try {
            real = Path.of(trimmed).toRealPath();
        } catch (IOException e) {
            return "（外部引用已失效：" + absolutePath + "）";
        }
        WorkspaceManager.Root ws;
        try {
            ws = workspaces.resolve(task.workspaceRoot);
        } catch (IOException | RuntimeException e) {
            log.warn("外部文件引用解析失败(工作区不可解析): ws={} - {}", task.workspaceRoot, e.getMessage());
            return "（外部引用注册失败：" + absolutePath + "）";
        }
        // 工作区内(词法/realpath 双形态,防符号链接形态差):退化为工作区相对路径明文,不注册根。
        Path lex = Path.of(trimmed).toAbsolutePath().normalize();
        if (lex.startsWith(ws.path())) {
            return " " + relative(ws.path(), lex) + " ";
        }
        if (real.startsWith(ws.realPath())) {
            return " " + relative(ws.realPath(), real) + " ";
        }
        // 工作区外:授权根 = 目录自身/文件父目录;先拒过宽根(与 addExternalRoot 同一单点判定)。
        Path authRoot = Files.isDirectory(real) ? real : real.getParent();
        if (OverBroadRootCheck.isOverBroadRoot(authRoot, ws.path(), ws.realPath())) {
            return "（外部路径被拒：授权根过于宽泛 " + absolutePath + "）";
        }
        try {
            workspaces.addExternalRoot(task.workspaceRoot, trimmed); // 幂等(skipped/absorbed 均视为成功)
        } catch (BadParamsException | IOException e) {
            log.warn("外部授权根注册失败: ws={} path={} - {}", task.workspaceRoot, absolutePath, e.getMessage());
            return "（外部引用注册失败：" + absolutePath + "）";
        }
        return externalRefText(real, osSandbox.isWslDirect(), osSandbox.isWslBwrap());
    }

    /** 工作区相对路径(`/` 分隔、无前导 `/`,根自身为 `.`),与前端 system.workspace_file 逐字一致。 */
    private static String relative(Path root, Path p) {
        String rel = root.relativize(p).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }

    /**
     * 工作区外引用的替换文本(前后各一空格防粘连):{@code [外部引用] <原生绝对路径>}
     * (realpath 形态)+ wsl 系后端附沙箱内路径(wsl-direct=toDirectMount / wsl-bwrap=toWsl;
     * 映射不出(UNC 等)或其余后端不附)。纯函数,便于单测钉住文本形态。
     */
    static String externalRefText(Path real, boolean wslDirect, boolean wslBwrap) {
        String sandboxPath = wslDirect ? WslPathMapper.toDirectMount(real)
                : wslBwrap ? WslPathMapper.toWsl(real) : null;
        StringBuilder sb = new StringBuilder(" [外部引用] ").append(real);
        if (sandboxPath != null) {
            sb.append("（wsl 沙箱内: ").append(sandboxPath).append("）");
        }
        return sb.append(' ').toString();
    }
}
