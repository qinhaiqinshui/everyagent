package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.util.AtomicFiles;
import dev.everyagent.worker.config.WorkerProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * skill 按工作区<b>暴露配置</b>的持久化存储(架构 §7.17):
 * 落盘 {@code <系统目录>/skill-exposure.json},形态
 * {@code {"<skillId>": {"blacklist": ["<workspaceRoot>", ...], "whitelist": ["<workspaceRoot>", ...]}}}。
 *
 * <p><b>双模式并存(黑名单优先)</b>:
 * <ul>
 *   <li><b>黑名单(blacklist)</b>:列出的工作区<b>不</b>自动暴露该 skill,其余暴露;</li>
 *   <li><b>白名单(whitelist)</b>:非空时仅列出的工作区自动暴露,其余不暴露;空 = 不限制;</li>
 *   <li><b>黑名单优先</b>:若工作区同时在黑白名单中,黑名单胜出(不暴露);</li>
 *   <li><b>不配置(键不存在)</b> = 两个清单都空 = 全部工作区暴露(向后兼容)。</li>
 * </ul>
 *
 * <p>判定逻辑 {@code isExposed(workspaceRoot)}:
 * <ol>
 *   <li>blacklist.contains(root) → false(黑名单优先);</li>
 *   <li>whitelist 非空且 !whitelist.contains(root) → false(白名单限制);</li>
 *   <li>否则 → true(暴露)。</li>
 * </ol>
 *
 * <p>旧版格式迁移:
 * <ul>
 *   <li>最旧版 {@code skill-exclusions.json} 形态 {@code {"<skillId>": ["<root>", ...]}}(数组值)
 *       → 自动迁移为 {@code {"blacklist": [...], "whitelist": []}}(旧语义 = 除列出外全部暴露 = blacklist);</li>
 *   <li>旧版单模式形态 {@code {"mode": "blacklist"|"whitelist", "workspaces": [...]}}
 *       → 迁移为对应清单:blacklist 模式填入 blacklist、whitelist 模式填入 whitelist;</li>
 *   <li>迁移后立即落盘为新格式,下次启动直接读新格式。</li>
 * </ul>
 *
 * <p>本存储只做<b>存储</b>,不做任何聪明事:路径不 normalize、不校验是否已注册
 * (normalize/校验由 RPC 层负责);不解释暴露语义——prompt 注入过滤由 SkillAdvisor
 * 消费方组合;{@code /} 菜单不受此配置影响(永远全量)。
 *
 * <p>失败策略:启动读盘缺文件/空文件/解析失败一律 WARN 回退空表(= 全部暴露),
 * 绝不抛出影响启动的异常;写入用 {@link AtomicFiles#writeText}(唯一名 tmp + 原子替换),
 * 失败抛 {@link IllegalStateException} 令调用方(RPC)显式报错。
 * 线程安全:所有读写均 {@code synchronized}。
 */
@Component
public class SkillExposureStore {

    private static final Logger log = LoggerFactory.getLogger(SkillExposureStore.class);

    /** 单个 skill 的暴露配置(黑名单 + 白名单,均保持插入序)。 */
    public record ExposureConfig(Set<String> blacklist, Set<String> whitelist) {
        /**
         * 判断指定工作区根是否暴露该 skill。
         * 黑名单优先:在黑名单中 → 不暴露;白名单非空且不在白名单中 → 不暴露;否则暴露。
         */
        public boolean isExposed(String workspaceRoot) {
            if (blacklist.contains(workspaceRoot)) {
                return false;
            }
            if (!whitelist.isEmpty() && !whitelist.contains(workspaceRoot)) {
                return false;
            }
            return true;
        }
    }

    private final WorkerProperties props;
    /** skillId → 暴露配置(保持插入序,wire 输出稳定)。 */
    private final Map<String, ExposureConfig> configs = new LinkedHashMap<>();
    private Path filePath;

    public SkillExposureStore(WorkerProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        filePath = props.resolveHomeDir().resolve("skill-exposure.json");
        load();
    }

    /** 从磁盘加载;文件不存在/空文件/解析失败时 WARN 回退空表(= 全部暴露),绝不抛出。 */
    private void load() {
        configs.clear();
        if (!Files.isRegularFile(filePath)) {
            // 尝试旧版文件名迁移
            Path oldPath = filePath.getParent().resolve("skill-exclusions.json");
            if (Files.isRegularFile(oldPath)) {
                loadOldFormat(oldPath);
            } else {
                log.info("[skill-exposure] skill-exposure.json 不存在({}),回退空表(全部暴露)", filePath);
            }
            return;
        }
        try {
            String content = Files.readString(filePath);
            if (content.isBlank()) {
                log.warn("[skill-exposure] skill-exposure.json 为空文件,回退空表(全部暴露)");
                return;
            }
            JsonNode root = Json.parse(content);
            if (!root.isObject()) {
                log.warn("[skill-exposure] skill-exposure.json 根节点非 object,回退空表(全部暴露)");
                return;
            }
            for (var entry : root.properties()) {
                JsonNode val = entry.getValue();
                if (val.isArray()) {
                    // 最旧版数组格式 → 自动迁移为 blacklist
                    loadOldArrayEntry(entry.getKey(), val);
                } else if (val.isObject()) {
                    loadObjectEntry(entry.getKey(), val);
                } else {
                    log.warn("[skill-exposure] 条目 {} 的值类型不支持,忽略该条目", entry.getKey());
                }
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[skill-exposure] 读取 skill-exposure.json 失败,回退空表(全部暴露): {}", e.getMessage());
        }
    }

    /** 读取最旧版文件(skill-exclusions.json,数组值格式),自动迁移为 blacklist。 */
    private void loadOldFormat(Path oldPath) {
        try {
            String content = Files.readString(oldPath);
            if (content.isBlank()) {
                log.info("[skill-exposure] 旧版 skill-exclusions.json 为空,回退空表(全部暴露)");
                return;
            }
            JsonNode root = Json.parse(content);
            if (!root.isObject()) {
                log.warn("[skill-exposure] 旧版 skill-exclusions.json 根节点非 object,回退空表");
                return;
            }
            int migrated = 0;
            for (var entry : root.properties()) {
                if (entry.getValue().isArray()) {
                    loadOldArrayEntry(entry.getKey(), entry.getValue());
                    migrated++;
                }
            }
            if (migrated > 0) {
                log.info("[skill-exposure] 旧版 skill-exclusions.json 已迁移 {} 条为双清单格式", migrated);
                // 立即落盘为新格式,避免每次启动重复迁移
                flush();
            }
        } catch (IOException | RuntimeException e) {
            log.warn("[skill-exposure] 读取旧版 skill-exclusions.json 失败,回退空表: {}", e.getMessage());
        }
    }

    /** 加载最旧版数组值条目(自动迁移为 blacklist)。 */
    private void loadOldArrayEntry(String skillId, JsonNode arr) {
        LinkedHashSet<String> roots = parseRoots(arr, skillId);
        if (!roots.isEmpty()) {
            configs.put(skillId, new ExposureConfig(roots, new LinkedHashSet<>()));
        }
    }

    /** 加载对象值条目(兼容新旧两种对象格式)。 */
    private void loadObjectEntry(String skillId, JsonNode obj) {
        // 新格式:{"blacklist": [...], "whitelist": [...]}
        if (obj.has("blacklist") || obj.has("whitelist")) {
            LinkedHashSet<String> blacklist = obj.path("blacklist").isArray()
                    ? parseRoots(obj.path("blacklist"), skillId) : new LinkedHashSet<>();
            LinkedHashSet<String> whitelist = obj.path("whitelist").isArray()
                    ? parseRoots(obj.path("whitelist"), skillId) : new LinkedHashSet<>();
            if (!blacklist.isEmpty() || !whitelist.isEmpty()) {
                configs.put(skillId, new ExposureConfig(blacklist, whitelist));
            }
            return;
        }
        // 旧单模式格式:{"mode": "blacklist"|"whitelist", "workspaces": [...]}
        String modeStr = obj.path("mode").asString(null);
        if (modeStr == null) {
            log.warn("[skill-exposure] 条目 {} 缺少 mode 字段且非双清单格式,忽略该条目", skillId);
            return;
        }
        LinkedHashSet<String> roots = obj.path("workspaces").isArray()
                ? parseRoots(obj.path("workspaces"), skillId) : new LinkedHashSet<>();
        LinkedHashSet<String> blacklist = new LinkedHashSet<>();
        LinkedHashSet<String> whitelist = new LinkedHashSet<>();
        switch (modeStr.trim().toLowerCase()) {
            case "blacklist" -> blacklist.addAll(roots);
            case "whitelist" -> whitelist.addAll(roots);
            default -> {
                log.warn("[skill-exposure] 条目 {} 的 mode 无法识别({}),忽略该条目", skillId, modeStr);
                return;
            }
        }
        if (!blacklist.isEmpty() || !whitelist.isEmpty()) {
            configs.put(skillId, new ExposureConfig(blacklist, whitelist));
        }
    }

    private LinkedHashSet<String> parseRoots(JsonNode arr, String skillId) {
        LinkedHashSet<String> roots = new LinkedHashSet<>();
        for (JsonNode n : arr) {
            if (n.isTextual() && !n.asString().isBlank()) {
                roots.add(n.asString());
            }
        }
        return roots;
    }

    /**
     * 判断指定 skill 在指定工作区根下是否自动暴露。
     *
     * @param skillId       skill 标识
     * @param workspaceRoot 工作区根(normalize 后;null/空白时由调用方兜底不过滤)
     * @return true = 自动暴露(进 system prompt)
     */
    public synchronized boolean isExposed(String skillId, String workspaceRoot) {
        ExposureConfig config = configs.get(skillId);
        if (config == null) {
            return true; // 不配置 = 全部暴露(向后兼容)
        }
        return config.isExposed(workspaceRoot);
    }

    /** 获取指定 skill 的暴露配置;未配置返回 null(= 默认全部暴露)。 */
    public synchronized ExposureConfig getConfig(String skillId) {
        return configs.get(skillId);
    }

    /**
     * 覆盖式设置指定 skill 的暴露配置(黑名单 + 白名单)并原子落盘。
     *
     * @param skillId    skill 标识(非空校验由本方法兜底,存在性校验在 RPC 层)
     * @param blacklist   黑名单工作区根路径集合(在列表中的工作区不暴露该 skill)
     * @param whitelist   白名单工作区根路径集合(非空时仅列表中的工作区暴露)
     * @throws IllegalArgumentException skillId 为空
     */
    public synchronized void setConfig(String skillId,
                                       Collection<String> blacklist,
                                       Collection<String> whitelist) {
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("skillId 不能为空");
        }
        LinkedHashSet<String> bl = toSet(blacklist);
        LinkedHashSet<String> wl = toSet(whitelist);
        // 两个清单都空 = 默认行为(全部暴露),删除键而非保留噪声条目
        if (bl.isEmpty() && wl.isEmpty()) {
            configs.remove(skillId);
        } else {
            configs.put(skillId, new ExposureConfig(bl, wl));
        }
        flush();
    }

    private static LinkedHashSet<String> toSet(Collection<String> source) {
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (source != null) {
            for (String r : source) {
                if (r != null && !r.isBlank()) {
                    set.add(r);
                }
            }
        }
        return set;
    }

    /** 全部暴露配置的快照副本(skillId → 配置,均保持存储序;skill.list 用)。 */
    public synchronized Map<String, ExposureConfig> snapshot() {
        Map<String, ExposureConfig> out = new LinkedHashMap<>();
        configs.forEach((k, v) -> out.put(k,
                new ExposureConfig(new LinkedHashSet<>(v.blacklist()), new LinkedHashSet<>(v.whitelist()))));
        return out;
    }

    /** 将当前内存状态原子写入磁盘(唯一名 tmp + 原子替换;失败抛 IllegalStateException)。 */
    private void flush() {
        try {
            Files.createDirectories(filePath.getParent());
            ObjectNode root = Json.obj();
            for (var entry : configs.entrySet()) {
                ExposureConfig cfg = entry.getValue();
                ObjectNode cfgNode = Json.obj();
                ArrayNode bl = Json.arr();
                cfg.blacklist().forEach(bl::add);
                cfgNode.set("blacklist", bl);
                ArrayNode wl = Json.arr();
                cfg.whitelist().forEach(wl::add);
                cfgNode.set("whitelist", wl);
                root.set(entry.getKey(), cfgNode);
            }
            AtomicFiles.writeText(filePath, Json.write(root));
        } catch (IOException e) {
            log.error("[skill-exposure] 写入 skill-exposure.json 失败: {}", e.getMessage(), e);
            throw new IllegalStateException("写入 skill 暴露配置失败: " + e.getMessage(), e);
        }
    }
}
