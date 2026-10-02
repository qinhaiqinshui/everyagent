package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SandboxProvider.SandboxConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * SandboxProvider SPI 注册表。
 *
 * <p>核心改造点 C3：OsSandbox 从此注册表选择沙箱后端。
 * 注册进来的都有效，查询直接返回全量。
 *
 * <p><b>代次（{@link #generation()}）</b>：每次注册/注销自增。消费方（{@code OsSandbox}）按代次
 * 做<b>惰性解析</b>——代次未变就复用已解析结果（含「无可用后端」这一结论），代次变了才重新
 * {@link #select}。原因：插件都在 {@code PluginLoader} 的 {@code @PostConstruct} 里注册，而
 * {@code PluginLoader → WorkerServices → OsSandbox} 的构造依赖链决定了 OsSandbox 必然先于插件
 * 激活完成初始化，一次性定论会永远退化成 DIRECT。见架构文档 §7.10「后端选择时机」。
 */
@Component
public class SandboxProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(SandboxProviderRegistry.class);

    private final List<SandboxProvider> providers = new CopyOnWriteArrayList<>();

    /** 注册表代次：每次结构变化（注册/注销）自增。 */
    private final AtomicLong generation = new AtomicLong();

    public void register(SandboxProvider provider) {
        providers.add(provider);
        log.info("[sandbox-registry] 注册 SPI 后端 id={} priority={}（现 {} 个候选，代次 {}）",
                provider.id(), provider.priority(), providers.size(), generation.incrementAndGet());
    }

    public void unregister(SandboxProvider provider) {
        if (providers.remove(provider)) {
            log.info("[sandbox-registry] 注销 SPI 后端 id={}（现 {} 个候选，代次 {}）",
                    provider.id(), providers.size(), generation.incrementAndGet());
        }
    }

    public List<SandboxProvider> getProviders() {
        return List.copyOf(providers);
    }

    /** 当前代次（结构每变化一次自增 1），供消费方判断「是否需要重新选择」。 */
    public long generation() {
        return generation.get();
    }

    /**
     * 候选清单描述（{@code id(priority)} 列表，<b>不调 isAvailable()</b>）。
     *
     * <p>供启动期日志使用：可用性探测（如 wsl 发行版 probe/autoImport）与
     * {@code create()}（如 codex 的 UAC setup）都不该在真正选择前发生。
     */
    public String describeProviders() {
        if (providers.isEmpty()) {
            return "（空：尚无沙箱插件注册）";
        }
        return providers.stream()
                .sorted(Comparator.comparingInt(SandboxProvider::priority).reversed())
                .map(p -> p.id() + "(" + p.priority() + ")")
                .collect(Collectors.joining(", "));
    }

    /**
     * 选择沙箱后端：显式 {@code type} 优先精确命中，否则 auto 取可用者中 priority 最高。
     *
     * <p>探测与创建分离：先用 {@code isAvailable()} 过滤，只对胜出者调 {@code create()}，
     * 故「胜出才付的代价」（codex setup 弹 UAC）不会被无谓触发。
     *
     * @return 胜出的后端；无可用后端返回 {@code null}（调用方退化为 DIRECT）
     */
    public SandboxBackend select(SandboxConfig config) {
        if (!config.enabled() || "none".equalsIgnoreCase(config.type())) {
            log.info("[sandbox-registry] 沙箱未启用或 type=none，不选择后端（enabled={} type={}）",
                    config.enabled(), config.type());
            return null;
        }
        if (providers.isEmpty()) {
            log.warn("[sandbox-registry] 注册表为空，无 SPI 后端可选（沙箱插件未加载？）→ 退化 DIRECT");
            return null;
        }

        if (config.type() != null && !config.type().isBlank()
                && !"auto".equalsIgnoreCase(config.type())) {
            for (SandboxProvider p : providers) {
                if (p.id().equalsIgnoreCase(config.type())) {
                    if (p.isAvailable()) {
                        log.info("[sandbox-registry] 显式 type={} 命中且可用 → 采用", p.id());
                        return p.create(config);
                    }
                    log.warn("[sandbox-registry] 显式 type={} 已注册但 isAvailable()=false，转 auto 选择",
                            config.type());
                    break;
                }
            }
            log.warn("[sandbox-registry] 显式 type={} 无对应 provider（候选 {}），转 auto 选择",
                    config.type(), describeProviders());
        }

        List<String> unavailable = providers.stream()
                .filter(p -> !p.isAvailable())
                .map(p -> p.id() + "(" + p.priority() + ")")
                .toList();
        return providers.stream()
                .filter(SandboxProvider::isAvailable)
                .max(Comparator.comparingInt(SandboxProvider::priority))
                .map(p -> {
                    log.info("[sandbox-registry] auto 选中 id={} priority={}（候选 {}；不可用 {}）",
                            p.id(), p.priority(), describeProviders(),
                            unavailable.isEmpty() ? "无" : String.join(", ", unavailable));
                    return p.create(config);
                })
                .orElseGet(() -> {
                    log.warn("[sandbox-registry] 全部候选 isAvailable()=false: {} → 退化 DIRECT",
                            String.join(", ", unavailable));
                    return null;
                });
    }
}
