package dev.everyagent.worker.agent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.event.EventLogReader;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.worker.plugin.registry.AdvisorProviderRegistry;
import dev.everyagent.worker.plugin.registry.ToolProviderRegistry;
import dev.everyagent.worker.skill.SkillCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link SkillToolExposeInterceptor} 单测(skill 工具自动暴露机制中段:读取触发 +
 * 落盘持久 + 冷启动回灌):
 * <ul>
 *   <li>read_file 成功读技能知识包路径 → exposedTools 双通道写入(内存 AgentContext +
 *       台账条目)且 persistFinal 落盘 agents.json 含 exposedTools 字段;</li>
 *   <li>失败前缀不触发;非技能路径不触发;工具名非 read_file 不触发;</li>
 *   <li>沙箱翻译路径(自定义 SandboxBackend 有映射)命中;</li>
 *   <li>frontmatter 捏造名(不在 hidden 池)照写集合(interceptor 层断言;组装期
 *       交集过滤由 AgentRunner 既有逻辑保证,不在此重复测);</li>
 *   <li>回灌:agents.json 含 exposedTools → AgentLedger 恢复 + AgentBuilder.build()
 *       重建实体后集合就位。</li>
 * </ul>
 */
class SkillToolExposeInterceptorTest {

    // ── 测试设施 ──

    /** 恒等翻译后端(DIRECT 形态:toHost 默认恒等,无沙箱映射)。 */
    private static final SandboxBackend IDENTITY_BACKEND = new SandboxBackend() {
        @Override public String id() { return "direct"; }
    };

    private static SkillCatalog catalogOf(Path skillsDir) {
        WorkerProperties props = new WorkerProperties();
        props.setSkillsDir(skillsDir.toString());
        return new SkillCatalog(props);
    }

    /** 物化一个 skill(含 frontmatter tools 绑定),返回其 skill.md 路径。 */
    private static Path writeSkill(Path skillsDir, String id, String toolsLine) throws IOException {
        Path dir = Files.createDirectories(skillsDir.resolve(id));
        Path skillMd = dir.resolve("skill.md");
        Files.writeString(skillMd, "---\nname: " + id + " 技能\ndescription: 测试技能。\n"
                + toolsLine + "\n---\n\n# " + id + "\n正文。\n");
        return skillMd;
    }

    /** 台账 onTrack 用事件日志桩(监听器空操作)。 */
    private static EventLogReader eventLogStub() {
        return mock(EventLogReader.class);
    }

    /** 记录 exposeTools 调用的 AgentContext 桩(内存通道断言材料)。 */
    private static AgentContext recordingAgent(List<String> sink) {
        AgentContext agent = mock(AgentContext.class);
        doAnswer(inv -> {
            for (String n : inv.<List<String>>getArgument(0)) {
                sink.add(n);
            }
            return null;
        }).when(agent).exposeTools(any());
        when(agent.agentId()).thenReturn("a1");
        return agent;
    }

    /** 最小 per-call ctx:工具名/入参/agents map/agentId/dataDir/emitter 可配置。 */
    private static final class Ctx implements ToolExecutionContext {
        final String toolName;
        final String args;
        final Map<String, AgentContext> agents;
        final Path dataDir;
        final EventEmitter emitter;
        final AtomicReference<EmitEvent> emitted = new AtomicReference<>();

        Ctx(String toolName, String args, Map<String, AgentContext> agents,
                Path dataDir, EventEmitter emitter) {
            this.toolName = toolName;
            this.args = args;
            this.agents = agents;
            this.dataDir = dataDir;
            this.emitter = emitter;
        }

