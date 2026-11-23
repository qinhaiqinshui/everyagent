package dev.everyagent.plugin.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.ToolContext;
import dev.everyagent.plugin.api.spi.WorkspaceManager;
import dev.everyagent.plugin.workflow.engine.WorkflowSubject;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

/**
 * WorkflowToolProvider：hidden 五工具注册、appliesTo 域隔离（排除工作流主体）、
 * 工具经 ToolContext 取工作区（零 per-run 捕获路径）。不依赖 worker 类。
 */
class WorkflowToolProviderTest {

    @TempDir
    Path home;

    static final String WS_A = "ws_tool";

    FakeWorkspaces workspaces;
    WorkflowStore store;
    WorkflowToolProvider provider;

    @BeforeEach
    void setUp() {
        workspaces = new FakeWorkspaces(home);
        workspaces.register(home.resolve("proj"), WS_A);
        store = new WorkflowStore(workspaces);
        provider = new WorkflowToolProvider(store);
    }

    @Test
    void hiddenToolsRegisteredWithExpectedNames() {
        List<ToolCallback> hidden = provider.createHiddenTools(taskCtx());
        assertThat(hidden).extracting(tc -> tc.getToolDefinition().name()).containsExactlyInAnyOrder(
                "workflow_create", "workflow_get", "workflow_update", "workflow_delete", "workflow_list");
        // 常规池为空（五工具全部 hidden）
        assertThat(provider.createTools(taskCtx())).isEmpty();
        assertThat(provider.pluginId()).isEqualTo("workflow");
    }

    @Test
    void appliesToExcludesWorkflowSubjects() {
        ToolContext task = taskCtx();
        assertThat(provider.appliesTo(task)).isTrue();

        // 通道②：metadata 标记
        ToolContext marked = taskCtx(Map.of(WorkflowSubject.MARKER_KEY, Boolean.TRUE), null);
        assertThat(provider.appliesTo(marked)).isFalse();

        // 通道③：dataDir 布局兜底——真实实例目录 workspaces/<ws>/workflows/instances/<wi>
        // （workflows 在 grandparent 层；修复前只查 parent，此布局漏判）
        ToolContext wfInstanceDir = taskCtx(null,
                home.resolve("workspaces").resolve(WS_A).resolve("workflows")
                        .resolve("instances").resolve("wi_x"));
        assertThat(provider.appliesTo(wfInstanceDir)).isFalse();

        // 通道③兼容：直接父级即 workflows 的同域路径（如 definitions/<wfId>）同样命中
        ToolContext wfDirectParent = taskCtx(null,
                home.resolve("workspaces").resolve(WS_A).resolve("workflows").resolve("wf_y"));
        assertThat(provider.appliesTo(wfDirectParent)).isFalse();

        // task 域布局不受影响
        ToolContext taskDataDir = taskCtx(null,
                home.resolve("workspaces").resolve(WS_A).resolve("tasks").resolve("t_x"));
        assertThat(provider.appliesTo(taskDataDir)).isTrue();
    }

