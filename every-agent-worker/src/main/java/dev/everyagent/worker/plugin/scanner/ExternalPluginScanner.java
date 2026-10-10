package dev.everyagent.worker.plugin.scanner;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 外部插件扫描器 —— 扫描外部插件安装目录 {@code ~/.everyagent/plugins/}
 * (可通过 {@code worker.plugins-dir} 配置覆盖)。
 *
 * <p>扫描规则(与内置扫描器对齐 enabled 语义):根目录下所有一级子目录,
 * 凡根含 {@code plugin.json} 且 {@code enabled} 不为 false 的即纳入,
 * source={@code "external"}。{@code enabled=false} 的整目录跳过不加载
 * (外部插件「装上但默认禁用」即靠它声明);jar 产物约定放在各插件目录的
 * {@code lib/} 下,由 PluginLoader 统一加载时解析。
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

        List<Path> subDirs;
        try (Stream<Path> dirs = Files.list(root)) {
            subDirs = dirs.filter(Files::isDirectory)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[plugins-external] 扫描外部插件目录失败: {}", root, e);
            return List.of();
        }

        List<ScannedPlugin> result = new ArrayList<>();
        for (Path dir : subDirs) {
            Path manifest = dir.resolve("plugin.json");
            if (!Files.isRegularFile(manifest)) {
                // 无 plugin.json 的目录不是插件,跳过
                continue;
            }
            // 与内置扫描器同语义:enabled=false 整目录跳过,不进扫描结果(也不加载)。
            if (!isEnabled(manifest)) {
                log.info("[plugins-external] 插件已禁用(enabled=false),跳过: {}", dir.getFileName());
                continue;
            }
            result.add(new ScannedPlugin(dir, "external"));
        }

        log.info("[plugins-external] 扫描到 {} 个外部插件", result.size());
        return result;
    }

    /**
     * 检查 plugin.json 中的 {@code enabled} 字段(与 {@link BuiltInPluginScanner#isEnabled} 同语义)。
     *
     * <p>缺省(无该字段)视为 enabled=true。解析失败也视为 true(宽容,不因格式错误阻止加载)。
     *
     * @param manifestPath plugin.json 路径
     * @return true = 已启用或缺省;false = 显式 enabled=false
     */
    private static boolean isEnabled(Path manifestPath) {
        try {
            JsonNode json = Json.parse(Files.readString(manifestPath));
            // asBoolean(true): 字段缺省时返回 true
            return json.path("enabled").asBoolean(true);
        } catch (Exception e) {
            log.warn("[plugins-external] 解析 plugin.json 失败,视为已启用: {}", manifestPath, e);
            return true;
        }
    }
}
