package dev.everyagent.worker.plugin.loader;

import dev.everyagent.worker.config.WorkerProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 内置插件管理器。
 *
 * <p>内置插件不物化、不独立打包——随主 jar 编译打包，通过 Spring @Component
 * 自动注册到 SPI 注册表（BuiltInToolProviders / BuiltInAdvisorProviders / BuiltInSandboxProviders）。
 * 外部插件在 ~/.everyagent/plugins/ 目录，由 PluginLoader 加载。
 *
 * <p>本类仅负责提供插件目录根路径 + 确保目录存在。
 */
@Component
public class BuiltInPlugins {

    private static final Logger log = LoggerFactory.getLogger(BuiltInPlugins.class);

    private final Path pluginsRoot;

    public BuiltInPlugins(WorkerProperties props) {
        this.pluginsRoot = props.resolvePluginsDir();
    }

    @PostConstruct
    void init() {
        try {
            java.nio.file.Files.createDirectories(pluginsRoot);
        } catch (java.io.IOException e) {
            log.warn("[plugins] 创建插件目录失败: {}", pluginsRoot, e);
        }
    }

    /** 插件目录根（外部插件存放位置）。 */
    public Path getPluginsRoot() {
        return pluginsRoot;
    }

    /** 内置插件 id 列表（保留供冲突检查用，当前为空）。 */
    public List<String> builtInIds() {
        return List.of();
    }
}