        @Override public Prompt prompt() { return null; }
        @Override public ChatResponse chatResponse() { return null; }
        @Override public List<AssistantMessage.ToolCall> toolCalls() { return List.of(); }
        @Override public AssistantMessage.ToolCall toolCall() {
            return new AssistantMessage.ToolCall("call_1", "function", toolName, args);
        }
        @Override public ToolCallback toolCallback() {
            return new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() {
                    return ToolDefinition.builder().name(toolName).description("test")
                            .inputSchema("{\"type\":\"object\"}").build();
                }
                @Override public ToolMetadata getToolMetadata() {
                    return ToolMetadata.builder().build();
                }
                @Override public String call(String toolInput) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public String arguments() { return args; }
        @Override public void arguments(String arguments) { }
        @Override public void synthesize(String result) { }
        @Override public String synthesizedResult() { return null; }
        @Override public long startedAt() { return 0; }
        @Override public void startedAt(long epochMs) { }
        @Override public dev.everyagent.plugin.api.spi.ToolOutcomeLog toolOutcomes() { return null; }
        @Override public String agentId() { return "a1"; }
        @Override public String subjectId() { return "t1"; }
        @Override public String workspaceRoot() { return null; }
        @Override public String workspaceId() { return "ws1"; }
        @Override public dev.everyagent.plugin.api.model.ModelConfig snapshot() { return null; }
        @Override public EventEmitter emitter() {
            return emitter != null ? emitter : e -> {
                emitted.set(e);
                return 1L;
            };
        }
        @Override public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() { return null; }
        @Override public Map<String, Object> metadata() { return Map.of(); }
        @Override public Path dataDir() { return dataDir; }
        @Override public boolean terminal() { return false; }
        @Override public dev.everyagent.plugin.api.interaction.InteractionService interaction() { return null; }
        @Override public Map<String, AgentContext> agents() { return agents; }
    }

    private static String invoke(SkillToolExposeInterceptor interceptor, Ctx ctx, String tailResult)
            throws Exception {
        ToolExecutionChain tail = c -> tailResult;
        return interceptor.invoke(ctx, tail);
    }

    // ── ① 成功读取 → 双通道写入 + 落盘含字段 ──

    @Test
    void successfulSkillReadExposesBoundToolsInMemoryAndLedger(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        writeSkill(skillsDir, "agent-dispatch", "tools: [run_agent, wait_agents]");
        Path dataDir = Files.createDirectory(tmp.resolve("task"));

        AgentLedger ledger = new AgentLedger();
        ledger.onTrack("t1", dataDir, eventLogStub(), null);
        SkillToolExposeInterceptor interceptor = new SkillToolExposeInterceptor(
                catalogOf(skillsDir), new SandboxPathRegistry(IDENTITY_BACKEND), ledger);

        List<String> sink = new ArrayList<>();
        Map<String, AgentContext> agents = new HashMap<>();
        agents.put("a1", recordingAgent(sink));
        String knowledgePath = catalogOf(skillsDir).scan().get(0).knowledgePath();
        Ctx ctx = new Ctx("read_file", "{\"path\":" + Json.write(knowledgePath) + "}",
                agents, dataDir, null);

        String out = invoke(interceptor, ctx, "# 子 Agent\n完整方法论。");

        assertThat(out).isEqualTo("# 子 Agent\n完整方法论。"); // 结果串原样返回
        assertThat(sink).containsExactly("run_agent", "wait_agents"); // 内存通道
        ObjectNodeEntry entry = readLedgerEntry(ledger);
        assertThat(entry.exposedTools()).containsExactly("run_agent", "wait_agents"); // 台账通道

        // 落盘:persistFinal(既有写者路径)后 agents.json 含 exposedTools 字段
        ledger.persistFinal("t1");
        String disk = Files.readString(dataDir.resolve("agents.json"));
        assertThat(disk).contains("\"exposedTools\"");
        assertThat(disk).contains("run_agent").contains("wait_agents");
    }

    /** 台账条目断言材料。 */
    private record ObjectNodeEntry(List<String> exposedTools) {
    }

    private static ObjectNodeEntry readLedgerEntry(AgentLedger ledger) {
        var entry = ledger.getLiveAgent("t1", "a1");
        assertThat(entry).isNotNull();
        List<String> tools = new ArrayList<>();
        var arr = entry.path("exposedTools");
        if (arr.isArray()) {
            arr.forEach(n -> tools.add(n.asString()));
        }
        return new ObjectNodeEntry(tools);
    }

    // ── ② 失败前缀不触发 ──

    @Test
    void failurePrefixResultDoesNotExpose(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        writeSkill(skillsDir, "agent-dispatch", "tools: [run_agent]");
        Path dataDir = Files.createDirectory(tmp.resolve("task"));
        AgentLedger ledger = new AgentLedger();
        ledger.onTrack("t1", dataDir, eventLogStub(), null);
        SkillToolExposeInterceptor interceptor = new SkillToolExposeInterceptor(
                catalogOf(skillsDir), new SandboxPathRegistry(IDENTITY_BACKEND), ledger);

        List<String> sink = new ArrayList<>();
        Map<String, AgentContext> agents = new HashMap<>();
        agents.put("a1", recordingAgent(sink));
        String knowledgePath = catalogOf(skillsDir).scan().get(0).knowledgePath();
        Ctx ctx = new Ctx("read_file", "{\"path\":" + Json.write(knowledgePath) + "}",
                agents, dataDir, null);

        String out = invoke(interceptor, ctx, ExecResults.TOOL_FAILURE_PREFIX + " 文件不存在");

        assertThat(out).startsWith(ExecResults.TOOL_FAILURE_PREFIX);
        assertThat(sink).isEmpty();
        assertThat(ledger.getLiveAgent("t1", "a1")).isNull();
    }

