// ea: 插件入口类：activate() 里经 WorkerPluginContext 注册扩展点（15 个注册方法目录见类体内注释）
package {{package}};

import dev.everyagent.plugin.api.EveryAgentPlugin;
import dev.everyagent.plugin.api.WorkerPluginContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 放开下方 ToolProvider 最小示例时，把下面这些 import 一并放开
// （均来自 every-agent-plugin-api 及其传递依赖 spring-ai-model，无须额外依赖）：
// import dev.everyagent.plugin.api.spi.ToolContext;
// import dev.everyagent.plugin.api.spi.ToolProvider;
// import org.springframework.ai.support.ToolCallbacks;
// import org.springframework.ai.tool.ToolCallback;
// import java.util.List;

/**
 * {{pluginName}} —— 后端插件入口类。
 *
 * <p>本类由 worker 以独立 {@code URLClassLoader} 加载（非 Spring 托管 bean），
 * 因此<b>禁用一切 Spring 注解</b>（@Component / @Autowired / @Resource 等都不生效）；
 * 需要的宿主依赖一律在 {@link #activate} 里经 {@code ctx.services()} 或
 * {@code ctx.getService(Class)} 手工获取。plugin.json 的 main 字段指向本类全限定名：
 * {@code {{mainClass}}}。
 */
public class {{entryClass}} implements EveryAgentPlugin {

    private static final Logger log = LoggerFactory.getLogger({{entryClass}}.class);

    /** 插件 id，必须与 plugin.json 的 id 字段一致（仅允许小写字母 / 数字 / 连字符）。 */
    @Override
    public String id() {
        return "{{pluginId}}";
    }

    /**
     * 激活：在这里注册扩展点。单个插件激活抛异常只影响自己，不影响其他插件加载。
     *
     * @param ctx 插件上下文（注册方法 + 只读服务；辅助方法还有 pluginId() / pluginDir() /
     *            services() / config() / getService(Class)）
     */
    @Override
    public void activate(WorkerPluginContext ctx) throws Exception {
        log.info("[{{pluginId}}] 已激活（{}）", ctx.pluginDir());

        // ══════════ ctx.register* 全量目录（15 个）══════════
        //
        // ── SPI 注册（10 个，WorkerPluginContext）──
        // void registerToolProvider(ToolProvider provider)                        注册 AI 工具提供者，向 agent 注入工具
        // void registerAdvisorProvider(AdvisorProvider provider)                  注册 Advisor 链提供者（system prompt / 护栏等增强）
        // void registerSandboxProvider(SandboxProvider provider)                  注册沙箱后端提供者（命令执行的隔离环境）
        // void registerSearchProvider(SearchProvider provider)                    注册搜索后端
        // void registerAuthorizationHandler(AuthorizationHandler handler)         注册授权决议链节点（PermissionGate 用户授权）
        // void registerToolExecutionInterceptor(ToolExecutionInterceptor i)       注册工具执行拦截链节点（观测 / 改写工具调用）
        // void registerSkillContributor(SkillContributor contributor)             注册 skill 贡献者（向 system prompt 与 / 菜单贡献 skill）
        // void registerTokenEstimator(TokenEstimator estimator)                   注册 Token 估算器（替换内置实现）
        // void registerChatModelEnhancer(ChatModelEnhancer enhancer)              注册模型构建增强器（如模型池容灾）
        // void registerFileReferenceHandler(FileReferenceHandler handler)         注册文件引用处理器（按扩展名处理 @ 文件引用）
        //
        // ── 通用扩展注册（3 个，WorkerPluginContext）──
        // void registerRpcMethod(String method, RpcMethod handler)                注册 RPC 方法（方法名「域.动作」，如 "git.status"，经 RpcDispatcher 分发）
        // void registerSlashProvider(String id, SlashProvider provider)           注册 Slash 命令提供者（/ 菜单）
        // void registerSlashTokenResolver(SlashTokenResolver resolver)            注册 Slash token 提交解析器
        //
        // ── task 域注册（2 个，父接口 TaskPluginContext）──
        // void registerTaskAdmissionPolicy(TaskAdmissionPolicy policy)           注册任务准入策略（队列插件用）
        // void registerTaskLifecycleNode(TaskLifecycleNode node)                 注册任务生命周期链节点（任务流编排）

        // ── ToolProvider 最小示例（默认注释；连同文件头部注释掉的 import 一起放开即可编译）──
        // 接口真实签名（every-agent-plugin-api …/spi/ToolProvider.java）：
        //   String pluginId();
        //   List<ToolCallback> createTools(ToolContext ctx);      // ctx 含 subjectId / workspaceRoot / sandbox 等槽位
        //   default boolean appliesTo(ToolContext ctx) { return true; }   // 可选：按任务筛选是否生效
        //
        // ctx.registerToolProvider(new ToolProvider() {
        //     @Override
        //     public String pluginId() {
        //         return "{{pluginId}}";
        //     }
        //
        //     @Override
        //     public List<ToolCallback> createTools(ToolContext toolCtx) {
        //         // MyTools = 你自己的工具 POJO，方法上标 @Tool 注解；
        //         // ToolCallbacks.from(...) 是 Spring AI 2 自带的「注解 POJO → ToolCallback」转换，勿手搓循环
        //         return List.of(ToolCallbacks.from(new MyTools()));
        //     }
        // });
    }
}
