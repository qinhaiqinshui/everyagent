package dev.everyagent.plugin.aireview;

import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import tools.jackson.databind.JsonNode;

/**
 * AI 审议 token 的提交解析器(仿 {@code modelpool.ModelPoolSlashResolver})。
 *
 * <p>kind={@code ai.review};本 token 仅是 AI 审议的触发标记(bottom 任务级开关),
 * 不承载任何需 AI 理解的内容,故 {@link #resolveSubmissionText} 返回空串——
 * 提交前残留的 token 被清空、不注入模型上下文(与模型池/自动同步解析器一致);
 * 真正的授权分派按任务级 {@code ai.review} slash token 判定
 * (由 {@code AiReviewAuthHandler} 经 {@code WorkerServices.slashTokens().hasKind(...)} 完成)。
 */
public class AiReviewSlashResolver implements SlashTokenResolver {

    @Override
    public String kind() {
        return AiReviewToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        return "";
    }
}
