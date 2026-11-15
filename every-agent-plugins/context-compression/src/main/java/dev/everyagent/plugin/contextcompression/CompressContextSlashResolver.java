package dev.everyagent.plugin.contextcompression;

import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import tools.jackson.databind.JsonNode;

/**
 * 压缩上下文 token 的提交解析器(仿 {@code UnattendedSlashResolver})。
 *
 * <p>kind={@code system.compress_context};本 token 仅是手动压缩的触发标记
 * (bottom 任务级一次性请求),不承载任何需 AI 理解的内容,故
 * {@link #resolveSubmissionText} 返回空串——提交前残留的 token 被清空、
 * 不注入模型上下文。真正的压缩动作由 {@link ContextCompressionAdvisor}
 * 在 advisor 链内按任务级 token 查询判定完成。
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
