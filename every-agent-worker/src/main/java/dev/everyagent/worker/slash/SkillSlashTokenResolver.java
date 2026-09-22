package dev.everyagent.worker.slash;

import org.springframework.stereotype.Component;

import tools.jackson.databind.JsonNode;

/**
 * skill 输入 token 的提交解析器(老项目 {@code skillInputToken.ts} 的
 * {@code skillInputTokenResolver} 的 worker 侧等价物)。
 *
 * <p>kind={@code system.skill};提交给 AI 的是技能名 + 知识包路径
 * ({@code payload.text ?? payload.skillId} + {@code payload.skillPath})。
 * 主动 skill 的路径已在 system prompt 中,重复注入无碍;被动/外部 skill 不在 prompt 中,
 * 路径是 AI 唯一能 read_file 知识包的来源——故始终注入。
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
            StringBuilder sb = new StringBuilder("请使用技能：").append(text).append("。");
            String skillPath = payload.path("skillPath").asText("");
            if (!skillPath.isEmpty()) {
                sb.append(" 知识包路径：").append(skillPath);
            }
            return sb.toString();
        }
        return payload.path("skillId").asText("");
    }
}
