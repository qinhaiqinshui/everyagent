// ea: JUnit5 单测：只依赖 every-agent-plugin-api，不依赖 worker（§14.9 红线）
package dev.everyagent.plugin.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.slash.WorkflowRunSlashResolver;
import dev.everyagent.plugin.workflow.slash.WorkflowRunToken;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.JsonNode;

/**
 * 「运行工作流」token 三件套：构造 → 编解码往返 → resolver 渲染（含定义名缺失经
 * store 反查补全）。payload 字段名 {@code definitionId/definitionName/variables} 与
 * 前端 RunDialog 组装的 payload 逐字一致（前端不注册提交 resolver，原串上行后由
 * worker 侧 SlashTokenResolveAdvisor 分发到本 resolver）。
 */
class WorkflowRunTokenTest {

    @TempDir
    Path home;

    FakeWorkspaces workspaces;
    WorkflowStore store;

    static final String WS = "ws_run";

    @BeforeEach
    void setUp() {
        workspaces = new FakeWorkspaces(home);
        workspaces.register(home.resolve("proj"), WS);
        store = new WorkflowStore(workspaces);
    }

    @Test
    void kindIsWorkflowDotRunAndResolverMatches() {
        assertThat(WorkflowRunToken.KIND).isEqualTo("workflow.run");
        assertThat(new WorkflowRunSlashResolver(store).kind()).isEqualTo(WorkflowRunToken.KIND);
    }

    @Test
    void buildTokenRoundTripsThroughEncoder() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("topic", "小说");
        vars.put("chapter", "3");
        String token = WorkflowRunToken.buildToken("wf_abc", "日更写作", vars);

        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(token);
        assertThat(parsed).isNotNull();
        assertThat(parsed.kind()).isEqualTo("workflow.run");
        assertThat(parsed.label()).isEqualTo("运行「日更写作」");
        assertThat(parsed.summary()).isEqualTo("参数: topic=小说; chapter=3");
        assertThat(parsed.payload().path("definitionId").asString("")).isEqualTo("wf_abc");
        assertThat(parsed.payload().path("definitionName").asString("")).isEqualTo("日更写作");
        assertThat(parsed.payload().path("variables").path("topic").asString("")).isEqualTo("小说");
        assertThat(parsed.payload().path("variables").path("chapter").asString("")).isEqualTo("3");
    }

    @Test
    void buildTokenFallsBackWhenNameAndVariablesAbsent() {
        String token = WorkflowRunToken.buildToken("wf_x", "", null);
        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(token);
        assertThat(parsed).isNotNull();
        // 定义名空 → label 退用 definitionId；summary 无参数
        assertThat(parsed.label()).isEqualTo("运行「wf_x」");
        assertThat(parsed.summary()).isEqualTo("无参数");
        // payload 原样携带空 definitionName 与空 variables（resolver 侧反查补全）
        assertThat(parsed.payload().path("definitionName").asString("x")).isEmpty();
        assertThat(parsed.payload().path("variables").isObject()).isTrue();
        assertThat(parsed.payload().path("variables").size()).isZero();
    }

    @Test
    void resolverRendersNameParamsAndToolGuidance() {
        Map<String, String> vars = new LinkedHashMap<>();
        vars.put("topic", "小说");
        vars.put("chapter", "3");
        JsonNode payload = SlashTokenEncoder
                .parseToken(WorkflowRunToken.buildToken("wf_abc", "日更写作", vars)).payload();

        String text = new WorkflowRunSlashResolver(store).resolveSubmissionText(payload);

        assertThat(text).isNotBlank();
        assertThat(text).contains("《日更写作》");
        assertThat(text).contains("definitionId=wf_abc");
        // 替换文本内层参数用「, 」分隔（与 token summary 的「; 」口径区分）
        assertThat(text).contains("参数: topic=小说, chapter=3");
        assertThat(text).contains("workflow_run");
        assertThat(text).contains("variables 原样作为 variables 参数传入");
    }

    @Test
    void resolverBackfillsMissingNameFromStore() {
        Definition saved = store.saveDefinition(WS, minimalDef("反查名称"));
        JsonNode payload = SlashTokenEncoder
                .parseToken(WorkflowRunToken.buildToken(saved.id(), "", Map.of())).payload();

        String text = new WorkflowRunSlashResolver(store).resolveSubmissionText(payload);

        assertThat(text).contains("《反查名称》").contains("definitionId=" + saved.id())
                .contains("无参数");
    }

    @Test
    void resolverFallsBackToIdWhenStoreMisses() {
        JsonNode payload = SlashTokenEncoder
                .parseToken(WorkflowRunToken.buildToken("wf_gone", "", Map.of())).payload();
        assertThat(new WorkflowRunSlashResolver(store).resolveSubmissionText(payload))
                .contains("《wf_gone》");
    }

    @Test
    void resolverToleratesNullOrBlankPayload() {
        WorkflowRunSlashResolver resolver = new WorkflowRunSlashResolver(store);
        assertThat(resolver.resolveSubmissionText(null))
                .isNotBlank().contains("未知工作流").contains("workflow_run");
        assertThat(resolver.resolveSubmissionText(Json.obj()))
                .isNotBlank().contains("未知工作流").contains("无参数");
    }

    /** 最小合法定义（仅 start 节点，经 store 校验落盘）。 */
    private static Definition minimalDef(String name) {
        return new Definition(Definition.SCHEMA_VERSION, null, name, null, WS, null, null,
                null, null, null,
                List.of(new NodeDef("n1", NodeDef.TYPE_START, "开始", 0.0, 0.0,
                null, null, null, null, null, null, null, null)),
                List.of());
    }
}
