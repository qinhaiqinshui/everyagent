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
 * 本类发现:<b>不排除任何 id</b>、<b>不缓存</b>——{@link #scan()} 每次实扫目录并读取
 * 每个 {@code skill.md} 的文件头。去缓存是刻意取舍:物化(BuiltInSkillPacks 启动、
 * 插件 activate 晚于两者)与任何快照之间都存在时序耦合,快照必然漏项;当前量级
 * (个位数 skill)的目录列举 + 读文件头开销可忽略。
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

    public SkillCatalog(WorkerProperties props) {
        this.skillsDir = props.resolveSkillsDir();
    }

    /**
     * 实扫一次系统技能目录,返回全部 skill(按 id 升序)。
     * 不缓存:每次调用重新扫描(开销 = 一次目录列举 + 每 skill 读一个文件头)。
     *
     * @return 不可变列表,目录不存在/扫描失败时为空列表(不返回 null)
     */
    public List<Skill> scan() {
        if (!Files.isDirectory(skillsDir)) {
            // 启动早期(内置包物化前)或全新安装:正常形态,降级为空列表即可
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

    /**
     * 重扫一次并返回当前 skill 数量(热加载入口,{@code skill.reload} RPC 调用)。
     * 因 {@link #scan()} 本就不缓存,本方法只是「实扫一次」的语义化封装;
     * 变更广播({@code config.changed{keys:["skills"]}})由调用方 ConfigRpcHandler 负责。
     *
     * @return 本次扫描到的 skill 总数(内置 + 插件包 + 用户自制)
     */
    public int reload() {
        return scan().size();
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
