package dev.everyagent.plugin.workflow.model;

import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 出边条件（命令/AI 节点统一机制，作用于节点完成态 {exitCode, output}；
 * AI 节点正常完成视为 exitCode=0，output=最终回复文本）。
 *
 * <table border="1">
 *   <tr><th>type</th><th>参数</th><th>语义</th></tr>
 *   <tr><td>always（缺省）</td><td>—</td><td>恒满足</td></tr>
 *   <tr><td>onSuccess</td><td>—</td><td>exitCode == 0</td></tr>
 *   <tr><td>onError</td><td>—</td><td>exitCode != 0</td></tr>
 *   <tr><td>exitCode</td><td>value</td><td>等于指定退出码</td></tr>
 *   <tr><td>outputContains</td><td>text</td><td>输出包含指定文本</td></tr>
 *   <tr><td>outputNotContains</td><td>text</td><td>输出不包含指定文本</td></tr>
 *   <tr><td>outputEquals</td><td>text</td><td>输出与文本各自去首尾空白后相等（精确等于，忽略两侧空白）</td></tr>
 *   <tr><td>outputMatches</td><td>pattern</td><td>输出匹配正则（编译失败=定义校验错误）</td></tr>
 * </table>
 *
 * <p>求值语义：节点完成后<b>所有</b>满足条件的出边全部激活（多路分发/扇出）；
 * 存在条件出边但无一满足 = 该节点走到尽头（不报错）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ConditionDef(
        String type,
        Long value,
        String text,
        String pattern) {

    public static final String ALWAYS = "always";
    public static final String ON_SUCCESS = "onSuccess";
    public static final String ON_ERROR = "onError";
    public static final String EXIT_CODE = "exitCode";
    public static final String OUTPUT_CONTAINS = "outputContains";
    public static final String OUTPUT_NOT_CONTAINS = "outputNotContains";
    public static final String OUTPUT_EQUALS = "outputEquals";
    public static final String OUTPUT_MATCHES = "outputMatches";

    /** 全部已知条件类型（校验器据此拒未知类型；求值在引擎，本层只存契约）。 */
    public static final Set<String> KNOWN_TYPES = Set.of(
            ALWAYS, ON_SUCCESS, ON_ERROR, EXIT_CODE,
            OUTPUT_CONTAINS, OUTPUT_NOT_CONTAINS, OUTPUT_EQUALS, OUTPUT_MATCHES);
}
