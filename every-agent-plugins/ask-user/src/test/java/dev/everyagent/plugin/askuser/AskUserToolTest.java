package dev.everyagent.plugin.askuser;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.exception.AgentCancelledException;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.interaction.AskOption;
import dev.everyagent.plugin.api.interaction.AskQuestion;
import dev.everyagent.plugin.api.interaction.AskResult;
import dev.everyagent.plugin.api.interaction.InteractionService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AskUserTool 单测 —— §14.9 红线：不依赖 every-agent-worker，
 * ExecContext / WorkerConfig / InteractionService 均为测试内自建桩
 * （接口代理 + 手写 stub），只验证工具自身逻辑：
 * 参数收敛、题目构造（含「其他」输入框追加）、超时透传、三态返回与中断转换。
 */
class AskUserToolTest {

    private static final long TIMEOUT_MS = 1_800_000L;
    private static final String AGENT_ID = "agent-42";

    /** 手写交互桩：捕获 ask() 入参，回放预设结果。 */
    static final class StubInteraction implements InteractionService {
        List<AskQuestion> capturedQuestions;
        long capturedTimeoutMs;
        Map<String, String> capturedContext;
        AskResult reply = new AskResult("answered", "好的");
        boolean throwInterrupted;

        @Override
        public AskResult ask(List<AskQuestion> questions, long timeoutMs, Map<String, String> context)
                throws InterruptedException {
            this.capturedQuestions = questions;
            this.capturedTimeoutMs = timeoutMs;
            this.capturedContext = context;
            if (throwInterrupted) {
                throw new InterruptedException("simulated");
            }
            return reply;
        }

        @Override
        public void askAsync(List<AskQuestion> questions, long timeoutMs, Map<String, String> context,
                Consumer<AskResult> callback) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hasPendingFor(String taskId, String agentId) {
            return false;
        }
    }

