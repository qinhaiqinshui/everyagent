package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.core.Ordered;
import reactor.core.publisher.Flux;
import tools.jackson.databind.JsonNode;

/**
 * 本轮文件改动收集 advisor(独立普通 advisor,<b>不继承</b> {@link ToolCallingAdvisor})。
 *
 * <p>位置:order = {@link Ordered#HIGHEST_PRECEDENCE} + 301,位于 {@link LoopRepeatGuardAdvisor}
 * ({@code ToolCallingAdvisor},HIGHEST+300)的<b>内层</b>、模型侧({@code ChatModelStreamAdvisor} 最内层)
 * 的<b>外层</b>。这样它通过 {@link #adviseStream} 的 {@code doOnNext} <b>直接看到模型流</b>——
 * 关键事实:OpenAiChatModel 流式内部已用 {@code bufferUntil}+{@code ChunkMerger} 把工具调用分片
 * <b>合并成一条完整消息</b>(含完整 toolCalls/思考/正文)输出,{@code ChatModelStreamAdvisor} 原样透传
 * 不过滤。因此本 advisor 无需继承 ToolCallingAdvisor,也无需聚合——工具轮就是流里一条
 * {@code hasToolCalls()==true} 的完整 AI 消息,直接检查即可。工具循环/事件发射/死循环检测仍由
 * {@link LoopRepeatGuardAdvisor} 承担,本 advisor 纯旁观,对其他 advisor 零影响。
 *
 * <p>职责(替代原 {@code FileTools.notifySaved} 与 {@code TaskManager.persistTurnFileChanges}):
 * <ul>
 *   <li><b>检测记录</b>:每轮 {@code doOnNext} 检查 AI 回复的工具调用,命中
 *       {@code update_file} / {@code create_file} 则解析 path 并记录到 {@link FileChangesCollector}
 *       (主/子 agent 共享的 {@link TaskEntry#fileChanges} 回合槽,子 agent 递归记录归入当前回合)。</li>
 *   <li><b>收口保存</b>:<b>「本轮无工具调用」= 工具循环最后一轮 = 整次 run 完成</b>,在该轮
 *       {@code doOnComplete} 由主 agent 把本轮文件变更的<b>轻量摘要</b>与<b>全文</b>分别填充到
 *       {@link TaskEntry#fileChangesLight} / {@link TaskEntry#fileChangesFull}(此后由
 *       {@link RoundIndexAdvisor} 落盘:摘要内联进 rounds.jsonl 每轮行、全文写
 *       {@code file-changes/<roundId>.json}),并清空回合槽。不再发 kind='file_changes' 的 task.trace。</li>
 * </ul>
 *
 * <p><b>顺序(关键)</b>:收口填充发生在内层该轮 doOnComplete(整 run 全部工具调用已记录),
 * 早于最外层 {@link MeasureDurationAdvisor} 的 doOnComplete → 耗时回填恒在文件变更之后
 * (rounds.jsonl 每轮 durationMs 与 fileChanges 分离,互不干扰)。注意:由于本 advisor 位于工具循环驱动层内层,fileChanges 的
 * 消费({@link RoundIndexAdvisor#persistRounds} 落盘)会排在<b>最终回答 message 之前</b>(在最后一轮模型流完成、权威 message 落盘前即收口)——这是
 * 「内层直接看到工具轮」与「回合末收口」不可兼得的取舍;如需 fileChanges 排在最终 message 之后,
 * 需将收口拆到外层(见最终交付说明)。
 *
 * <p>设计纪律:per-run 物化(每 run 新建实例,状态随实例隔离),多任务并发安全;流式({@link #adviseStream})
 * 为 worker 唯一路径(AgentRunner 始终 stream),非流式 call 不实现记录(默认透传)。
 */
public class FileChangeAdvisor implements StreamAdvisor {

    /** 需要记录文件改变的工具名(与 FileTools 注册的 @Tool 名一致)。 */
    private static final String UPDATE_FILE = "update_file";
    private static final String CREATE_FILE = "create_file";

    /** 目标 agent(主 agent 建收集器+收口;子 agent 只记录到共享槽)。 */
    private final AgentEntity a;

    /** 主 agent 首次 adviseStream 建回合收集器(per-run 实例标志)。 */
    private boolean collectorInitialized = false;
    /** 当前轮(本次 adviseStream 调用)是否含工具调用:有=工具轮(将继续递归,延后收口)。 */
    private boolean turnHasToolCalls = false;

