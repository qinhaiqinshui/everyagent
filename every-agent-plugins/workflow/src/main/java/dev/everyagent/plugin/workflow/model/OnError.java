package dev.everyagent.plugin.workflow.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 定义级错误策略（definition.onError；缺省 supervisor）。
 *
 * <ul>
 *   <li>{@code fail}：节点失败 → 实例失败；</li>
 *   <li>{@code supervisor}（默认）：主 agent 介入（督导会话线续跑，输出决策 JSON）；</li>
 *   <li>{@code askUser}：实例转 waiting-user + pendingDecision，用户经前端决策恢复。</li>
 * </ul>
 */
public enum OnError {

    @JsonProperty("fail") FAIL,

    @JsonProperty("supervisor") SUPERVISOR,

    @JsonProperty("askUser") ASK_USER
}