    // ── ③ 非技能路径不触发 ──

    @Test
    void nonSkillPathDoesNotExpose(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        writeSkill(skillsDir, "agent-dispatch", "tools: [run_agent]");
        AgentLedger ledger = new AgentLedger();
        SkillToolExposeInterceptor interceptor = new SkillToolExposeInterceptor(
                catalogOf(skillsDir), new SandboxPathRegistry(IDENTITY_BACKEND), ledger);

        List<String> sink = new ArrayList<>();
        Map<String, AgentContext> agents = new HashMap<>();
        agents.put("a1", recordingAgent(sink));
        Ctx ctx = new Ctx("read_file", "{\"path\":\"docs/readme.md\"}", agents, null, null);

        invoke(interceptor, ctx, "普通文件内容");

        assertThat(sink).isEmpty();
    }

    // ── ④ 工具名非 read_file 不触发 ──

    @Test
    void nonReadFileToolDoesNotExpose(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        writeSkill(skillsDir, "agent-dispatch", "tools: [run_agent]");
        SkillToolExposeInterceptor interceptor = new SkillToolExposeInterceptor(
                catalogOf(skillsDir), new SandboxPathRegistry(IDENTITY_BACKEND), new AgentLedger());

        List<String> sink = new ArrayList<>();
        Map<String, AgentContext> agents = new HashMap<>();
        agents.put("a1", recordingAgent(sink));
        String knowledgePath = catalogOf(skillsDir).scan().get(0).knowledgePath();
        // shell 类工具读了同一路径(如 cat)不触发——只认 read_file
        Ctx ctx = new Ctx("bash", "{\"command\":\"cat " + knowledgePath + "\"}", agents, null, null);

        invoke(interceptor, ctx, "skill 内容");

        assertThat(sink).isEmpty();
    }

    // ── ⑤ 沙箱翻译路径(有映射)命中 ──

    @Test
    void sandboxTranslatedPathHitsAfterReverseTranslation(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        writeSkill(skillsDir, "agent-dispatch", "tools: [run_agent]");
        String knowledgePath = catalogOf(skillsDir).scan().get(0).knowledgePath();

        // 沙箱后端桩:/sandbox-view/skill.md → 宿主知识包真实路径(模拟 WSL 挂载翻译)
        Path hostSkill = Path.of(knowledgePath);
        SandboxBackend translating = new SandboxBackend() {
            @Override public String id() { return "wsl-test"; }
            @Override public Path toHost(String sandboxPath) {
                return "/sandbox-view/skill.md".equals(sandboxPath) ? hostSkill : null;
            }
        };
        AgentLedger ledger = new AgentLedger();
        ledger.onTrack("t1", tmp, eventLogStub(), null);
        SkillToolExposeInterceptor interceptor = new SkillToolExposeInterceptor(
                catalogOf(skillsDir), new SandboxPathRegistry(translating), ledger);

        List<String> sink = new ArrayList<>();
        Map<String, AgentContext> agents = new HashMap<>();
        agents.put("a1", recordingAgent(sink));
        Ctx ctx = new Ctx("read_file", "{\"path\":\"/sandbox-view/skill.md\"}", agents, tmp, null);

        invoke(interceptor, ctx, "skill 内容(沙箱路径读取)");

        assertThat(sink).containsExactly("run_agent");
        assertThat(readLedgerEntry(ledger).exposedTools()).containsExactly("run_agent");
    }

    // ── ⑥ frontmatter 捏造名(不在 hidden 池)照写集合 ──

