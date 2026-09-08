package dev.everyagent.worker.git;

import org.springframework.stereotype.Component;

import dev.everyagent.worker.slash.SlashTokenHandler;
import tools.jackson.databind.JsonNode;

/**
 * 自动同步 token 的提交解析器(老项目 {@code gitAutoSyncToken.ts} 的
 * {@code gitAutoSyncTokenResolver} 的 worker 侧等价物)。
 *
 * <p>kind={@code git.auto_sync};本 token 仅是任务收口阶段的触发标记,
 * 不承载任何需 AI 理解的内容,故 {@link #resolveSubmissionText} 返回空串——
 * 提交前的 token 被清空、不注入模型上下文(与老项目 {@code resolveSubmissionText = ''} 一致);
 * 真正的 commit+push 由 {@code GitAutoSyncAdvisor} 在本轮任务完成后执行。
 */
@Component
public class GitAutoSyncSlashResolver implements SlashTokenHandler.SlashTokenResolver {

    @Override
    public String kind() {
        return GitAutoSyncToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        return "";
    }
}
