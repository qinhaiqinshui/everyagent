// ea: JUnit5 冒烟测试：只依赖 every-agent-plugin-api，不依赖 worker（§14.9 红线）
package dev.everyagent.plugin.workflow;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.PluginConfig;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.event.EventEgressFilter;
import dev.everyagent.plugin.api.event.RoundEgressFilter;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.model.ChatModelEnhancer;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.rpc.RpcMethod;
import dev.everyagent.plugin.api.slash.SlashProvider;
import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.FileReadHandler;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.plugin.api.task.TaskAdmissionPolicy;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;
import dev.everyagent.plugin.workflow.slash.WorkflowReferenceToken;
import dev.everyagent.plugin.workflow.search.WorkflowSearchProvider;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WorkflowPlugin 冒烟测试。
 *
 * <p>§14.9 红线：插件测试<b>不得依赖 every-agent-worker</b> —— 本测试只依赖
 * every-agent-plugin-api（编译期接口）与 spring-boot-starter-test 传递的 JUnit 5 +
 * AssertJ，不启动 worker、不需要 Spring 上下文。activate() 经
 * {@link RecordingPluginContext} 录制式桩验证注册接线（slash token resolver /
 * hidden 工具 provider / workflow.* RPC 面）。
 */
class WorkflowPluginSmokeTest {

    @Test
    void entryClassInstantiableAndIdMatchesManifest() {
        EveryAgentPlugin plugin = new WorkflowPlugin();
        assertEquals("workflow", plugin.id(), "id() 必须与 plugin.json 的 id 字段一致");
    }

    @Test
    void idMatchesScaffoldNamingRule() {
        // 与脚手架 create-everyagent-plugin 同一条校验规则：^[a-z0-9][a-z0-9-]{1,38}$
        assertTrue("workflow".matches("[a-z0-9][a-z0-9-]{1,38}"),
                "id 只允许小写字母/数字/连字符，总长 2~39，首尾须为字母或数字");
    }

    @Test
    void activateRegistersSlashResolverToolsAndRpc(@TempDir Path home) {
        RecordingPluginContext ctx = new RecordingPluginContext(home);
        new WorkflowPlugin().activate(ctx);

        // 「工作流引用」token 提交解析接线：kind=workflow.reference + 兼容 LEGACY
        // workflow.run 各一份（任务页运行按钮 / @ 引用的胶囊原串上行 → worker 复用的
        // SlashTokenResolveAdvisor 按 kind 分发到对应 resolver）
        assertThat(ctx.slashTokenResolvers).hasSize(2);
        assertThat(ctx.slashTokenResolvers)
                .extracting(SlashTokenResolver::kind)
                .containsExactlyInAnyOrder(
                        WorkflowReferenceToken.KIND, WorkflowReferenceToken.LEGACY_KIND);

        // 统一搜索后端接线：1 个 provider，声明 workflow-definition / workflow-instance 两 kind
        assertThat(ctx.searchProviders).hasSize(1);
        assertThat(ctx.searchProviders.get(0)).isInstanceOf(WorkflowSearchProvider.class);
        assertThat(ctx.searchProviders.get(0).kinds())
                .containsExactlyInAnyOrder("workflow-definition", "workflow-instance");

        // 既有注册面不回归：hidden 工具 provider + workflow.* RPC 方法
        assertThat(ctx.toolProviders).hasSize(1);
        assertThat(ctx.rpcMethods).isNotEmpty();
    }

    // ── 录制式 WorkerPluginContext 桩（activate 路径只用到 services.workspaces/stream 与注册方法） ──

    private static final class RecordingPluginContext implements WorkerPluginContext {

        final List<ToolProvider> toolProviders = new CopyOnWriteArrayList<>();
        final List<SlashTokenResolver> slashTokenResolvers = new CopyOnWriteArrayList<>();
        final List<String> rpcMethods = new CopyOnWriteArrayList<>();
        final List<SearchProvider> searchProviders = new CopyOnWriteArrayList<>();
        private final WorkerServices services;

