package dev.everyagent.plugin.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 节点定义（v1 仅 开始/命令/AI 三种；架构留扩展点）。
 *
 * <ul>
 *   <li>{@code start}：开始节点，全图唯一，先行执行（x/y 画布坐标）；</li>
 *   <li>{@code command}：命令节点——{@code command} 经 shell 执行（Windows cmd.exe /c、
 *       Unix /bin/sh -c），{@code timeoutMs} 仅命令节点有效，超时 destroyForcibly；</li>
 *   <li>{@code ai}：AI 节点——{@code agent} 指定命名会话线（缺省=一次性会话），
 *       {@code modelConfigId} 指定本节点的模型配置（null=系统默认模型），
 *       {@code systemPrompt} 为本节点装配 AI 的系统提示（可空；同会话线的各节点
 *       各自生效，编辑器候选名扫描自上游 AI 节点），
 *       {@code input} 为输入模板（{@code {{var}}} 渲染）；</li>
 *   <li>{@code maxRuns}：循环上限（调度时 runCount+1 &gt; maxRuns 触发错误策略），∈[1,10]。</li>
 * </ul>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeDef(
        String id,
        String type,
        String name,
        Double x,
        Double y,
        String command,
        Long timeoutMs,
        Integer maxRuns,
        String agent,
        String modelConfigId,
        String input,
        String systemPrompt) {

    public static final String TYPE_START = "start";
    public static final String TYPE_COMMAND = "command";
    public static final String TYPE_AI = "ai";
}
