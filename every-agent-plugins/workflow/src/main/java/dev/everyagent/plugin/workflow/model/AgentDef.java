package dev.everyagent.plugin.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 命名 agent 声明：一个命名 agent = 实例内一条持续会话线
 * （物理载体 instances/&lt;wi&gt;/agents/&lt;name&gt;.jsonl）。
 *
 * <p>AI 节点经 {@code agent} 字段引用；<b>引用即声明</b>——未显式声明的引用名在保存时
 * 自动合并进 agents[]（补默认模型 null）。名字不可撞保留名（supervisor 督导专用；
 * meta/events/runs/agents 因收进 agents/ 子目录已无文件冲突，仍保留禁用）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AgentDef(
        String name,
        String modelConfigId,
        String systemPrompt) {
}
