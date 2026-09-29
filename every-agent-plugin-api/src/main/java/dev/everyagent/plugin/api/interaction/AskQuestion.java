package dev.everyagent.plugin.api.interaction;

import java.util.List;

/**
 * 交互选择题。id 由 worker 在 ask() 时以真实 askId 派生(askId_i)。
 */
public record AskQuestion(String id, String prompt, List<AskOption> options) {
}
