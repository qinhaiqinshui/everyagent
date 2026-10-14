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
 * skill 按工作区<b>排除配置</b>的持久化存储(计划「skill 目录唯一真相源」§3.5):
 * 落盘 {@code <系统目录>/skill-exclusions.json},形态 {@code {"<skillId>": ["<workspaceRoot>", ...]}}。
 *
 * <p>语义:<b>不配置 = 全部工作区自动暴露</b>(进 system prompt);每个 skill 可配置一个
 * 「不自动暴露的工作区根路径」黑名单。排除清单按工作区<b>根路径</b>存——它是工作区的身份键
 * (workspaceRegistry 注释原话),{@code WorkspaceEntry.id} 在旧 worker 上可能缺失不可用;
 * 代价是工作区移动/改名后旧排除项失效需重配(已接受的取舍)。
 *
 * <p>本存储只做<b>存储</b>,不做任何聪明事:
 * <ul>
 *   <li>路径<b>不</b> normalize、不校验是否已注册——normalize/校验由 RPC 层
 *       ({@code skill.exclusions.set})负责,这里存入什么就存什么;</li>
 *   <li>不解释排除语义——prompt 注入过滤由 SkillAdvisor 消费方组合(暴露 = skill × 工作区
 *       的关系,不属于 skill 本体,也不在 skill.md frontmatter 中);{@code /} 菜单不受此
 *       配置影响(永远全量),设置页表格经 {@code skill.list} 读快照展示。</li>
 * </ul>
 *
 * <p>独立于 {@link UserPreferenceStore}(那是「主题」类扁平 key-value 杂项容器):本配置是
 * 结构化、有专门 RPC 与广播键({@code config.changed{keys:["skills"]}})的独立域,
 * 独立文件避免把 JSON 塞进字符串值再到处解析。
 *
 * <p>失败策略:启动读盘缺文件/空文件/解析失败一律 WARN 回退空表(= 全部暴露),
 * 绝不抛出影响启动的异常;写入用 {@link AtomicFiles#writeText}(唯一名 tmp + 原子替换,
 * 杜绝「扫描读到半截文件」),失败抛 {@link IllegalStateException} 令调用方(RPC)显式报错,
 * 不静默接受内存/磁盘分叉。线程安全:所有读写均 {@code synchronized}。
 */
@Component
public class SkillExclusionStore {

    private static final Logger log = LoggerFactory.getLogger(SkillExclusionStore.class);

    private final WorkerProperties props;
    /** skillId → 排除的工作区根路径(保持插入序,wire 输出稳定)。 */
    private final Map<String, LinkedHashSet<String>> exclusions = new LinkedHashMap<>();
    private Path filePath;

    public SkillExclusionStore(WorkerProperties props) {
        this.props = props;
    }

    @PostConstruct
    void init() {
        filePath = props.resolveHomeDir().resolve("skill-exclusions.json");
        load();
    }

    /** 从磁盘加载;文件不存在/空文件/解析失败时 WARN 回退空表(= 全部暴露),绝不抛出。 */
    private void load() {
        exclusions.clear();
        if (!Files.isRegularFile(filePath)) {
            log.info("[skill-exclusions] skill-exclusions.json 不存在({}),回退空表(全部暴露)", filePath);
            return;
        }
        try {
            String content = Files.readString(filePath);
            if (content.isBlank()) {
                log.warn("[skill-exclusions] skill-exclusions.json 为空文件,回退空表(全部暴露)");
                return;
            }
            JsonNode root = Json.parse(content);
            if (!root.isObject()) {
                log.warn("[skill-exclusions] skill-exclusions.json 根节点非 object,回退空表(全部暴露)");
                return;
            }
            for (var entry : root.properties()) {
                JsonNode arr = entry.getValue();
                if (!arr.isArray()) {
                    log.warn("[skill-exclusions] 条目 {} 的值非数组,忽略该条目", entry.getKey());
                    continue;
                }
                LinkedHashSet<String> roots = new LinkedHashSet<>();
                for (JsonNode n : arr) {
                    if (n.isTextual() && !n.asString().isBlank()) {
                        roots.add(n.asString());
                    }
                }
                if (!roots.isEmpty()) { // 空清单条目视为未配置,不占键
                    exclusions.put(entry.getKey(), roots);
                }
            }
        } catch (IOException | RuntimeException e) {
            // 损坏 JSON(JsonParseException 是 RuntimeException)、IO 异常等:回退空表,不阻断启动
            log.warn("[skill-exclusions] 读取 skill-exclusions.json 失败,回退空表(全部暴露): {}", e.getMessage());
        }
    }

    /** 指定 skill 被排除的工作区根路径集合(保持存储序);未配置返回空集(= 全部工作区暴露)。 */
    public synchronized Set<String> excludedWorkspaces(String skillId) {
        Set<String> roots = exclusions.get(skillId);
        return roots == null ? Set.of() : new LinkedHashSet<>(roots);
    }

    /**
     * 覆盖式设置指定 skill 的排除工作区清单并原子落盘。
     *
     * @param skillId skill 标识(目录名;非空校验由本方法兜底,存在性校验在 RPC 层)
     * @param roots   工作区根路径集合(RPC 层已 normalize/校验注册;本存储不做路径加工,
     *                仅剔除 null/空白项并去重);<b>空集合 = 清空该 skill 的排除并删除键</b>
     *                (恢复全部工作区暴露)
     */
    public synchronized void setExcludedWorkspaces(String skillId, Collection<String> roots) {
        if (skillId == null || skillId.isBlank()) {
            throw new IllegalArgumentException("skillId 不能为空");
        }
        LinkedHashSet<String> set = new LinkedHashSet<>();
        if (roots != null) {
            for (String r : roots) {
                if (r != null && !r.isBlank()) {
                    set.add(r);
                }
            }
        }
        if (set.isEmpty()) {
            exclusions.remove(skillId); // 空集合 = 清空排除(删除键,不保留 "{}" 噪声条目)
        } else {
            exclusions.put(skillId, set);
        }
        flush();
    }

    /** 全部排除配置的快照副本(skillId → 排除的工作区根路径集,均保持存储序;{@code skill.list} 用)。 */
    public synchronized Map<String, Set<String>> snapshot() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        exclusions.forEach((k, v) -> out.put(k, new LinkedHashSet<>(v)));
        return out;
    }

    /** 将当前内存状态原子写入磁盘(唯一名 tmp + 原子替换;失败抛 IllegalStateException)。 */
    private void flush() {
        try {
            Files.createDirectories(filePath.getParent());
            ObjectNode root = Json.obj();
            for (var entry : exclusions.entrySet()) {
                ArrayNode arr = Json.arr();
                entry.getValue().forEach(arr::add);
                root.set(entry.getKey(), arr);
            }
            AtomicFiles.writeText(filePath, Json.write(root));
        } catch (IOException e) {
            log.error("[skill-exclusions] 写入 skill-exclusions.json 失败: {}", e.getMessage(), e);
            throw new IllegalStateException("写入 skill 排除配置失败: " + e.getMessage(), e);
        }
    }
}
