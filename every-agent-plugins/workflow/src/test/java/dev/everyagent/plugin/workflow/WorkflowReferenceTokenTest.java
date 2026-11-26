// ea: JUnit5 单测：只依赖 every-agent-plugin-api，不依赖 worker（§14.9 红线）
package dev.everyagent.plugin.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.slash.WorkflowReferenceSlashResolver;
import dev.everyagent.plugin.workflow.slash.WorkflowReferenceToken;
import dev.everyagent.plugin.workflow.store.WorkflowStore;
import tools.jackson.databind.JsonNode;

/**
 * 「工作流引用」token 三件套：构造 → 编解码往返 → resolver 渲染（含定义名缺失经
 * store 反查补全、LEGACY workflow.run 兼容渲染）。payload 字段名
 * {@code definitionId/definitionName}（中性引用，无 variables）与前端 RunDialog / @
 * 引用组装的 payload 逐字一致（前端不注册提交 resolver，原串上行后由 worker 侧
 * SlashTokenResolveAdvisor 按 kind 分发到本 resolver）。
 */
class WorkflowReferenceTokenTest {

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
    void kindIsWorkflowReferenceAndLegacyRegistered() {
        assertThat(WorkflowReferenceToken.KIND).isEqualTo("workflow.reference");
        assertThat(WorkflowReferenceToken.LEGACY_KIND).isEqualTo("workflow.run");
        assertThat(new WorkflowReferenceSlashResolver(store, WorkflowReferenceToken.KIND).kind())
                .isEqualTo(WorkflowReferenceToken.KIND);
        assertThat(new WorkflowReferenceSlashResolver(store, WorkflowReferenceToken.LEGACY_KIND).kind())
                .isEqualTo(WorkflowReferenceToken.LEGACY_KIND);
    }

    @Test
    void buildTokenRoundTripsThroughEncoder() {
        String token = WorkflowReferenceToken.buildToken("wf_abc", "日更写作");

        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(token);
        assertThat(parsed).isNotNull();
        assertThat(parsed.kind()).isEqualTo("workflow.reference");
        // 中性引用 label（不再是「运行…」）
        assertThat(parsed.label()).isEqualTo("工作流「日更写作」");
        assertThat(parsed.summary()).isEqualTo("wf_abc");
        assertThat(parsed.payload().path("definitionId").asString("")).isEqualTo("wf_abc");
        assertThat(parsed.payload().path("definitionName").asString("")).isEqualTo("日更写作");
        // payload 不再携带 variables（参数写在 token 外的普通文字）
        assertThat(parsed.payload().path("variables").isMissingNode()).isTrue();
    }

    @Test
    void buildTokenFallsBackWhenNameAbsent() {
        String token = WorkflowReferenceToken.buildToken("wf_x", "");
        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(token);
        assertThat(parsed).isNotNull();
        // 定义名空 → label 退用 definitionId；summary=definitionId
        assertThat(parsed.label()).isEqualTo("工作流「wf_x」");
        assertThat(parsed.summary()).isEqualTo("wf_x");
        assertThat(parsed.payload().path("definitionName").asString("x")).isEmpty();
    }

    @Test
    void resolverRendersReferenceOnlyText() {
        JsonNode payload = SlashTokenEncoder
                .parseToken(WorkflowReferenceToken.buildToken("wf_abc", "日更写作")).payload();

        String text = new WorkflowReferenceSlashResolver(store, WorkflowReferenceToken.KIND)
                .resolveSubmissionText(payload);

        // 只留 工作流名 + definitionId；无参数渲染、无 workflow_run 工具引导
        assertThat(text).contains("[引用工作流]").contains("《日更写作》").contains("definitionId=wf_abc");
        assertThat(text).doesNotContain("参数").doesNotContain("workflow_run");
    }

    @Test
    void resolverBackfillsMissingNameFromStore() {
        Definition saved = store.saveDefinition(WS, minimalDef("反查名称"));
        JsonNode payload = SlashTokenEncoder
                .parseToken(WorkflowReferenceToken.buildToken(saved.id(), "")).payload();

        String text = new WorkflowReferenceSlashResolver(store, WorkflowReferenceToken.KIND)
                .resolveSubmissionText(payload);

        assertThat(text).contains("《反查名称》").contains("definitionId=" + saved.id());
    }

    @Test
    void resolverFallsBackToIdWhenStoreMisses() {
        JsonNode payload = SlashTokenEncoder
                .parseToken(WorkflowReferenceToken.buildToken("wf_gone", "")).payload();
        assertThat(new WorkflowReferenceSlashResolver(store, WorkflowReferenceToken.KIND)
                .resolveSubmissionText(payload)).contains("《wf_gone》");
    }

    @Test
    void resolverToleratesNullOrBlankPayload() {
        WorkflowReferenceSlashResolver resolver =
                new WorkflowReferenceSlashResolver(store, WorkflowReferenceToken.KIND);
        assertThat(resolver.resolveSubmissionText(null))
                .isNotBlank().contains("未知工作流");
        assertThat(resolver.resolveSubmissionText(Json.obj()))
                .isNotBlank().contains("未知工作流");
    }

    @Test
    void legacyRunPayloadRendersSameReferenceTextDroppingVariables() {
        // LEGACY workflow.run 兼容：旧 payload 带 variables → 同样的引用文本（variables 有意丢弃）
        JsonNode legacyPayload = Json.obj()
                .put("definitionId", "wf_old")
                .put("definitionName", "旧版流程")
                .set("variables", Json.toJson(Map.of("topic", "小说")));

        String text = new WorkflowReferenceSlashResolver(store, WorkflowReferenceToken.LEGACY_KIND)
                .resolveSubmissionText(legacyPayload);

        assertThat(text).contains("[引用工作流]").contains("《旧版流程》").contains("definitionId=wf_old");
        assertThat(text).doesNotContain("小说").doesNotContain("workflow_run");
    }

    /** 最小合法定义（仅 start 节点，经 store 校验落盘）。 */
    private static Definition minimalDef(String name) {
        return new Definition(Definition.SCHEMA_VERSION, null, name, null, WS, null, null,
                null, null, null, null,
                List.of(new NodeDef("n1", NodeDef.TYPE_START, "开始", 0.0, 0.0,
                null, null, null, null, null, null, null, null)),
                List.of());
    }
}
