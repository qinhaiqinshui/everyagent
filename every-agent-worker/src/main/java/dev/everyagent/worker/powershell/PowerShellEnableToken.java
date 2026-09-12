package dev.everyagent.worker.powershell;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.slash.SlashTokenEncoder;

/**
 * 「/启用powershell」斜杠能力 capsule token 的 worker 侧实现(仿
 * {@code network.NetworkToken}:opaque token 三要素——固定 kind、构造、命中扫描)。
 *
 * <p>职责:
 * <ul>
 *   <li>固定 {@link #KIND} = {@code powershell.enable};</li>
 *   <li>{@link #buildToken()} 构造自包含 opaque token(顶层 label/summary 明文,
 *       payload base64url),直接作为 {@code slash.list} 的 {@code insertText} 下发;</li>
 *   <li>{@link #enabledIn(String)} 扫描消息文本,命中本 kind 且 {@code enabled=true}
 *       即返回 true(与 NetworkToken 口径一致)。</li>
 * </ul>
 *
 * <p>本 token 是任务级 bottom 开关的触发标记,不承载任何需 AI 理解的内容;提交解析
 * (从 AI 上下文剥离)由 {@code PowerShellEnableSlashResolver} 接管:本 kind 解析为
 * {@code ""},token 被清空、不注入模型上下文。真正的「注册 powershell 工具」由
 * {@code TaskManager}/{@code SubAgentManager} 按任务级 {@code TaskEntry.powershellEnabled}
 * 在构建 agent 工具集时生效(WSL 后端在 bash 之外追加 PowerShellTool,命令回宿主
 * Windows 原生沙箱执行,AI 同时拥有 powershell 与 bash 两个命令工具)。
 *
 * <p>本次任务有效:选中 /启用powershell 即开启任务级开关(写入任务 meta.json 落盘),
 * 之后本任务所有轮次持续生效、再运行仍保持,直至 ✕ 取消。
 */
public final class PowerShellEnableToken {

    /** 启用 powershell token 的固定 kind(提交解析 / 胶囊回显共用)。 */
    public static final String KIND = "powershell.enable";

    private PowerShellEnableToken() {
    }

    /** 构造「启用powershell」opaque token(select 直接返回该串 → 底部渲染胶囊)。 */
    public static String buildToken() {
        return SlashTokenEncoder.buildToken(
                KIND,
                "启用powershell",
                "为 WSL 沙箱追加 powershell 工具（本任务有效）",
                Json.obj().put("enabled", true));
    }

    /**
     * 判断文本是否包含「启用powershell」标记(命中 {@code powershell.enable} 且 enabled=true)。
     * 逐段扫描 opaque token,非本 kind / 解析失败跳过;与渲染解耦。
     */
    public static boolean enabledIn(String text) {
        return Boolean.TRUE.equals(enabledOf(text));
    }

    /**
     * 扫描文本命中 {@code powershell.enable} token 时返回其 {@code enabled} 值;
     * 未命中(无 token / 非本 kind / 解析失败)返回 {@code null}。
     */
    public static Boolean enabledOf(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int idx = text.indexOf(SlashTokenEncoder.OPAQUE_PREFIX);
        while (idx >= 0) {
            int end = text.indexOf(SlashTokenEncoder.OPAQUE_SUFFIX, idx);
            if (end < 0) {
                break;
            }
            String opaque = text.substring(idx, end + SlashTokenEncoder.OPAQUE_SUFFIX.length());
            SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(opaque);
            if (parsed != null && KIND.equals(parsed.kind())) {
                return parsed.payload().path("enabled").asBoolean(false);
            }
            idx = text.indexOf(SlashTokenEncoder.OPAQUE_PREFIX, end);
        }
        return null;
    }
}
