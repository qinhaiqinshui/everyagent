package dev.everyagent.plugin.workflow.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 边定义：源节点完成时对出边条件<b>求值一次</b>，满足则该边标记为「已激活」；
 * 节点就绪 = 存在已激活的入边。condition 缺省 = always。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EdgeDef(
        String id,
        String from,
        String to,
        ConditionDef condition) {
}
