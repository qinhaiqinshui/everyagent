package dev.everyagent.worker.skill;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.everyagent.worker.config.WorkerProperties;

/**
 * 系统技能目录 {@code <skillsDir>/} 的目录扫描器——skill 的<b>唯一读取口</b>
 * (目录即真相源,规格见 docs/ARCHITECTURE.md §7.17)。
 *
 * <p>内置包({@link BuiltInSkillPacks} 物化)、插件包(插件 {@code activate()} 经
 * plugin-api 的 {@code SkillMaterializer} 物化)与用户自制 skill 落盘后<b>统一</b>由
 * 本类发现。<b>首次 {@link #scan()} 时扫描目录并缓存,此后只读快照</b>。
 * 缓存的取舍:skill 目录变更(增删 skill 目录)需要重启 worker 才能生效——skill 变更
 * 是低频运维操作,重启成本可接受;换来的是消费方({@link SkillAdvisor} 每 run 注入、
 * {@code SkillSlashProvider} 每次菜单打开、RPC 层 skill.list)零 IO 开销。
 *
 * <p>懒加载天然解决时序问题:{@link BuiltInSkillPacks} 的 {@code @PostConstruct}
 * 物化内置包,而 {@link #scan()} 的首次调用一定发生在 worker 完全启动之后
 * (任务请求或菜单请求),那时物化早已完成。
 *
 * <p>扫描规则:
 * <ol>
 *   <li>遍历 {@code skillsDir} 下的一级子目录(<b>不递归</b>,二级子目录不算);</li>
 *   <li>目录名即 id,须匹配 {@code [a-z0-9][a-z0-9-]*}(非法跳过);</li>
 *   <li>目录内须存在 {@code skill.md}(缺失跳过);</li>
 *   <li>realpath 必须仍落在 {@code skillsDir} 内(符号链接越界拒绝该目录);</li>
 *   <li>展示元数据(title/description)经 {@link SkillFrontmatter#parse} 解析
 *       (fallbackName = 目录名;无 frontmatter 时回落「目录名 + 首个非 {@code #} 正文行」)。</li>
 * </ol>
 *
 * <p>结果按 id 升序(确定序,保证 {@code /} 菜单条目顺序稳定)。单目录扫描下目录名
 * 天然唯一,不存在「重复 id」形态。目录不存在/不可读整体降级为空列表,不阻断
 * worker 启动与 prompt 构造。
 */
@Component
public class SkillCatalog {

    private static final Logger log = LoggerFactory.getLogger(SkillCatalog.class);

    /** 合法 skill id(= 一级目录名):首字符小写字母或数字,后续可含连字符。 */
    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** skill 目录内的知识包文件名。 */
    private static final String SKILL_FILE_NAME = "skill.md";

    private final Path skillsDir;

    /** 首次 scan() 后缓存的不可变快照;null 表示尚未扫描。 */
    private volatile List<Skill> cached;

    public SkillCatalog(WorkerProperties props) {
        this.skillsDir = props.resolveSkillsDir();
    }

    /**
     * 返回 skill 列表。首次调用时实扫目录并缓存,此后只读快照。
     *
     * @return 不可变列表,目录不存在/扫描失败时为空列表(不返回 null)
     */
    public List<Skill> scan() {
        List<Skill> snapshot = cached;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = cached;
                if (snapshot == null) {
                    snapshot = doScan();
                    cached = snapshot;
                    log.info("skill 目录扫描完成,缓存 {} 个 skill", snapshot.size());
                }
            }
        }
        return snapshot;
    }

    /** 实扫一次系统技能目录,返回全部 skill(按 id 升序)。仅在首次 {@link #scan()} 时调用。 */
    private List<Skill> doScan() {
        if (!Files.isDirectory(skillsDir)) {
            // 全新安装或目录尚未物化:降级为空列表即可
            log.debug("系统技能目录不存在,本次扫描返回空: {}", skillsDir);
            return List.of();
        }
        Path rootReal;
        try {
            rootReal = skillsDir.toRealPath();
        } catch (IOException e) {
            log.warn("skill 目录扫描失败:无法 realpath skillsDir {}", skillsDir, e);
            return List.of();
        }
        List<Skill> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(skillsDir)) {
            stream.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(dir -> scanOne(dir, rootReal, result));
        } catch (IOException e) {
            log.warn("skill 目录扫描失败:遍历 skillsDir {} 异常", skillsDir, e);
            return List.of();
        }
        return List.copyOf(result);
    }

    /** 扫描单个一级子目录(不合法即跳过,绝不因单个目录畸形导致整体失败)。 */
    private void scanOne(Path dir, Path rootReal, List<Skill> result) {
        String id = dir.getFileName().toString();
        if (!VALID_ID.matcher(id).matches()) {
            log.warn("skill 跳过(目录名不合法,须匹配 [a-z0-9][a-z0-9-]*):{}", id);
            return;
        }
        // realpath 越界防御:符号链接指向 skillsDir 外时拒绝该目录
        Path dirReal;
        try {
            dirReal = dir.toRealPath();
        } catch (IOException e) {
            log.warn("skill 跳过(无法 realpath):{}", id, e);
            return;
        }
        if (!dirReal.startsWith(rootReal)) {
            log.warn("skill 跳过(realpath 越界,不在 skillsDir 下):{} -> {}", id, dirReal);
            return;
        }
        Path skillMd = dir.resolve(SKILL_FILE_NAME);
        if (!Files.isRegularFile(skillMd)) {
            log.warn("skill 跳过(缺 skill.md):{}", id);
            return;
        }
        SkillFrontmatter.Frontmatter fm = parseFrontmatter(skillMd, id);
        result.add(new Skill(id, fm.name(), fm.description(), skillMd.toAbsolutePath().normalize().toString()));
        log.debug("skill 发现:{} ({})", id, skillMd);
    }

    /** 读 skill.md 并解析 frontmatter;读取失败降级为「目录名 + 空描述」。 */
    private SkillFrontmatter.Frontmatter parseFrontmatter(Path skillMd, String id) {
        try {
            String content = Files.readString(skillMd, StandardCharsets.UTF_8);
            return SkillFrontmatter.parse(content, id);
        } catch (IOException e) {
            log.warn("skill 元数据解析失败(不可读 skill.md,降级为目录名 + 空描述):{}", id, e);
            return new SkillFrontmatter.Frontmatter(id, "");
        }
    }
}
