package dev.everyagent.worker.plugin.scanner;

import java.nio.file.Path;
import java.util.List;

/**
 * 插件扫描器接口(插件加载统一重构第 1 步)。
 *
 * <p>每个扫描器负责发现一类插件来源(内置源码目录、外部安装目录等),
 * 返回 {@link ScannedPlugin} 列表供后续 PluginLoader 统一加载。
 *
 * <p>后续步骤将让 PluginLoader 注入 {@code List<PluginScanner>} 统一调度;
 * 本接口为该重构的抽象入口,本步只定义接口 + 内置扫描器实现,不改 PluginLoader 逻辑。
 */
public interface PluginScanner {

    /**
     * 扫描插件,返回已发现的插件目录列表。
     *
     * @return 扫描到的插件列表(可能为空,不返回 null)
     */
    List<ScannedPlugin> scan();

    /**
     * 扫描到的插件描述。
     *
     * <p>{@code pluginDir} 指向插件根目录(含 plugin.json 或构建产物的目录);
     * {@code source} 标识插件来源,取值如 {@code "builtin"}(内置源码插件)、
     * {@code "external"}(外部安装插件)等,供 PluginLoader 区分加载策略。
     *
     * @param pluginDir 插件根目录绝对路径
     * @param source    插件来源标识
     */
    record ScannedPlugin(Path pluginDir, String source) {
    }
}