    @Test
    void fabricatedToolNameStillWrittenToSet(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        writeSkill(skillsDir, "fake-tools", "tools: [no_such_tool_x]");
        AgentLedger ledger = new AgentLedger();
        SkillToolExposeInterceptor interceptor = new SkillToolExposeInterceptor(
                catalogOf(skillsDir), new SandboxPathRegistry(IDENTITY_BACKEND), ledger);

        List<String> sink = new ArrayList<>();
        Map<String, AgentContext> agents = new HashMap<>();
        agents.put("a1", recordingAgent(sink));
        String knowledgePath = catalogOf(skillsDir).scan().get(0).knowledgePath();
        Ctx ctx = new Ctx("read_file", "{\"path\":" + Json.write(knowledgePath) + "}",
                agents, null, null);

        invoke(interceptor, ctx, "skill 内容");

        // 捏造名仅写集合:interceptor 层不校验 hidden 池(组装期交集过滤由 AgentRunner 保证)
        assertThat(sink).containsExactly("no_such_tool_x");
    }

    // ── ⑦ 瞬态 trace 可见性(不落盘) ──

    @Test
    void exposureEmitsTransientTrace(@TempDir Path tmp) throws Exception {
        Path skillsDir = Files.createDirectory(tmp.resolve("skills"));
        writeSkill(skillsDir, "agent-dispatch", "tools: [run_agent]");
        SkillToolExposeInterceptor interceptor = new SkillToolExposeInterceptor(
                catalogOf(skillsDir), new SandboxPathRegistry(IDENTITY_BACKEND), new AgentLedger());

        Map<String, AgentContext> agents = new HashMap<>();
        agents.put("a1", recordingAgent(new ArrayList<>()));
        String knowledgePath = catalogOf(skillsDir).scan().get(0).knowledgePath();
        Ctx ctx = new Ctx("read_file", "{\"path\":" + Json.write(knowledgePath) + "}",
                agents, null, null);

        invoke(interceptor, ctx, "skill 内容");

        EmitEvent event = ctx.emitted.get();
        assertThat(event).isNotNull();
        assertThat(event.kind()).isEqualTo("task.trace");
        assertThat(event.persist()).isFalse(); // 瞬态,不落盘
        assertThat(event.agentId()).isEqualTo("a1");
        assertThat(event.data()).isInstanceOf(dev.everyagent.plugin.api.event.Events.TracePayload.class);
        var payload = (dev.everyagent.plugin.api.event.Events.TracePayload) event.data();
        assertThat(payload.kind()).isEqualTo("skill_tool_expose");
        assertThat(payload.summary()).contains("run_agent");
    }

    // ── ⑧ 回灌:agents.json 含 exposedTools → 重建 agent 后集合就位 ──

    @Test
    void ledgerBackfillRestoresExposedToolsOnRebuild(@TempDir Path tmp) throws Exception {
        Path dataDir = Files.createDirectory(tmp.resolve("task"));
        Files.writeString(dataDir.resolve("agents.json"), """
                {"agents":[{"agentId":"a_main","createdAt":1,"status":"stopped",
                "exposedTools":["run_agent","wait_agents"]}]}
                """);
        AgentLedger ledger = new AgentLedger();
        ledger.onTrack("t1", dataDir, eventLogStub(), null); // 冷启动恢复:agents.json → 内存台账

        // 主 agent 每轮新建实体:经 AgentBuilder.build() 统一回灌口
        WorkerProperties props = new WorkerProperties();
        AgentBuilder builder = new AgentBuilder(new ToolProviderRegistry(),
                new AdvisorProviderRegistry(), mock(ToolCallingManager.class), props,
                null, null, null, null, null, null, mock(AgentRunner.class), ledger);
        ChatModel model = mock(ChatModel.class);
        when(model.getOptions()).thenReturn(OpenAiChatOptions.builder().build());
        var exec = mock(dev.everyagent.plugin.api.execution.ExecContext.class);
        when(exec.workspaceRoot()).thenReturn(null); // 跳过沙箱路径注册
        when(exec.agents()).thenReturn(new HashMap<String, AgentContext>());
        when(exec.emitter()).thenReturn(e -> 1L);
        when(exec.subjectId()).thenReturn("t1");
        when(exec.dataDir()).thenReturn(dataDir);

        AgentEntity entity = builder.create("a_main", model,
                OpenAiChatOptions.builder().build(), exec, "cfg1").build();

        assertThat(entity.exposedTools)
                .containsExactlyInAnyOrder("run_agent", "wait_agents");

        // 主体未 track 时回退直读磁盘(workflow/单测形态)
        AgentLedger cold = new AgentLedger();
        assertThat(cold.exposedToolsOf("t1", dataDir, "a_main"))
                .containsExactly("run_agent", "wait_agents");
        assertThat(cold.exposedToolsOf("t1", dataDir, "other")).isEmpty();
    }
}
