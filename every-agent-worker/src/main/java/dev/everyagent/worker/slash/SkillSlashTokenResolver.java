package dev.everyagent.worker.slash;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * skill 输入 token 的提交解析器(老项目 {@code skillInputToken.ts} 的
 * {@code skillInputTokenResolver} 的 worker 侧等价物)。
 *
 * <p>kind={@code system.skill};提交给 AI 的是技能名
 * ({@code payload.text ?? payload.skillId}),与老项目
 * {@code resolveSubmissionText: (payload) => String(payload.text ?? payload.skillId ?? '')}
 * 一致。注册进 {@link SlashTokenHandler} 后由 advisor 层统一分发,核心零业务分支。
 */
@Component
public class SkillSlashTokenResolver implements SlashTokenHandler.SlashTokenResolver {

    @Override
    public String kind() {
        return SkillSlashProvider.SKILL_INPUT_TOKEN_KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        String text = payload.path("text").asText("");
        if (text != null && !text.isEmpty()) {
            return "请使用技能："+text+"。";
        }
        return payload.path("skillId").asText("");
    }
}
