package dev.everyagent.worker.plugin.loader;

import dev.everyagent.worker.config.WorkerProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * 内置插件物化器 —— 对标 {@link dev.everyagent.worker.skill.BuiltInSkills#materialize()}。
 *
 * <p>与 skill 同构方案（方案A）：jar 随 worker 打包在 classpath，启动时物化解压到
 * {@code ~/.everyagent/plugins/<id>/}，外部插件也在同目录，冲突内置优先。
 *
 * <p>物化幂等：目标已存在且 jar 大小一致则跳过；不一致（worker 升级）则覆盖。
 * 失败仅 WARN 不阻断启动（与 skill 一致）。
 *
 * <p>阶段二：内置插件清单暂时为空（阶段三才实际拆分内置功能为独立 jar），
 * 但物化机制先就绪——后续只需往 BUILT_IN_PLUGINS 列表加入 id，并在
 * {@code src/main/resources/plugins/<id>/} 放入对应 jar + plugin.json 即可。
 */
@Component
public class BuiltInPlugins {

    private static final Logger log = LoggerFactory.getLogger(BuiltInPlugins.class);

    /** classpath 插件资源前缀：plugins/<id>/ */
    private static final String RESOURCE_PREFIX = "plugins/";

    /**
     * 内置插件清单（硬编码，随 worker 版本同步）。
     *
     * <p>阶段二为空——阶段三将把内置功能（沙箱、工具、git、搜索等）拆分为独立 jar，
     * 届时在此列表加入对应 id。
     */
    private static final List<String> BUILT_IN_PLUGINS = List.of();

    private final Path pluginsRoot;

    public BuiltInPlugins(WorkerProperties props) {
        this.pluginsRoot = props.resolvePluginsDir();
    }

    /** 启动时一次性物化（幂等）。 */
    @PostConstruct
    void init() {
        materialize();
    }

    /** 插件目录根。 */
    public Path getPluginsRoot() {
        return pluginsRoot;
    }

    /** 全部内置插件 id（供 PluginLoader 排除外部同名用）。 */
    public List<String> builtInIds() {
        return BUILT_IN_PLUGINS;
    }

    /**
     * 把内置插件从 classpath 物化到系统插件目录。
     *
     * <p>物化逻辑：
     * <ul>
     *   <li>classpath 资源 {@code plugins/<id>/plugin.json} → {@code <pluginsRoot>/<id>/plugin.json}</li>
     *   <li>classpath 资源 {@code plugins/<id>/lib/<id>.jar} → {@code <pluginsRoot>/<id>/lib/<id>.jar}</li>
     *   <li>classpath 资源 {@code plugins/<id>/web/*} → {@code <pluginsRoot>/<id>/web/*}（如有）</li>
     * </ul>
     * 幂等：目标已存在且大小一致 → 跳过；不一致 → 覆盖。失败仅 WARN。
     */
    public void materialize() {
        for (String id : BUILT_IN_PLUGINS) {
            try {
                materializePlugin(id);
            } catch (IOException e) {
                log.warn("内置插件物化失败 plugin={} target={}", id,
                        pluginsRoot.resolve(id), e);
            }
        }
    }

    private void materializePlugin(String id) throws IOException {
        Path targetDir = pluginsRoot.resolve(id).normalize();
        if (!targetDir.startsWith(pluginsRoot)) {
            log.warn("插件路径越界,已跳过: {}", targetDir);
            return;
        }

        // 物化 plugin.json
        materializeResource(id, "plugin.json", targetDir.resolve("plugin.json"));

        // 物化 jar（lib/<id>.jar）
        String jarPath = "lib/" + id + ".jar";
        ClassPathResource jarRes = new ClassPathResource(RESOURCE_PREFIX + id + "/" + jarPath);
        if (jarRes.exists()) {
            Path jarTarget = targetDir.resolve(jarPath).normalize();
            materializeFile(jarRes, jarTarget);
        }

        // 物化 web 侧（如有 web/index.js）
        ClassPathResource webRes = new ClassPathResource(RESOURCE_PREFIX + id + "/web/index.js");
        if (webRes.exists()) {
            Path webTarget = targetDir.resolve("web/index.js").normalize();
            materializeFile(webRes, webTarget);
        }
    }

    private void materializeResource(String id, String resourceName, Path target) throws IOException {
        ClassPathResource res = new ClassPathResource(RESOURCE_PREFIX + id + "/" + resourceName);
        if (!res.exists()) {
            log.warn("内置插件资源缺失(classpath): {}/{}", id, resourceName);
            return;
        }
        materializeFile(res, target);
    }

    /**
     * 物化单个文件（幂等：已存在且大小一致 → 跳过；不一致 → 覆盖）。
     */
    private void materializeFile(ClassPathResource res, Path target) throws IOException {
        if (!target.startsWith(pluginsRoot)) {
            log.warn("物化路径越界,已跳过: {}", target);
            return;
        }
        Files.createDirectories(target.getParent());
        if (Files.isRegularFile(target) && Files.size(target) == res.contentLength()) {
            return; // 幂等：已存在且大小一致
        }
        try (InputStream in = res.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