        RecordingPluginContext(Path home) {
            this.services = new MinimalWorkerServices(new FakeWorkspaces(home));
        }

        @Override
        public String pluginId() {
            return "workflow-test";
        }

        @Override
        public Path pluginDir() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerToolProvider(ToolProvider provider) {
            toolProviders.add(provider);
        }

        @Override
        public void registerAdvisorProvider(AdvisorProvider provider) {
        }

        @Override
        public void registerSandboxProvider(SandboxProvider provider) {
        }

        @Override
        public void registerSearchProvider(SearchProvider provider) {
            searchProviders.add(provider);
        }

        @Override
        public void registerAuthorizationHandler(AuthorizationHandler handler) {
        }

        @Override
        public void registerToolExecutionInterceptor(ToolExecutionInterceptor interceptor) {
        }

        @Override
        public void registerTokenEstimator(TokenEstimator estimator) {
        }

        @Override
        public void registerChatModelEnhancer(ChatModelEnhancer enhancer) {
        }

        @Override
        public void registerFileReadHandler(FileReadHandler handler) {
        }

        @Override
        public void registerEventEgressFilter(EventEgressFilter filter) {
        }

        @Override
        public void registerRoundEgressFilter(RoundEgressFilter filter) {
        }

        @Override
        public void registerRpcMethod(String method, RpcMethod handler) {
            rpcMethods.add(method);
        }

        @Override
        public void registerSlashProvider(String id, SlashProvider provider) {
        }

        @Override
        public void registerSlashTokenResolver(SlashTokenResolver resolver) {
            slashTokenResolvers.add(resolver);
        }

        @Override
        public WorkerServices services() {
            return services;
        }

        @Override
        public PluginConfig config() {
            return null;
        }

        @Override
        public <T> T getService(Class<T> type) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void registerTaskAdmissionPolicy(TaskAdmissionPolicy policy) {
        }

        @Override
        public void registerTaskLifecycleNode(TaskLifecycleNode node) {
        }
    }

    /** activate 路径最小服务桩：workspaces（空临时目录）+ no-op stream，其余不支持。 */
    private static final class MinimalWorkerServices implements WorkerServices {

        private final FakeWorkspaces workspaces;

        MinimalWorkerServices(FakeWorkspaces workspaces) {
            this.workspaces = workspaces;
        }

        @Override
        public dev.everyagent.plugin.api.spi.SandboxBackend sandbox() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.spi.NativeExec nativeExec() {
            throw new UnsupportedOperationException();
        }

        @Override
        public FakeWorkspaces workspaces() {
            return workspaces;
        }

        @Override
        public TokenEstimator tokenEstimator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.slash.SlashTokenService slashTokens() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.interaction.InteractionService interaction() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.config.WorkerConfig config() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.spi.IdGenerator ids() {
            throw new UnsupportedOperationException();
        }

        @Override
        public StreamEmitter stream() {
            return new StreamEmitter() {
                @Override
                public void fanout(Function<String, String> channelNamer, String event, Long seq,
                        JsonNode payload, JsonNode ext) {
                    // no-op：空目录扫描不产生变更/实例事件，监听器不会被触发
                }

                @Override
                public String workerId() {
                    return "wk_test";
                }
            };
        }

        @Override
        public Path dataDirOf(String subjectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.model.EventEmitter emitterOf(String subjectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addRoundClosedListener(dev.everyagent.plugin.api.task.RoundClosedListener listener) {
        }

        @Override
        public void addFileWriteListener(dev.everyagent.plugin.api.spi.FileWriteListener listener) {
        }

        @Override
        public String toSandboxPath(Path hostPath) {
            return hostPath.toString();
        }

        @Override
        public dev.everyagent.plugin.api.agent.AgentFactory agentFactoryFor(
                dev.everyagent.plugin.api.execution.ExecContext exec) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<dev.everyagent.plugin.api.model.ModelConfig> modelConfigs() {
            return List.of();
        }

        @Override
        public dev.everyagent.plugin.api.task.TaskService task() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.task.TaskStoreService store() {
            throw new UnsupportedOperationException();
        }
    }
}

