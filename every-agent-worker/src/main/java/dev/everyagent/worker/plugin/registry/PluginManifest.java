package dev.everyagent.worker.plugin.registry;

import java.nio.file.Path;

/**
 * 插件清单（完整目录条目）。
 *
 * <p>由 {@link PluginRegistry} 扫描 classpath 下的 {@code plugin.json}
 * 与外部插件目录后聚合而来，供 {@code plugin.list} RPC 返回完整目录。
 *
 * @param id          插件唯一标识
 * @param name        显示名称
 * @param version     版本号
 * @param description 描述
 * @param author      作者
 * @param main        Java 入口类全限定名（无则为纯 web/声明式插件）
 * @param webMain     前端入口文件路径（如 {@code web/index.ts}，无则该插件无前端入口）
 * @param source      来源：{@code builtin}（随主包打包）/ {@code external}（外部插件目录）
 * @param pluginDir   插件根目录绝对路径（内置插件源码目录或外部插件安装目录），
 *                    供 {@code plugin.webSource} RPC 读取文件用
 */
public record PluginManifest(String id, String name, String version, String description,
                             String author, String main, String webMain, String source,
                             Path pluginDir) {
}
