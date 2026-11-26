package dev.everyagent.plugin.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.workflow.model.Definition;
import dev.everyagent.plugin.workflow.model.EdgeDef;
import dev.everyagent.plugin.workflow.model.InstanceMeta;
import dev.everyagent.plugin.workflow.model.NodeDef;
import dev.everyagent.plugin.workflow.search.WorkflowSearchProvider;
import dev.everyagent.plugin.workflow.store.WorkflowStore;

/**
 * WorkflowSearchProvider：定义结构化字段搜索（名称/描述/节点输入）、实例过程内容搜索
 * （events 干净文本抽取 + 工作流名）、状态过滤、kinds 过滤、上限触顶、空 pattern、
 * 截窗 snippet。不依赖 worker 类（FakeWorkspaces 桩实现 plugin-api 接口）。
 */
class WorkflowSearchProviderTest {

    @TempDir
    Path home;

    FakeWorkspaces workspaces;
    WorkflowStore store;
    WorkflowSearchProvider provider;

    static final String WS_A = "ws_aaa";

    @BeforeEach
    void setUp() {
        workspaces = new FakeWorkspaces(home);
        workspaces.register(home.resolve("projA"), WS_A);
        store = new WorkflowStore(workspaces);
        provider = new WorkflowSearchProvider(store);
    }

    /** 合法定义：start + ai（agent=writer，input 可带搜索词）+ edge。 */
    private Definition def(String name, String description, String input) {
        NodeDef n1 = new NodeDef("n1", "start", "n1", 0.0, 0.0, null, null, null, null, null, null, null, null);
        NodeDef n2 = new NodeDef("n2", "ai", "写作节点", 0.0, 0.0, null, null, null, "writer", null, input, null, null);
        EdgeDef e1 = new EdgeDef("e1", "n1", "n2", null);
        return new Definition(null, null, name, description, null, null, null,
                null, null, null, null, List.of(n1, n2), List.of(e1));
    }

    private Definition save(String name, String description, String input) {
        return store.saveDefinition(WS_A, def(name, description, input));
    }

    private SearchProvider.SearchRequest req(String pattern, Map<String, Object> filters,
            List<String> kinds) {
        return new SearchProvider.SearchRequest(WS_A, home.resolve("projA"), pattern, filters, kinds);
    }

    // ── 定义搜索 ──

    @Test
    void definitionDescriptionHit() {
        Definition def = save("发版流水线", "每周自动构建并发布", "写周报");
        SearchProvider.SearchResult r = provider.search(req("自动构建", null, null));
        assertThat(r.items()).hasSize(1);
        SearchProvider.SearchResultItem item = r.items().get(0);
        assertThat(item.kind()).isEqualTo(WorkflowSearchProvider.KIND_DEFINITION);
        assertThat(item.fields().get("definitionId")).isEqualTo(def.id());
        assertThat(item.fields().get("name")).isEqualTo("发版流水线");
        assertThat(item.fields().get("field")).isEqualTo("描述");
        assertThat(item.fields().get("matchText")).isEqualTo("自动构建");
        assertThat(r.truncated()).isFalse();
    }

    @Test
    void definitionNodeInputHit() {
        save("扫描流程", null, "扫描代码并生成报告");
        SearchProvider.SearchResult r = provider.search(req("扫描代码", null, null));
        assertThat(r.items()).hasSize(1);
        assertThat(String.valueOf(r.items().get(0).fields().get("field"))).contains("节点");
        assertThat(String.valueOf(r.items().get(0).fields().get("line"))).contains("扫描代码");
    }

    @Test
    void definitionNameHit() {
        save("代码评审流水线", "评审助手", "看代码");
        SearchProvider.SearchResult r = provider.search(req("评审", null, null));
        assertThat(r.items()).isNotEmpty();
        // 名称命中 + 节点名/描述也可能命中；至少一条 field=名称
        assertThat(r.items().stream().anyMatch(i -> "名称".equals(i.fields().get("field")))).isTrue();
    }

    // ── 实例搜索 ──

    @Test
    void instanceEventHit() {
        Definition def = save("发版流水线", "每周自动构建并发布", "写周报");
        InstanceMeta meta = store.createInstance(def, Map.of());
        store.appendEvent(WS_A, meta.id(), "node.output",
                Map.of("nodeId", "n2", "runNo", 1, "outputPreview", "构建成功耗时 42 秒"));

        SearchProvider.SearchResult r = provider.search(req("构建成功", null, null));
        assertThat(r.items()).hasSize(1);
        SearchProvider.SearchResultItem item = r.items().get(0);
        assertThat(item.kind()).isEqualTo(WorkflowSearchProvider.KIND_INSTANCE);
        assertThat(item.fields().get("instanceId")).isEqualTo(meta.id());
        assertThat(item.fields().get("definitionId")).isEqualTo(def.id());
        assertThat(item.fields().get("status")).isEqualTo("created");
        assertThat(item.fields().get("field")).isEqualTo("节点输出");
        assertThat(String.valueOf(item.fields().get("line"))).contains("构建成功");
    }

