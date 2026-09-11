package dev.everyagent.worker.powershell;

import org.springframework.stereotype.Component;

import dev.everyagent.worker.slash.SlashTokenHandler;
import tools.jackson.databind.JsonNode;

/**
 * 启用 powershell token 的提交解析器(仿 {@code network.NetworkSlashResolver})。
 *
 * <p>kind={@code powershell.enable};本 token 仅是任务级 bottom 开关的触发标记,
 * 不承载任何需 AI 理解的内容,故 {@link #resolveSubmissionText} 返回空串——
 * 提交前残留的 token 被清空、不注入模型上下文;真正的「注册 powershell 工具」由
 * {@code TaskManager}/{@code SubAgentManager} 按任务级 {@code TaskEntry.powershellEnabled}
 * 在构建 agent 工具集时生效(WSL 后端 bash 之外追加 PowerShellTool)。
 */
@Component
public class PowerShellEnableSlashResolver implements SlashTokenHandler.SlashTokenResolver {

    @Override
    public String kind() {
        return PowerShellEnableToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        return "";
    }
}
