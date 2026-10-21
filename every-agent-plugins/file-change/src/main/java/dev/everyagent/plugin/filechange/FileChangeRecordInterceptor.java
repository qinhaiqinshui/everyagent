package dev.everyagent.plugin.filechange;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.plugin.api.spi.ToolOutcomeLog;
import dev.everyagent.plugin.api.task.TaskRuntime;
import dev.everyagent.plugin.api.task.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

/**
 * 文件写一阶段记账拦截器(file-change 插件注册的 per-call 工具执行拦截链<b>上行段</b>,
 * 架构 §7.15.2)。
 *
 * <p>update_file / create_file <b>调用完成的当下</b>({@code next.proceed} 返回结果串后)一阶段记账:
 * 链上行天然携带执行结果与成败,不再有旧「模型流暂存(pendingByCallId)→ 下一轮请求侧配对
 * ToolResponseMessage 确认」两阶段。成败判定与链上发射点 {@code ToolEventInterceptor.statusOf}
 * 同口径——结果串失败前缀 {@link ExecResults#TOOL_FAILURE_PREFIX} ∨ callId 账
 * {@link ToolOutcomeLog#callOutcome}(不解析结果文本:文案匹配会被「读到该文案源码」自指误判);
 * 文件工具失败 = 抛异常 → 异常处理器产出前缀文本,shell 退出码类判定不适用。失败
 * (文件已存在 / oldcontent 不唯一 / 授权拒绝等)即丢弃,成功才解析入参(path/oldcontent/content)
 * {@code onFileSaved} 写入 provider 维护的<b>任务级共享 collector</b>(主 agent 与全部子 agent
 * 经 {@code subjectId()} 定位同一实例,子 agent 的文件改动不丢失),Entry.source 按
 * {@code ctx.agentId()==mainAgentId} 标注 MAIN/SUB_AGENT(agentId 经 per-run 数据通道
 * RunContext 下行,§7.14.3)。
 *
 * <p><b>order=400</b>:位于发射点 {@code tool-events}(500)<b>外侧</b>——上行逆序执行,
 * 本节点上行段拿到 callId 账时发射点已写账(前缀判定自身不依赖顺序,账是双保险)。
 * 主体已收口({@code ctx.terminal()})不再记账:轮已闭合,记账只会造孤儿 collector(宁漏记)。
 *
 * <p>无状态(共享 collector/taskService 均任务键控),多任务并发安全;收口与落盘链路在
 * {@link FileChangeAdvisor}(主 agent 末轮收口)与 {@link FileChangeAdvisorProvider}
 * (RoundClosedListener 写分片),本节点只管记账。
 */
public class FileChangeRecordInterceptor implements ToolExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(FileChangeRecordInterceptor.class);

    static final String UPDATE_FILE = "update_file";
    static final String CREATE_FILE = "create_file";

    private final TaskService taskService;
    private final FileChangeAdvisorProvider provider;

    public FileChangeRecordInterceptor(TaskService taskService, FileChangeAdvisorProvider provider) {
        this.taskService = taskService;
        this.provider = provider;
    }

    @Override
    public String id() {
        return "file-change-record";
    }

    @Override
    public float order() {
        return 400f;
    }

    @Override
    public String invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception {
        String result = next.proceed(ctx);
        record(ctx, result);
        return result;
    }

    /** 链上行一阶段记账:当前调用是文件写工具且执行成功 → 解析入参写共享 collector。 */
    private void record(ToolExecutionContext ctx, String result) {
        String name = ctx.toolCall() == null ? null : ctx.toolCall().name();
        if (!UPDATE_FILE.equals(name) && !CREATE_FILE.equals(name)) {
            return; // 非文件写工具:放行不记账
        }
        if (ctx.terminal()) {
            return; // 主体已收口:轮已闭合,记账只会造孤儿 collector(宁漏记)
        }
        String taskId = ctx.subjectId();
        String agentId = ctx.agentId();
        if (taskId == null || taskId.isEmpty() || agentId == null || agentId.isEmpty()) {
            return; // 无任务上下文/无 agent 归属(裸用/单测):变更无归属,不收集
        }
        if (failed(ctx, result)) {
            log.debug("[file-change] 丢弃失败工具调用的变更记录 tool={} callId={} task={}",
                    name, ctx.toolCall().id(), taskId);
            return;
        }
        JsonNode args = parseArgs(ctx.arguments());
        String path = args.path("path").asString(null);
        if (path == null || path.isEmpty()) {
            return;
        }
        String before = "";
        String after;
        String changeType;
        if (UPDATE_FILE.equals(name)) {
            String oldcontent = args.path("oldcontent").asString(null);
            before = oldcontent == null ? "" : oldcontent;
            String content = args.path("content").asString(null);
            after = content == null ? "" : content;
            changeType = "modified";
        }
        else {
            String content = args.path("content").asString(null);
            after = content == null ? "" : content;
            changeType = "created";
        }
        provider.activeCollector(taskId)
                .onFileSaved(agentId, sourceOf(taskId, agentId), path, before, after, changeType);
    }

    /** 来源标注:agentId == 任务 mainAgentId → MAIN,否则 SUB_AGENT(任务查不到视为子,与既往口径一致)。 */
    private FileChangesCollector.Source sourceOf(String taskId, String agentId) {
        TaskRuntime taskRuntime = taskService == null ? null : taskService.get(taskId);
        boolean main = taskRuntime != null && agentId.equals(taskRuntime.mainAgentId());
        return main ? FileChangesCollector.Source.MAIN : FileChangesCollector.Source.SUB_AGENT;
    }

    /**
     * 成败判定(与链上发射点 {@code ToolEventInterceptor.statusOf} 同口径,plugin 侧复刻——
     * 该类在 worker,file-change 依 §14.9 零 worker 依赖):
     * <ol>
     *   <li>结果串以 {@link ExecResults#TOOL_FAILURE_PREFIX} 开头 → 失败(异常处理器与缺工具
     *       兜底的产出前缀;文件工具失败 = 抛异常 → 前缀文本,shell 退出码类判定不适用);</li>
     *   <li>否则查 callId 账(发射点 order=500 在本节点内侧,上行先执行,账必已写;
     *       无账 = 该调用未经发射点上行,如守卫 WARN 轮合成/裸用 → 按成功);</li>
     *   <li>账本未装配(null)一律成功(同既往「账本空一律 success」约定)。</li>
     * </ol>
     */
    static boolean failed(ToolExecutionContext ctx, String result) {
        if (result != null && result.startsWith(ExecResults.TOOL_FAILURE_PREFIX)) {
            return true;
        }
        ToolOutcomeLog outcomes = ctx.toolOutcomes();
        return outcomes != null && Boolean.TRUE.equals(outcomes.callOutcome(ctx.toolCall().id()));
    }

    private static JsonNode parseArgs(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Json.obj();
        }
        try {
            return Json.parse(arguments);
        }
        catch (RuntimeException e) {
            return Json.obj();
        }
    }
}