    @Test
    void fiveToolsCrudRoundTripThroughStore() {
        ToolContext ctx = taskCtx();
        WorkflowTools tools = new WorkflowTools(store, ctx);

        String definition = "{\"name\":\"评审流\",\"nodes\":["
                + "{\"id\":\"n1\",\"type\":\"start\",\"name\":\"开始\"},"
                + "{\"id\":\"n2\",\"type\":\"ai\",\"name\":\"评审\",\"agent\":\"reviewer\",\"input\":\"评审改动\"}],"
                + "\"edges\":[{\"id\":\"e1\",\"from\":\"n1\",\"to\":\"n2\"}],"
                + "\"agents\":[{\"name\":\"reviewer\",\"systemPrompt\":\"你是评审\"}]}";
        String created = tools.workflow_create(definition);
        String id = Json.parse(created).path("id").asString();
        assertThat(id).startsWith("wf_");

        // list（缺省=当前任务工作区）
        String list = tools.workflow_list(null);
        assertThat(Json.parse(list).path("defs").size()).isEqualTo(1);
        assertThat(Json.parse(list).path("defs").get(0).path("id").asString()).isEqualTo(id);
        assertThat(Json.parse(list).path("defs").get(0).path("nodeCount").asInt()).isEqualTo(2);

        // get
        assertThat(Json.parse(tools.workflow_get(id)).path("name").asString()).isEqualTo("评审流");

        // update（改名）
        String updated = tools.workflow_update(id, definition.replace("评审流", "评审流v2"));
        assertThat(Json.parse(updated).path("id").asString()).isEqualTo(id);
        assertThat(Json.parse(tools.workflow_get(id)).path("name").asString()).isEqualTo("评审流v2");

        // 校验失败：逐项 details
        String bad = tools.workflow_create("{\"name\":\"坏定义\",\"nodes\":[{\"id\":\"n1\",\"type\":\"ai\"}]}");
        assertThat(Json.parse(bad).path("error").asString()).contains("校验失败");
        assertThat(Json.parse(bad).path("details").size()).isGreaterThanOrEqualTo(1);

        // get 不存在
        assertThat(Json.parse(tools.workflow_get("wf_none")).path("error").asString()).contains("NOT_FOUND");

        // delete
        assertThat(Json.parse(tools.workflow_delete(id)).path("ok").asBoolean()).isTrue();
        assertThat(Json.parse(tools.workflow_list(null)).path("defs").size()).isZero();
    }

    @Test
    void listHonorsExplicitWorkspaceParamViaRegistry() {
        WorkflowTools tools = new WorkflowTools(store, taskCtx());
        tools.workflow_create("{\"name\":\"A\",\"nodes\":[{\"id\":\"n1\",\"type\":\"start\",\"name\":\"s\"}]}");
        // 未注册路径 → 报错 JSON（不抛异常回灌）
        String err = tools.workflow_list("Z:/not/registered");
        assertThat(Json.parse(err).path("error").asString()).contains("未注册");
        // 已注册路径 → 该工作区列表
        String ok = tools.workflow_list(home.resolve("proj").toString());
        assertThat(Json.parse(ok).path("defs").size()).isEqualTo(1);
    }

    // ── 最小 ToolContext 桩（零 worker 依赖）──

    private ToolContext taskCtx() {
        return taskCtx(null, null);
    }

    private ToolContext taskCtx(Map<String, Object> metadataOverride, Path dataDirOverride) {
        return new ToolContext() {
            private final Map<String, Object> metadata = metadataOverride != null
                    ? new ConcurrentHashMap<>(metadataOverride) : new ConcurrentHashMap<>();

            @Override public String subjectId() { return "t_task1"; }
            @Override public String workspaceRoot() { return home.resolve("proj").toString(); }
            @Override public String workspaceId() { return WS_A; }
            @Override public ModelConfig snapshot() { return null; }
            @Override public dev.everyagent.plugin.api.model.EventEmitter emitter() { return null; }
            @Override public dev.everyagent.plugin.api.agent.AgentFactory agentFactory() { return null; }
            @Override public Map<String, Object> metadata() { return metadata; }
            @Override public Path dataDir() {
                return dataDirOverride != null ? dataDirOverride
                        : home.resolve("workspaces").resolve(WS_A).resolve("tasks").resolve("t_task1");
            }
            @Override public boolean terminal() { return false; }
            @Override public dev.everyagent.plugin.api.interaction.InteractionService interaction() { return null; }
            @Override public Map<String, dev.everyagent.plugin.api.agent.AgentContext> agents() {
                return new ConcurrentHashMap<>();
            }
            @Override public String agentId() { return "a_main"; }
            @Override public SandboxBackend sandbox() { return null; }
            @Override public WorkspaceManager workspaces() { return workspaces; }
        };
    }
}