    @Test
    void instanceMetadataNameHit() {
        Definition def = save("部署流水线", null, "发版");
        store.createInstance(def, Map.of());
        SearchProvider.SearchResult r = provider.search(req("部署流水线", null, null));
        assertThat(r.items().stream()
                .anyMatch(i -> "工作流名".equals(i.fields().get("field"))
                        && WorkflowSearchProvider.KIND_INSTANCE.equals(i.kind()))).isTrue();
    }

    @Test
    void instanceStatusFilter() {
        Definition def = save("发版流水线", "每周自动构建并发布", "写周报");
        store.createInstance(def, Map.of());
        // 状态过滤 created：实例命中（工作流名不含词，但 wf.created 无正文——此断言验证过滤不误杀）
        // 注意：本定义与实例均无「自动构建」实例正文命中，改用真实事件验证状态过滤链路
        store.appendEvent(WS_A, store.listInstances(WS_A, null).get(0).id(), "node.output",
                Map.of("outputPreview", "自动构建完成"));
        assertThat(provider.search(req("自动构建",
                Map.of("workflow-instance.status", "created"),
                List.of(WorkflowSearchProvider.KIND_INSTANCE))).items()).isNotEmpty();
        // 过滤 done：实例实际 created → 全部被跳过
        assertThat(provider.search(req("自动构建",
                Map.of("workflow-instance.status", "done"),
                List.of(WorkflowSearchProvider.KIND_INSTANCE))).items()).isEmpty();
    }

    // ── kinds / pattern / 上限 / snippet ──

    @Test
    void kindsFilterLimitsScope() {
        Definition def = save("发版流水线", "每周自动构建并发布", "写周报");
        store.createInstance(def, Map.of());
        store.appendEvent(WS_A, store.listInstances(WS_A, null).get(0).id(), "node.output",
                Map.of("outputPreview", "自动构建日志"));
        SearchProvider.SearchResult r = provider.search(req("自动构建", null,
                List.of(WorkflowSearchProvider.KIND_DEFINITION)));
        assertThat(r.items()).isNotEmpty();
        assertThat(r.items()).allMatch(i -> WorkflowSearchProvider.KIND_DEFINITION.equals(i.kind()));
    }

    @Test
    void blankPatternAndUnregisteredWorkspaceReturnEmpty() {
        assertThat(provider.search(req("", null, null)).items()).isEmpty();
        assertThat(provider.search(req("  ", null, null)).items()).isEmpty();
        SearchProvider.SearchRequest noWs = new SearchProvider.SearchRequest(
                null, home.resolve("projA"), "词", null, null);
        assertThat(provider.search(noWs).items()).isEmpty();
    }

    @Test
    void truncatedWhenCapReached() {
        WorkflowSearchProvider small = new WorkflowSearchProvider(store, 2);
        for (int i = 0; i < 3; i++) {
            save("流程" + i, "包含关键词埋点", "正文" + i);
        }
        SearchProvider.SearchResult r = small.search(req("关键词", null,
                List.of(WorkflowSearchProvider.KIND_DEFINITION)));
        assertThat(r.items()).hasSize(2);
        assertThat(r.truncated()).isTrue();
    }

    @Test
    void longTextSnippetWindowedAroundMatch() {
        String prefix = "前".repeat(300);
        Definition d = save("长文流程", prefix + "关键词" + "后".repeat(300), "正文");
        SearchProvider.SearchResult r = provider.search(req("关键词", null,
                List.of(WorkflowSearchProvider.KIND_DEFINITION)));
        assertThat(r.items()).hasSize(1);
        String line = String.valueOf(r.items().get(0).fields().get("line"));
        assertThat(line).startsWith("…").endsWith("…");
        assertThat(line).contains("关键词");
        int matchIndex = ((Number) r.items().get(0).fields().get("matchIndex")).intValue();
        assertThat(matchIndex).isBetween(0, line.length());
        assertThat(d.id()).isNotBlank();
    }

    @Test
    void caseInsensitiveByDefaultAndWholeWordSupported() {
        save("大小写流程", "Contains DeployStep Marker", "正文");
        assertThat(provider.search(req("deploystep", null, null)).items()).isNotEmpty();
        assertThat(provider.search(req("DeployStep",
                Map.of("workflow-definition.caseSensitive", true), null)).items()).isNotEmpty();
        assertThat(provider.search(req("Deploy",
                Map.of("workflow-definition.wholeWord", true), null)).items()).isEmpty();
    }
}
