package dev.everyagent.worker.authreview;

import org.springframework.stereotype.Component;

import dev.everyagent.worker.slash.SlashTokenHandler;
import tools.jackson.databind.JsonNode;

/**
 * AI 审议 token 的提交解析器(仿 {@code modelpool.ModelPoolSlashResolver})。
 *
 * <p>kind={@code ai.review};本 token 仅是 AI 审议的触发标记(bottom 任务级开关),
 * 不承载任何需 AI 理解的内容,故 {@link #resolveSubmissionText} 返回空串——
 * 提交前残留的 token 被清空、不注入模型上下文(与模型池/自动同步解析器一致);
 * 真正的授权分派由 {@code PermissionGate} 按任务级 {@code TaskEntry.aiReview} 执行(步骤 6)。
 */
@Component
public class AiReviewSlashResolver implements SlashTokenHandler.SlashTokenResolver {

    @Override
    public String kind() {
        return AiReviewToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        return "";
    }
}
