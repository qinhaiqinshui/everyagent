package dev.everyagent.worker.authreview;

import org.springframework.stereotype.Component;

import dev.everyagent.worker.slash.SlashTokenHandler;
import tools.jackson.databind.JsonNode;

/**
 * 无人值守 token 的提交解析器(仿 {@code modelpool.ModelPoolSlashResolver})。
 *
 * <p>kind={@code unattended.mode};本 token 仅是无人值守的触发标记(bottom 任务级开关),
 * 不承载任何需 AI 理解的内容,故 {@link #resolveSubmissionText} 返回空串——
 * 提交前残留的 token 被清空、不注入模型上下文(与模型池/自动同步解析器一致);
 * 真正的 ask_user 拦截(代替人工逐题选第一个选项)由
 * {@code UnattendedAskUserCallback} 装饰器在工具执行瞬间按任务级
 * {@code TaskEntry.unattended} 完成。
 */
@Component
public class UnattendedSlashResolver implements SlashTokenHandler.SlashTokenResolver {

    @Override
    public String kind() {
        return UnattendedToken.KIND;
    }

    @Override
    public String resolveSubmissionText(JsonNode payload) {
        return "";
    }
}
