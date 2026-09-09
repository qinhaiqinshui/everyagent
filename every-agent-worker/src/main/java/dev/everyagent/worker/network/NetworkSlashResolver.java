package dev.everyagent.worker.network;

import org.springframework.stereotype.Component;

import dev.everyagent.worker.slash.SlashTokenHandler;
import tools.jackson.databind.JsonNode;

/**
 * 网络 token 的提交解析器(仿 {@code modelpool.ModelPoolSlashResolver})。
 *
 * <p>kind={@code network.access};本 token 仅是网络的任务级触发标记(bottom 任务级开关,
 * 语义为「禁用本任务网络」),不承载任何需 AI 理解的内容,故 {@link #resolveSubmissionText}
 * 返回空串——提交前残留的 token 被清空、不注入模型上下文(与模型池/自动同步解析器一致);
 * 真正的「禁用网络」由 {@code CommandExecutor} 按任务级 {@code TaskEntry.networkBlocked}
 * 执行(沙箱三后端各自落地)。
 */
@Component
public class NetworkSlashResolver implements SlashTokenHandler.SlashTokenResolver {

    @Override
    public String kind() {
        return NetworkToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        return "";
    }
}