    /** ExecContext 桩：JDK 动态代理，interaction() 返回注入的桩，其余槽位返回默认值。 */
    private static ExecContext execContext(StubInteraction interaction) {
        return (ExecContext) Proxy.newProxyInstance(
                ExecContext.class.getClassLoader(),
                new Class<?>[]{ExecContext.class},
                (proxy, method, args) -> {
                    if ("interaction".equals(method.getName())) {
                        return interaction;
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    if ("equals".equals(method.getName())) {
                        return proxy == args[0];
                    }
                    if ("toString".equals(method.getName())) {
                        return "StubExecContext";
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) { return false; }
                    if (rt == Map.class) { return Map.of(); }
                    return null;
                });
    }

    /** WorkerConfig 桩：limits().askTimeoutMs() 返回固定超时，其余方法零值。 */
    private static WorkerConfig workerConfig() {
        Object limits = Proxy.newProxyInstance(
                WorkerConfig.Limits.class.getClassLoader(),
                new Class<?>[]{WorkerConfig.Limits.class},
                (proxy, method, args) -> {
                    if ("askTimeoutMs".equals(method.getName())) {
                        return TIMEOUT_MS;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) { return false; }
                    if (rt == int.class) { return 0; }
                    if (rt == long.class) { return 0L; }
                    if (rt == double.class) { return 0d; }
                    return null;
                });
        return (WorkerConfig) Proxy.newProxyInstance(
                WorkerConfig.class.getClassLoader(),
                new Class<?>[]{WorkerConfig.class},
                (proxy, method, args) -> {
                    if ("limits".equals(method.getName())) {
                        return limits;
                    }
                    if ("hashCode".equals(method.getName())) {
                        return System.identityHashCode(proxy);
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) { return false; }
                    return null;
                });
    }

    private static AskUserTool tool(StubInteraction interaction) {
        return new AskUserTool(workerConfig(), execContext(interaction), AGENT_ID);
    }

    private static List<AskUserTool.AskQuestionInput> oneQuestion() {
        return List.of(new AskUserTool.AskQuestionInput("主角性别?", List.of("男", "女")));
    }

    @Test
    void emptyQuestionsSkipsAsk() {
        StubInteraction stub = new StubInteraction();
        AskUserTool tool = tool(stub);

        assertThat(tool.ask_user(null)).isEqualTo("未提供任何问题,跳过提问。");
        assertThat(tool.ask_user(List.of())).isEqualTo("未提供任何问题,跳过提问。");
        assertThat(stub.capturedQuestions).as("不应发起任何提问").isNull();
    }

    @Test
    void answeredReturnsTextAndBuildsQuestions() throws InterruptedException {
        StubInteraction stub = new StubInteraction();
        stub.reply = new AskResult("answered", "用户选了:女");
        AskUserTool tool = tool(stub);

        String out = tool.ask_user(oneQuestion());

        assertThat(out).isEqualTo("用户选了:女");
        // 题目构造:prompt 透传,radio 选项 + 自动追加「其他」输入框
        assertThat(stub.capturedQuestions).hasSize(1);
        AskQuestion q = stub.capturedQuestions.get(0);
        assertThat(q.prompt()).isEqualTo("主角性别?");
        assertThat(q.options()).hasSize(3);
        assertThat(q.options().get(0)).isEqualTo(new AskOption("男", "男", AskOption.TYPE_RADIO));
        assertThat(q.options().get(2)).isEqualTo(new AskOption("其他", "", AskOption.TYPE_INPUT));
        // 超时取自 WorkerConfig.limits().askTimeoutMs(),context 透传 agentId
        assertThat(stub.capturedTimeoutMs).isEqualTo(TIMEOUT_MS);
        assertThat(stub.capturedContext).containsEntry("agentId", AGENT_ID);
    }

    @Test
    void timeoutReturnsGuidanceText() {
        StubInteraction stub = new StubInteraction();
        stub.reply = new AskResult("timeout", null);

        assertThat(tool(stub).ask_user(oneQuestion()))
                .isEqualTo("用户未在规定时间内回答(已超时)。请基于现有信息继续,并明确告知用户未获得答复。");
    }

    @Test
    void cancelledReturnsText() {
        StubInteraction stub = new StubInteraction();
        stub.reply = new AskResult("cancelled", null);

        assertThat(tool(stub).ask_user(oneQuestion())).isEqualTo("提问已被取消。");
    }

    @Test
    void interruptedBecomesAgentCancelledException() {
        StubInteraction stub = new StubInteraction();
        stub.throwInterrupted = true;

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> tool(stub).ask_user(oneQuestion()))
                .isInstanceOf(AgentCancelledException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
        Thread.interrupted(); // 清理中断标记,避免影响后续测试
    }

    @Test
    void lenientDeserializationCoercesLlmQuirks() throws Exception {
        String json = """
                [{"question": {"text": "主角性别?"}, "options": [{"label": "男"}, {"name": "女"}, "其他"]}]
                """;
        AskUserTool.AskQuestionInput[] parsed = new ObjectMapper()
                .readValue(json, AskUserTool.AskQuestionInput[].class);

        assertThat(parsed).hasSize(1);
        assertThat(parsed[0].question()).isEqualTo("主角性别?");
        // 对象形态选项按 text/label/name/value 等键收敛为字符串,标量原样保留
        assertThat(parsed[0].options()).containsExactly("男", "女", "其他");
    }

    @Test
    void providerWiresToolWithPluginId() {
        StubInteraction stub = new StubInteraction();
        dev.everyagent.plugin.api.spi.ToolContext toolCtx =
                (dev.everyagent.plugin.api.spi.ToolContext) Proxy.newProxyInstance(
                        dev.everyagent.plugin.api.spi.ToolContext.class.getClassLoader(),
                        new Class<?>[]{dev.everyagent.plugin.api.spi.ToolContext.class},
                        (proxy, method, args) -> {
                            if ("agentId".equals(method.getName())) { return AGENT_ID; }
                            if ("interaction".equals(method.getName())) { return stub; }
                            if ("hashCode".equals(method.getName())) { return System.identityHashCode(proxy); }
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) { return false; }
                            if (rt == Map.class) { return Map.of(); }
                            return null;
                        });

        AskUserToolProvider provider = new AskUserToolProvider(workerConfig());
        assertThat(provider.pluginId()).isEqualTo("ask-user");
        var callbacks = provider.createTools(toolCtx);
        assertThat(callbacks).hasSize(1);
        assertThat(callbacks.get(0).getToolDefinition().name()).isEqualTo("ask_user");
    }
}
