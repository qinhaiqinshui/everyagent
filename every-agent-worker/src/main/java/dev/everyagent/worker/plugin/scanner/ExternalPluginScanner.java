package dev.everyagent.worker.plugin.scanner;

import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * 外部插件扫描器 —— 扫描外部插件安装目录 {@code ~/.everyagent/plugins/}
 * (可通过 {@code worker.plugins-dir} 配置覆盖)。
 *
 * <p>扫描规则:根目录下所有一级子目录,凡根含 {@code plugin.json} 的即纳入,
 * source={@code "external"}。jar 产物约定放在各插件目录的 {@code lib/} 下,
 * 由 PluginLoader 统一加载时解析。
 *
 * <p>根目录不存在时 INFO 日志并返回空列表,不报错。
 */
@Component
public class ExternalPluginScanner implements PluginScanner {

    private static final Logger log = LoggerFactory.getLogger(ExternalPluginScanner.class);

    private final WorkerProperties props;

    public ExternalPluginScanner(WorkerProperties props) {
        this.props = props;
    }

    @Override
    public List<ScannedPlugin> scan() {
        Path root = props.resolvePluginsDir();
        if (!Files.isDirectory(root)) {
            log.info("[plugins-external] 外部插件目录不存在,跳过扫描: {}", root);
            return List.of();
        }

        List<ScannedPlugin> result;
        try (Stream<Path> dirs = Files.list(root)) {
            result = dirs.filter(Files::isDirectory)
                    .filter(dir -> Files.isRegularFile(dir.resolve("plugin.json")))
                    .sorted()
                    .map(dir -> new ScannedPlugin(dir, "external"))
                    .toList();
        } catch (IOException e) {
            log.warn("[plugins-external] 扫描外部插件目录失败: {}", root, e);
            return List.of();
        }

        log.info("[plugins-external] 扫描到 {} 个外部插件", result.size());
        return result;
    }
}
