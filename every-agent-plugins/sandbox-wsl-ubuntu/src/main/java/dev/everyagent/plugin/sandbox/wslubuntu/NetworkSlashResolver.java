package dev.everyagent.plugin.sandbox.wslubuntu;

import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import tools.jackson.databind.JsonNode;

/**
 * 网络 token 的提交解析器(仿 {@code unattended.UnattendedSlashResolver})。
 *
 * <p>kind={@code network.access};本 token 仅是网络的任务级触发标记(bottom 任务级开关,
 * 语义为「禁用本任务网络」),不承载任何需 AI 理解的内容,故 {@link #resolveSubmissionText}
 * 返回空串——提交前残留的 token 被清空、不注入模型上下文(与模型池/无人值守解析器一致);
 * 真正的禁网由 {@link WslUbuntuCommandExecutor} 按任务级
 * {@code metadata[networkBlocked]} 在发行版内 {@code unshare -n} 执行。
 */
public class NetworkSlashResolver implements SlashTokenResolver {

    @Override
    public String kind() {
        return NetworkToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        return "";
    }
}
