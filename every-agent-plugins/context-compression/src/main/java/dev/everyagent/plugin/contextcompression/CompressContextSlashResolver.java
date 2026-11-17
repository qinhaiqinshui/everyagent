package dev.everyagent.plugin.contextcompression;

import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import tools.jackson.databind.JsonNode;

/**
 * 压缩上下文 token 的提交解析器(仿 task-wait 的 {@code WaitTaskSlashResolver})。
 *
 * <p>kind={@code system.compress_context};本 token 仅是手动压缩的触发标记
 * (输入框内联一次性胶囊),不承载任何需 AI 理解的内容,故
 * {@link #resolveSubmissionText} 返回空串——token 从 AI 上下文剥离、不注入模型。
 * 请求登记由 {@link CompressContextRequestNode} 扫 rawContent 完成,真正的压缩动作由
 * {@link ContextCompressionAdvisor} 在 advisor 链内消费请求完成。
 */
public class CompressContextSlashResolver implements SlashTokenResolver {

    @Override
    public String kind() {
        return CompressContextToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        return "";
    }
}
