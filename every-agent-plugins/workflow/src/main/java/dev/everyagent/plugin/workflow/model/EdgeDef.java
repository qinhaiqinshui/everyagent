package dev.everyagent.plugin.workflow.model;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 边定义：源节点完成时对出边条件<b>求值一次</b>，满足则该边标记为「已激活」；
 * 节点就绪 = 存在已激活的入边。condition 缺省 = always。
 *
 * <p><b>边数据传递（校验-回指重试的通道）</b>：边激活时除路由外还写运行时变量——
 * ①{@code assigns}：源节点输出写入具名变量；②{@code input}：模板经「用户变量 +
 * 已写运行时变量」渲染后写入变量（缺省名 {@code input}）。两步均在调度下游之前
 * 完成（schedLock 内先赋值后渲染——本边 assigns 可被本边模板引用），下游节点的
 * command/input 用 {@code {{var}}} 消费（命令与 AI 节点同机制）。变量解析优先级：
 * 边写入值 &gt; 用户输入 &gt; 声明默认值；校验器禁「边写入变量与用户输入变量同名」
 * 与「不同源节点写同名变量」（并行竞写无静态时序），同源多边允许按边列表序后写
 * 覆盖。值截断 8KB（全文在 runs 文件留档）；写入快照落 {@code node.output} 事件
 * payload.vars（rerun 重放依据）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record EdgeDef(
        String id,
        String from,
        String to,
        ConditionDef condition,
        List<EdgeAssign> assigns,
        EdgeInput input) {

    /** 旧四参构造（纯路由边——无数据传递；既有构造调用点与旧定义等价形态）。 */
    public EdgeDef(String id, String from, String to, ConditionDef condition) {
        this(id, from, to, condition, null, null);
    }

    /**
     * 边级输出赋值：边激活时把源节点输出写入运行时变量。
     * {@code from} 缺省/空 = {@code output}（源节点 conditionOutput：命令=stdout+stderr、
     * AI=最终回复全文）；v1 仅此一种来源，未知值由校验器拒绝（列表+对象结构为
     * exitCode/正则捕获等 v2 增量来源留口）。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EdgeAssign(
            String var,
            String from) {

        public static final String FROM_OUTPUT = "output";

        /** v1 全部已知来源（校验器据此拒未知值）。 */
        public static final Set<String> KNOWN_FROMS = Set.of(FROM_OUTPUT);

        /** 生效来源（null/空 = 缺省 output）。 */
        public String effectiveFrom() {
            return from == null || from.isBlank() ? FROM_OUTPUT : from;
        }
    }

    /**
     * 边级输入模板：边激活时渲染模板并写入变量（缺省名 {@code input}）。
     * 模板可引用本边及此前已写的运行时变量与用户输入变量——「路径即指令」：
     * 首轮/回指轮由不同入边携带不同模板，目标节点 input/command 引 {@code {{var}}}
     * 消费，无需 defaultValue 哨兵。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EdgeInput(
            String var,
            String template) {

        public static final String DEFAULT_VAR = "input";

        /** 生效变量名（null/空 = 缺省 input）。 */
        public String effectiveVar() {
            return var == null || var.isBlank() ? DEFAULT_VAR : var;
        }
    }
}