    public FileChangeAdvisor(AgentEntity a) {
        this.a = a;
    }

    @Override
    public String getName() {
        return "File Change Advisor";
    }

    @Override
    public int getOrder() {
        // 位于 LoopRepeatGuardAdvisor(ToolCallingAdvisor,HIGHEST+300)内层、模型(ChatModelStreamAdvisor)外层:
        // doOnNext 直接看到模型流,工具轮为模型层合并后的完整消息(含 toolCalls)。
        // doOnComplete 先于最外层 MeasureDurationAdvisor 触发 → file_changes 先落盘、「Done in」后写。
        return Ordered.HIGHEST_PRECEDENCE + 301;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest,
            StreamAdvisorChain streamAdvisorChain) {
        if (a.kind == AgentEntity.Kind.MAIN && !collectorInitialized) {
            a.task.fileChanges = new FileChangesCollector();
            collectorInitialized = true;
        }
        turnHasToolCalls = false; // 每轮(每次内层链进入)重置
        return streamAdvisorChain.nextStream(chatClientRequest)
                .doOnNext(this::record)
                .doOnComplete(this::finalizeIfLastTurn);
    }

    /** 逐条检查模型流:工具轮已是完整消息(含 toolCalls),命中 update_file/create_file 则记录。 */
    private void record(ChatClientResponse chunk) {
        ChatResponse cr = chunk.chatResponse();
        if (cr == null || cr.getResult() == null) {
            return;
        }
        AssistantMessage out = cr.getResult().getOutput();
        if (out == null) {
            return;
        }
        var calls = out.getToolCalls();
        if (calls == null || calls.isEmpty()) {
            return;
        }
        turnHasToolCalls = true;
        FileChangesCollector c = a.task.fileChanges;
        if (c == null) {
            return;
        }
        for (AssistantMessage.ToolCall tc : calls) {
            recordIfFileChange(c, tc);
        }
    }

    /** 「本轮无工具调用」= 工具循环最后一轮 = 整次 run 完成;主 agent 收口填充 light/full 槽,由 RoundIndexAdvisor 落盘。 */
    private void finalizeIfLastTurn() {
        if (turnHasToolCalls) {
            return; // 工具轮:ToolCallingAdvisor 将继续递归,收口延后到最终回答轮
        }
        if (a.kind != AgentEntity.Kind.MAIN) {
            return; // 子 agent 只记录不写槽,任务级文件变更由主 agent 统一收口
        }
        FileChangesCollector c = a.task.fileChanges;
        if (c == null || c.isEmpty()) {
            return;
        }
        try {
            // 不再发 file_changes trace:轻量摘要内联进 rounds.jsonl 每轮行,全文由 RoundIndexStore 单独落盘。
            a.task.fileChangesLight = c.buildLightSummary();
            a.task.fileChangesFull = c.buildContent();
        } finally {
            a.task.fileChanges = null;
        }
    }

    /** 识别 update_file / create_file 工具调用并记录一次文件改变。 */
    private void recordIfFileChange(FileChangesCollector c, AssistantMessage.ToolCall tc) {
        String name = tc.name();
        if (!UPDATE_FILE.equals(name) && !CREATE_FILE.equals(name)) {
            return;
        }
        JsonNode args = parseArgs(tc.arguments());
        String path = args.path("path").asString(null);
        if (path == null || path.isEmpty()) {
            return;
        }
        if (UPDATE_FILE.equals(name)) {
            String oldcontent = args.path("oldcontent").asString(null);
            String content = args.path("content").asString(null);
            c.onFileSaved(a.agentId, path,
                    oldcontent == null ? "" : oldcontent,
                    content == null ? "" : content,
                    "modified");
        } else {
            String content = args.path("content").asString(null);
            c.onFileSaved(a.agentId, path, "", content == null ? "" : content, "created");
        }
    }

    /** 解析工具参数 JSON(非法/空时回空对象,路径取不到则跳过)。 */
    private static JsonNode parseArgs(String arguments) {
        if (arguments == null || arguments.isEmpty()) {
            return Json.obj();
        }
        try {
            return Json.parse(arguments);
        } catch (RuntimeException e) {
            return Json.obj();
        }
    }
}
