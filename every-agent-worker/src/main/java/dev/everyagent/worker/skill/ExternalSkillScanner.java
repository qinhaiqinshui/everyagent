package dev.everyagent.worker.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 外部 skill 扫描器:扫描 {@link BuiltInSkills#getKnowledgeRoot()} 下的一级子目录,
 * 对每个合法目录构造 {@link Skill} 记录。
 *
 * <p>外部 skill <b>不进 system prompt</b>(不经 {@code SkillAdvisor} 注入),只注册进
 * {@code /} 菜单(后续步骤 4 消费)。与内置 skill 互补:内置 skill 由 {@link BuiltInSkills}
 * 从 classpath 物化并自动注入 prompt;外部 skill 由用户在系统技能目录下放置
 * {@code <id>/skill.md} 自助扩展。
 *
 * <p>扫描规则(详见 {@link #scanOnce}):
 * <ol>
 *   <li>遍历 knowledgeRoot 下的一级子目录(不递归);</li>
 *   <li>跳过内置 skill id(防重复注册);</li>
 *   <li>目录名须匹配 {@code [a-z0-9][a-z0-9-]*};</li>
 *   <li>须存在 {@code <dir>/skill.md};</li>
 *   <li>realpath 须仍以 knowledgeRoot 为前缀(防符号链接越界);</li>
 *   <li>描述取自 skill.md 首个非空且非 {@code #} 标题行的正文行,截断 200 字符;</li>
 *   <li>toolIds 恒为空(外部 skill 不声明工具绑定)。</li>
 * </ol>
 *
 * <p>扫描在 {@code @PostConstruct} 时执行一次,结果缓存于 {@link #cache}(不可变 List,
 * {@code scan()} 直接返回)。任何异常整体降级为空列表 + WARN,不阻断 worker 启动。
 */
@Component
public class ExternalSkillScanner {

    private static final Logger log = LoggerFactory.getLogger(ExternalSkillScanner.class);

    /** 合法目录名:首字符小写字母或数字,后续可含连字符。 */
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** 描述截断上限(字符数)。 */
    private static final int DESC_MAX_CHARS = 200;

    private final BuiltInSkills builtInSkills;

    /** 扫描结果缓存(不可变 List;{@code null} 仅在扫描失败降级前出现)。 */
    private volatile List<Skill> cache;

    public ExternalSkillScanner(BuiltInSkills builtInSkills) {
        this.builtInSkills = builtInSkills;
    }

    /**
     * 启动时扫描一次(无热加载/监听),缓存结果。失败降级为空列表。
     */
    @PostConstruct
    void init() {
        this.cache = scanOnce();
    }

    /**
     * 返回外部 skill 列表(扫描结果缓存,可能为空,不返回 null)。
     * 线程安全:返回不可变 List,底层引用 {@code volatile} 可见。
     */
    public List<Skill> scan() {
        List<Skill> c = cache;
        return c == null ? List.of() : c;
    }

    /**
     * 执行一次扫描(可独立调用,如测试)。不修改缓存。
     */
    List<Skill> scanOnce() {
        Path knowledgeRoot = builtInSkills.getKnowledgeRoot();
        Set<String> builtinIds = new HashSet<>();
        for (Skill s : builtInSkills.getAllSkills()) {
            builtinIds.add(s.id());
        }

        if (knowledgeRoot == null || !Files.isDirectory(knowledgeRoot)) {
            log.warn("外部 skill 扫描跳过:knowledgeRoot 不存在或非目录 {}", knowledgeRoot);
            return List.of();
        }

        Path rootReal;
        try {
            rootReal = knowledgeRoot.toRealPath();
        } catch (IOException e) {
            log.warn("外部 skill 扫描失败:无法 realpath knowledgeRoot {}", knowledgeRoot, e);
            return List.of();
        }

        List<Skill> result = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        try (Stream<Path> stream = Files.list(knowledgeRoot)) {
            stream.filter(Files::isDirectory)
                    .forEach(dir -> scanOne(dir, rootReal, builtinIds, result, seenIds));
        } catch (IOException e) {
            log.warn("外部 skill 扫描失败:遍历 knowledgeRoot {} 异常", knowledgeRoot, e);
            return List.of();
        }
        return List.copyOf(result);
    }

    private void scanOne(Path dir, Path rootReal, Set<String> builtinIds,
                         List<Skill> result, Set<String> seenIds) {
        String name = dir.getFileName().toString();

        if (builtinIds.contains(name)) {
            log.debug("外部 skill 跳过(内置 id):{}", name);
            return;
        }
        if (!VALID_ID.matcher(name).matches()) {
            log.warn("外部 skill 跳过(目录名不合法,须匹配 [a-z0-9][a-z0-9-]*):{}", name);
            return;
        }
        // realpath 越界防御:符号链接指向 knowledgeRoot 外时跳过
        Path dirReal;
        try {
            dirReal = dir.toRealPath();
        } catch (IOException e) {
            log.warn("外部 skill 跳过(无法 realpath):{}", name, e);
            return;
        }
        if (!dirReal.startsWith(rootReal)) {
            log.warn("外部 skill 跳过(realpath 越界,不在 knowledgeRoot 下):{} -> {}", name, dirReal);
            return;
        }
        Path skillMd = dir.resolve("skill.md");
        if (!Files.isRegularFile(skillMd)) {
            log.warn("外部 skill 跳过(缺 skill.md):{}", name);
            return;
        }
        if (seenIds.contains(name)) {
            log.warn("外部 skill 跳过(重复 id,先到者胜):{}", name);
            return;
        }
        String description = extractDescription(skillMd, name);
        Skill skill = new Skill(name, name, description, skillMd.toAbsolutePath().toString(), List.of());
        seenIds.add(name);
        result.add(skill);
        log.info("外部 skill 注册:{} ({})", name, skillMd);
    }

    /**
     * 从 skill.md 提取描述:首个非空且非 {@code #} 标题行的正文行,截断至 200 字符。
     * 读取失败/全文仅标题行 → 空串。
     */
    private String extractDescription(Path skillMd, String name) {
        try {
            List<String> lines = Files.readAllLines(skillMd, StandardCharsets.UTF_8);
            for (String raw : lines) {
                if (raw == null) {
                    continue;
                }
                String line = raw.strip();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                return line.length() > DESC_MAX_CHARS ? line.substring(0, DESC_MAX_CHARS) : line;
            }
        } catch (IOException e) {
            log.warn("外部 skill 描述提取失败(不可读 skill.md):{}", name, e);
        }
        return "";
    }
}
