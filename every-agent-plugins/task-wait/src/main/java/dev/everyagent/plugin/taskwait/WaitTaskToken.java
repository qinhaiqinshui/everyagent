package dev.everyagent.plugin.taskwait;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;

import java.util.ArrayList;
import java.util.List;

/**
 * 「/等待任务」胶囊 token 的 worker 侧实现（仿 {@code unattended.UnattendedToken}：
 * opaque token 三要素——固定 kind、构造、命中扫描）。
 *
 * <p>职责：
 * <ul>
 *   <li>固定 {@link #KIND} = {@code task.wait}（前后端逐字一致）；</li>
 *   <li>{@link #buildToken(String, String)} 构造自包含 opaque token（顶层
 *       label/summary 明文，payload base64url），payload 携带
 *       {@code targetTaskId} + {@code title}；</li>
 *   <li>{@link #scanWaits(String)} 扫描文本中全部 {@code [[[[…]]]]} 段，解析
 *       kind==task.wait 的项为 {@link WaitRef}（解析失败 / 非本 kind / 缺
 *       targetTaskId 一律跳过）。</li>
 * </ul>
 *
 * <p>本 token 是等待行为的触发标记，不承载任何需 AI 理解的内容；提交解析
 * （从 AI 上下文剥离）由 {@link WaitTaskSlashResolver} 接管（返回 {@code ""}）。
 * 真正的等待执行由 {@link WaitTaskAdvisor} 在首个模型调用前按
 * {@link WaitRegistry} 的内存绑定完成。
 */
public final class WaitTaskToken {

    /** 等待任务 token 的固定 kind（提交解析 / 胶囊回显 / trace 事件共用）。 */
    public static final String KIND = "task.wait";

    /** 扫描命中的等待引用（payload 的类型化视图）。 */
    public record WaitRef(String targetTaskId, String title) {
    }

    private WaitTaskToken() {
    }

    /**
     * 构造「等待［任务名］」胶囊的 opaque token（前端 select 直接返回该串 →
     * 内联渲染胶囊；点击胶囊由前端 chip action 打开目标任务标签）。
     */
    public static String buildToken(String targetTaskId, String targetTitle) {
        return SlashTokenEncoder.buildToken(
                KIND,
                "等待「" + targetTitle + "」",
                "等待该任务完成后继续（本次输入有效）",
                Json.obj().put("targetTaskId", targetTaskId).put("title", targetTitle));
    }

    /**
     * 扫描文本中全部等待胶囊引用（按出现顺序返回）。
     *
     * <p>逐段扫描 opaque token（{@code [[[[…]]]]}），只解析 kind==task.wait 的段；
     * 解析失败 / 非本 kind / payload 缺 targetTaskId 的段直接跳过——多胶囊时由
     * 调用方（{@link WaitBindNode}）取最后一个（最近意图胜）。
     */
    public static List<WaitRef> scanWaits(String rawContent) {
        List<WaitRef> refs = new ArrayList<>();
        if (rawContent == null || rawContent.isEmpty()) {
            return refs;
        }
        int idx = rawContent.indexOf(SlashTokenEncoder.OPAQUE_PREFIX);
        while (idx >= 0) {
            int end = rawContent.indexOf(SlashTokenEncoder.OPAQUE_SUFFIX, idx);
            if (end < 0) {
                break;
            }
            String opaque = rawContent.substring(idx, end + SlashTokenEncoder.OPAQUE_SUFFIX.length());
            SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(opaque);
            if (parsed != null && KIND.equals(parsed.kind())) {
                String targetTaskId = parsed.payload().path("targetTaskId").asString("");
                if (!targetTaskId.isEmpty()) {
                    refs.add(new WaitRef(targetTaskId, parsed.payload().path("title").asString("")));
                }
            }
            idx = rawContent.indexOf(SlashTokenEncoder.OPAQUE_PREFIX, end);
        }
        return refs;
    }
}
