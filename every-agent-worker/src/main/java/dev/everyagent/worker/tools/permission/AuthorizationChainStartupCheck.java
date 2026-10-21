package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

/**
 * 授权决议链启动断言(fail-fast,§7.8 fail-closed 收口的装配期防线):
 * 核心人工授权节点({@link HumanAuthorizationHandler})未注册时 worker 启动直接失败。
 *
 * <p>决议链链尾已按 fail-closed 兜底拒绝(「无授权决议者，默认拒绝」,见
 * {@link GrantRegistry}),但那是运行期静默防线——若人工弹窗节点缺失,全部越权请求
 * 会在无人知情的情况下被拒,表现为「AI 说它没权限但从不弹窗」。装配错误应在启动期
 * 显式暴露,而不是等第一条越权访问被拒后才被发现。
 *
 * <p>时序:实现 {@link SmartInitializingSingleton}——所有单例(含内置节点的
 * {@code @PostConstruct} 自注册、{@code PluginLoader} 在 {@code @PostConstruct}
 * 激活的外部插件 handler)初始化完成后恰好回调一次,此时看到的就是最终装配态;
 * 抛出 {@link IllegalStateException} 即中止 Spring 启动(worker 起不来)。
 * 只做启动期断言,不阻止运行期反注册(运行期缺口由链尾兜底拒绝收口)。
 */
@Component
public class AuthorizationChainStartupCheck implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationChainStartupCheck.class);

    private final AuthorizationHandlerRegistry authHandlerRegistry;

    public AuthorizationChainStartupCheck(AuthorizationHandlerRegistry authHandlerRegistry) {
        this.authHandlerRegistry = authHandlerRegistry;
    }

    @Override
    public void afterSingletonsInstantiated() {
        boolean present = authHandlerRegistry.sorted().stream()
                .map(AuthorizationHandler::id)
                .anyMatch(HumanAuthorizationHandler.ID::equals);
        if (!present) {
            throw new IllegalStateException("授权决议链缺少核心人工授权节点 " + HumanAuthorizationHandler.ID
                    + ",worker 拒绝启动:无授权决议者=默认拒绝,人工弹窗缺失会让全部越权请求被静默拒绝"
                    + "(请检查组件扫描/节点自注册是否被破坏)");
        }
        log.info("[gate] 授权决议链装配校验通过:人工授权节点 {} 已注册", HumanAuthorizationHandler.ID);
    }
}
