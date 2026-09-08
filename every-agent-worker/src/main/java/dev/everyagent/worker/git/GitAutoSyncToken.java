package dev.everyagent.worker.git;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.slash.SlashTokenEncoder;

/**
 * 「/自动同步」斜杠能力 capsule token 的 worker 侧实现(对齐 old
 * {@code plugins/git/gitAutoSyncToken.ts})。
 *
 * <p>职责：
 * <ul>
 *   <li>固定 {@link #KIND} = {@code git.auto_sync},与老项目逐项一致;</li>
 *   <li>{@link #buildToken(boolean)} 构造自包含 opaque token(顶层 label/summary 明文,
 *       payload base64url),直接作为 {@code slash.list} 的 {@code insertText} 下发;</li>
 *   <li>{@link #enabledIn(String)} 扫描消息文本,命中本 kind 且 {@code enabled=true}
 *       即返回 true——供 {@code GitAutoSyncAdvisor} 在任务收口时判定本轮是否自动同步;</li>
 *   <li>{@link #buildCommitMessage(String)} 生成自动提交消息(带 taskId 便于追溯)。</li>
 * </ul>
 *
 * <p>提交解析(从 AI 上下文剥离 token)由 {@code SlashTokenResolveAdvisor} 接管:本 kind
 * 解析为 {@code ""},token 被清空、不注入模型上下文;真正提交+推送由
 * {@code GitAutoSyncAdvisor} 在本轮任务完成后执行(老项目由 unwind 节点
 * {@code git.auto_sync_on_complete} 执行,语义等价)。
 */
public final class GitAutoSyncToken {

    /** 自动同步 token 的固定 kind(提交解析 / 胶囊回显共用)。 */
    public static final String KIND = "git.auto_sync";

    private GitAutoSyncToken() {
    }

    /** 构造自动同步 opaque token(select 直接返回该串 → 输入框显示胶囊)。 */
    public static String buildToken(boolean enabled) {
        return SlashTokenEncoder.buildToken(
                KIND,
                enabled ? "自动同步" : "取消自动同步",
                enabled ? "本轮任务完成后自动提交并推送到远端" : "本轮任务完成后不自动同步",
                Json.obj().put("enabled", enabled));
    }

    /**
     * 判断文本是否包含「本轮自动同步」标记(命中 {@code git.auto_sync} 且 enabled=true)。
     * 逐段扫描 opaque token,非本 kind / 解析失败跳过;与渲染解耦。
     */
    public static boolean enabledIn(String text) {
        if (text == null || text.isEmpty()) {
            return false;
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
        return false;
    }

    /** 自动提交的固定消息(带任务标识便于追溯;无标识时仅用前缀)。 */
    public static String buildCommitMessage(String taskId) {
        return taskId != null && !taskId.isEmpty()
                ? "[auto-sync] 任务完成后自动同步 #" + taskId
                : "[auto-sync] 任务完成后自动同步";
    }
}
