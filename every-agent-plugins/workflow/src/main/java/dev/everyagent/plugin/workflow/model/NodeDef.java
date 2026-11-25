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
 *   <li>{@code maxRuns}：循环上限（调度时 runCount+1 &gt; maxRuns 触发错误策略），∈[1,10]；
 *       joinMode=all 时按<b>触发执行次数</b>计——等齐触发一次=消耗一次，并行分支→汇总
 *       不再需要按入边数手算 maxRuns；</li>
 *   <li>{@code joinMode}：节点级汇合屏障（String）——{@code none}（缺省，含 null/空串）
 *       每条激活入边到达即触发一次执行；{@code all} 等齐全部入边各激活一次才触发一次
 *       （触发后到达记账清零，回指循环每轮重新等齐；环内同一条边重复到达按边 id 去重
 *       记一条）。仅对有入边的 command/ai 节点有意义：start 无入边、入边数≤1 的节点
 *       all 等价 none（校验与引擎均宽松等价处理，不报错）。</li>
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
        String systemPrompt,
        String joinMode) {

    public static final String TYPE_START = "start";
    public static final String TYPE_COMMAND = "command";
    public static final String TYPE_AI = "ai";

    /** 汇合屏障：无屏障（每条激活入边到达即触发一次执行；null/空串同义）。 */
    public static final String JOIN_NONE = "none";
    /** 汇合屏障：等齐全部入边各激活一次才触发一次执行。 */
    public static final String JOIN_ALL = "all";

    /** joinMode=all 判定（null/""/none → false；引擎侧宽松等价，未知值由校验器拦截）。 */
    public boolean isJoinAll() {
        return JOIN_ALL.equals(joinMode());
    }
}
