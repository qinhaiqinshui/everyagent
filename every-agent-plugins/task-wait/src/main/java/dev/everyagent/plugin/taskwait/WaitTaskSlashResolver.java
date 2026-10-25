package dev.everyagent.plugin.taskwait;

import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import tools.jackson.databind.JsonNode;

/**
 * 等待任务 token 的提交解析器（仿 {@code unattended.UnattendedSlashResolver}）。
 *
 * <p>kind={@code task.wait}；本 token 仅是等待行为的触发标记（绑定登记 +
 * advisor 阻塞等待），不承载任何需 AI 理解的内容，故
 * {@link #resolveSubmissionText} 返回空串——提交前残留的 token 被清空、
 * 不注入模型上下文。等待执行由 {@link WaitTaskAdvisor} 按
 * {@link WaitRegistry} 的内存绑定完成，与模型上下文无关。
 */
public class WaitTaskSlashResolver implements SlashTokenResolver {

    @Override
    public String kind() {
        return WaitTaskToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        return "";
    }
}
