package dev.everyagent.plugin.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.plugin.api.spi.SuggestionProvider;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EdgeDef;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.search.WorkflowDefinitionSuggestProvider;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

/**
 * 「工作流定义名」@ 建议源：包含/子序列命中、大小写不敏感、上限 5、空参返回空、
 * 元数据袋携带 definitionId/definitionName。不依赖 worker 类（FakeWorkspaces 桩）。
 */
class WorkflowDefinitionSuggestProviderTest {

    @TempDir
    Path home;

    FakeWorkspaces workspaces;
    WorkflowStore store;
    WorkflowDefinitionSuggestProvider provider;

    static final String WS = "ws_sug";

    @BeforeEach
    void setUp() {
        workspaces = new FakeWorkspaces(home);
        workspaces.register(home.resolve("projA"), WS);
        store = new WorkflowStore(workspaces);
        provider = new WorkflowDefinitionSuggestProvider(store);
    }

    private Definition def(String name) {
        NodeDef n1 = new NodeDef("n1", "start", "n1", 0.0, 0.0, null, null, null, null, null, null, null, null);
        NodeDef n2 = new NodeDef("n2", "ai", "写作节点", 0.0, 0.0, null, null, null, "writer", null, "正文", null, null);
        EdgeDef e1 = new EdgeDef("e1", "n1", "n2", null);
        return new Definition(null, null, name, null, null, null, null,
                null, null, null, null, List.of(n1, n2), List.of(e1));
    }

    private SuggestionProvider.SuggestRequest req(String query) {
        return new SuggestionProvider.SuggestRequest(WS, Path.of("."), query, ".");
    }

    @Test
    void nameHitCarriesDefinitionIdAndNameInMetadata() {
        Definition saved = store.saveDefinition(WS, def("日更写作流水线"));

        List<SuggestionProvider.Suggestion> list = provider.suggest(req("日更"));

        assertThat(list).hasSize(1);
        var s = list.get(0);
        assertThat(s.path()).isEqualTo("workflows/" + saved.id());
        assertThat(s.kind()).isEqualTo(WorkflowDefinitionSuggestProvider.KIND);
        assertThat(s.metadata().get("definitionId")).isEqualTo(saved.id());
        assertThat(s.metadata().get("definitionName")).isEqualTo("日更写作流水线");
    }

    @Test
    void subsequenceHitMatchesLoosely() {
        store.saveDefinition(WS, def("周报生成器"));
        assertThat(provider.suggest(req("周生"))).hasSize(1);
    }

    @Test
    void caseInsensitiveEnglishName() {
        store.saveDefinition(WS, def("Deploy Pipeline"));
        assertThat(provider.suggest(req("deploy pipeline"))).hasSize(1);
    }

    @Test
    void cappedAtFiveSuggestions() {
        for (int i = 0; i < 7; i++) {
            store.saveDefinition(WS, def("关键词流程" + i));
        }
        assertThat(provider.suggest(req("关键词"))).hasSize(5);
    }

    @Test
    void blankQueryOrWorkspaceReturnsEmpty() {
        store.saveDefinition(WS, def("部署流程"));
        assertThat(provider.suggest(req(""))).isEmpty();
        assertThat(provider.suggest(
                new SuggestionProvider.SuggestRequest(null, Path.of("."), "部署", "."))).isEmpty();
    }

    @Test
    void otherWorkspaceDefinitionsNotSuggested() {
        workspaces.register(home.resolve("projB"), "ws_bbb");
        store.saveDefinition(WS, def("本工作区流程"));
        store.saveDefinition("ws_bbb", def("别家流程"));
        var list = provider.suggest(req("流程"));
        assertThat(list).hasSize(1);
        assertThat(list.get(0).metadata().get("definitionName")).isEqualTo("本工作区流程");
    }
}
