package dev.everyagent.plugin.api.skill;

import dev.everyagent.plugin.api.util.AtomicFiles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 技能包物化工具(静态):把 classpath 上的技能包 {@code <root>/<id>/skill.md} 原子落盘为
 * {@code <skillsDir>/<id>/skill.md}。worker 内置包与外部插件共用本类,是「skill 目录唯一真相源」
 * 的落盘通道(规格见 docs/ARCHITECTURE.md §7.17)。
 *
 * <h2>两个资源根与父委派隔离</h2>
 * <ul>
 *   <li>{@link #PLUGIN_SKILL_ROOT}(= {@code skill}):插件 jar 侧的技能包根。插件在
 *       {@code activate()} 里调 {@link #materializeAllFromClasspath}。</li>
 *   <li>{@link #BUILTIN_SKILL_ROOT}(= {@code builtin-skill}):worker 内置技能包根,由 worker 启动时
 *       调 {@link #materializeAll(Path, ClassLoader, String)} 落盘。</li>
 * </ul>
 * 两个根<b>刻意不同</b>,这是父委派泄漏的隔离手段:插件 classloader 以 worker 的 classloader 为
 * parent(见 worker 侧 {@code PluginLoader}),{@code classpath*:} 扫描会经父委派看到 parent 上的
 * 同名根资源——若内置包与插件包共用一个根,任意先 activate 的插件都会把 worker 的内置包错当自己的
 * 代装(实测复现过)。worker 的 classpath 上不存在 {@code skill/} 根(内置包全部住在
 * {@code builtin-skill/} 下),插件扫 {@code skill/**} 时父 loader 上无此根,隔离零运行时成本成立;
 * 谁放错根,调用方日志里的物化 id 清单立即可见。
 *
 * <h2>发现 pattern:一级 &#42; 而非 &#42;&#42;</h2>
 * {@link #materializeAll} 用 pattern {@code classpath*} 前缀加 &lt;root&gt;/&#42;/skill.md
 * 发现技能包(目录形态与普通 jar 形态 classpath 均实测可用)。&#42; 恰好一级,id = 资源路径倒数
 * 第二段目录名(唯一可能的那一段);&#42;&#42; 会接受 {@code skill/a/b/skill.md} 这类深嵌套并猜出
 * 无意义的 id,故不采用。
 *
 * <p><b>命中但 id 非法 → ERROR 后跳过(不静默)</b>:目录名不合 {@link #isValidId} 的资源(如
 * {@code Plan}/{@code my_skill}/中文)不会被物化,但**必须留下一条 ERROR**——带推导出的 id 与
 * 资源描述,并附规则口径。理由:加包者看到的现象只有「物化 id 清单里少了一项」,没有日志就无从
 * 归因(是 id 写错了?还是层级放深了?还是根本没进 classpath?);ERROR 让「我的 skill 为什么
 * 没出现」当场可判。注意深嵌套 {@code <root>/a/b/skill.md} 属于**压根扫不到**(一级 &#42; 不
 * 匹配、不进这条日志),故加包的两个必要条件是「id 合规」+「层级恰一层」。
 *
 * <h2>物化语义</h2>
 * <ul>
 *   <li><b>幂等</b>:与磁盘内容<b>逐字节</b>比较,一致则零写入返回 {@code false};判据是内容而非
 *       文件大小——「内容变、大小恰好相同」的等长升级不会被漏掉。</li>
 *   <li><b>原子写入</b>:复用 {@link AtomicFiles#replace}(同目录唯一名临时文件 → 原子替换),
 *       杜绝扫描方读到半截文件。</li>
 *   <li><b>清理旧扁平残留</b>:旧布局 {@code <skillsDir>/<id>.md}(不带目录)在每次物化时尽力删除
 *       (尽力而为:被杀软短暂锁住时不阻断本次物化,残留无害——目录扫描只认 {@code <id>/skill.md})。</li>
 *   <li><b>失败策略</b>:资源缺失 / IO 失败抛 {@link IOException},由调用方 WARN 降级、不阻断启动
 *       (代价是该 skill 整体消失,可经 skill 重载重试复原)。</li>
 * </ul>
 *
 * <p>技能包一律经本类物化落盘,再由 worker 侧目录扫描统一发现;历史上的代码注册通道
 * (插件经上下文向 system prompt 与 {@code /} 菜单直接声明 skill 元数据的 SPI)已整体删除,
 * 不再存在绕过落盘的旁路。
 */
public final class SkillMaterializer {

    private static final Logger log = LoggerFactory.getLogger(SkillMaterializer.class);

    /** 技能包目录下的知识包文件名。 */
    private static final String SKILL_FILE_NAME = "skill.md";

    /** id 规则的人读口径(ERROR 提醒里带上,便于加包者当场自纠)。 */
    private static final String ID_RULE_HINT =
            "目录名须匹配 [a-z0-9][a-z0-9-]*(仅小写字母、数字与连字符;大写、下划线、点、中文均非法)";

    /** 合法 skill id:[a-z0-9][a-z0-9-]*(即目录名;realpath 必须仍在 skillsDir 内)。 */
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9-]*");

    /** 插件侧技能包根(相对 classpath):插件 jar 把技能包放在 {@code skill/<id>/skill.md}。 */
    public static final String PLUGIN_SKILL_ROOT = "skill";

    /** worker 内置技能包根(相对 classpath):与插件根不同名,是父委派泄漏的隔离手段(见类 javadoc)。 */
    public static final String BUILTIN_SKILL_ROOT = "builtin-skill";

    private SkillMaterializer() {
    }

    /**
     * skill id 是否合法:[a-z0-9][a-z0-9-]*。
     *
     * <p>id 即 {@code <skillsDir>} 下的一级目录名。字符集不含点、斜杠与反斜杠,天然排除
     * {@code ..} / {@code a/b} 等路径逃逸形态;大小写敏感(大写、下划线均非法)。
     *
     * @param id 待校验的 id(可为 null,返回 false)
     * @return 合法返回 true
     */
    static boolean isValidId(String id) {
        return id != null && ID_PATTERN.matcher(id).matches();
    }

    /**
     * 解析技能包知识文件路径:{@code <skillsDir>/<id>/skill.md}(绝对化 + normalize)。
     *
     * <p>校验 id 合法性后仍做前缀越界防御(解析结果必须仍落在 skillsDir 内,越界抛
     * {@link IllegalArgumentException})——对合法 id 这是冗余防线,防的是未来校验口径松动。
     *
     * @param skillsDir 系统技能目录(不必已存在)
     * @param id        skill id
     * @return {@code <skillsDir>/<id>/skill.md} 的绝对规范路径
     * @throws IllegalArgumentException id 非法,或解析结果越出 skillsDir
     */
    static Path skillFile(Path skillsDir, String id) {
        Objects.requireNonNull(skillsDir, "skillsDir");
        if (!isValidId(id)) {
            throw new IllegalArgumentException(
                    "非法 skill id: \"" + id + "\"(须匹配 [a-z0-9][a-z0-9-]*)");
        }
        Path root = skillsDir.toAbsolutePath().normalize();
        Path dir = root.resolve(id).normalize();
        if (!dir.startsWith(root)) {
            throw new IllegalArgumentException(
                    "skill id 越界: " + id + " 解析为 " + dir + ",已越出 " + root);
        }
        return dir.resolve(SKILL_FILE_NAME);
    }

    /**
     * 物化单个技能包(步骤:① 删旧扁平 {@code <id>.md} 残留 → ② 幂等比较 → ③ 建目录 + 原子写入)。
     *
     * @param skillsDir 系统技能目录(不存在则连同级目录一起创建)
     * @param id        skill id(即目标一级目录名)
     * @param content   {@code skill.md} 的完整字节(原样落盘,本类不解析内容)
     * @return 是否实际写入({@code false} = 磁盘内容已逐字节一致,零写入)
     * @throws IllegalArgumentException id 非法或解析越界(此时尚未做任何 IO)
     * @throws IOException              写入或原子替换失败
     */
    public static boolean materialize(Path skillsDir, String id, byte[] content) throws IOException {
        Objects.requireNonNull(content, "content");
        Path file = skillFile(skillsDir, id); // 先校验,后 IO
        Path root = file.getParent().getParent();

        // ① 旧扁平布局残留(skillsDir 直接下的 <id>.md):尽力清理。失败(如被杀软短暂锁住)不阻断
        //    本次物化——残留无害,目录扫描只认 <id>/skill.md,下次物化会再试。
        try {
            Files.deleteIfExists(root.resolve(id + ".md"));
        } catch (IOException residueCleanupFailed) {
            // 尽力而为
        }

        // ② 幂等:逐字节比较(不是比大小——等长改写必须覆盖)。
        if (Files.exists(file)) {
            byte[] onDisk = Files.readAllBytes(file);
            if (Arrays.equals(onDisk, content)) {
                return false;
            }
        }

        // ③ 建目录 + 同目录唯一名 tmp 写入 + AtomicFiles.replace 原子替换。
        Path dir = file.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "." + file.getFileName() + ".", ".tmp");
        try {
            Files.write(tmp, content);
            AtomicFiles.replace(tmp, file);
        } finally {
            // 成功时 tmp 已被 move 走;replace 失败时它已自清理;此处兜底「写 tmp 阶段」的失败。
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignore) {
                // 清理失败不掩盖原始异常
            }
        }
        return true;
    }

    /**
     * 从 classpath 读单个资源并物化。
     *
     * @param skillsDir    系统技能目录
     * @param id           skill id(资源不参与 id 推导,由调用方给定)
     * @param cl           资源查找用的 classloader
     * @param resourcePath classpath 相对路径(如 {@code skill/plan/skill.md})
     * @return 是否实际写入(语义同 {@link #materialize})
     * @throws IOException 资源缺失(含 resourcePath 为 null)或读取/写入失败
     */
    public static boolean materializeFromClasspath(Path skillsDir, String id, ClassLoader cl,
                                                   String resourcePath) throws IOException {
        if (resourcePath == null) {
            throw new IOException("classpath 资源缺失: null");
        }
        try (InputStream in = cl.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("classpath 资源缺失: " + resourcePath);
            }
            return materialize(skillsDir, id, in.readAllBytes());
        }
    }

    /**
     * 发现并物化某资源根下的全部技能包:pattern 为 {@code classpath*} 前缀加
     * &lt;resourceRoot&gt;/&#42;/skill.md(一级 &#42;,不接受深嵌套),id = 资源路径倒数第二段
     * 目录名,按 id 升序逐个物化。
     *
     * <p>规则:
     * <ul>
     *   <li>id 不合 {@link #isValidId} 的资源(非法布局)跳过,但**打 ERROR** 并带上资源与推导出的
     *       id(不静默:加包者只用「物化 id 清单少了一项」无从归因,ERROR 让原因当场可见);</li>
     *   <li>同 id 多来源时后发现的胜出(实际形态为单一来源根);</li>
     *   <li>根在 classpath 上不存在 → 返回空列表(不抛;「物化 id 清单」由调用方日志呈现);</li>
     *   <li>单个资源读取失败 → 抛 {@link IOException},由调用方 WARN 降级。</li>
     * </ul>
     *
     * @param skillsDir    系统技能目录
     * @param cl           资源查找用的 classloader(worker 内置包传 worker 自己的 loader,
     *                     插件传 {@code getClass().getClassLoader()})
     * @param resourceRoot 资源根({@link #PLUGIN_SKILL_ROOT} 或 {@link #BUILTIN_SKILL_ROOT})
     * @return 已安装(发现并落盘,含幂等零写入)的 id 列表,按 id 升序
     * @throws IOException 发现或物化过程中的 IO 失败
     */
    public static List<String> materializeAll(Path skillsDir, ClassLoader cl,
                                              String resourceRoot) throws IOException {
        Objects.requireNonNull(resourceRoot, "resourceRoot");
        PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver(cl);
        Resource[] resources = resolver.getResources("classpath*:" + resourceRoot + "/*/" + SKILL_FILE_NAME);
        Map<String, Resource> byId = new TreeMap<>(); // TreeMap:同时完成去重与按 id 排序
        for (Resource resource : resources) {
            String id = idOf(resource);
            if (!isValidId(id)) {
                // 命中但布局不合规:跳过是对的,静默是错的——加包者看不到 skill 出现时,
                // 这条 ERROR 是唯一的归因线索(id 写错 / 目录名带非法字符 / 根本没进 classpath)。
                log.error("技能包目录名非法,已跳过(不会物化,模型与 / 菜单都看不到它): "
                                + "id=\"{}\",资源={} —— {}", id, resource.getDescription(), ID_RULE_HINT);
                continue;
            }
            byId.put(id, resource);
        }
        List<String> installed = new ArrayList<>(byId.size());
        for (Map.Entry<String, Resource> entry : byId.entrySet()) {
            try (InputStream in = entry.getValue().getInputStream()) {
                materialize(skillsDir, entry.getKey(), in.readAllBytes());
            }
            installed.add(entry.getKey());
        }
        return installed;
    }

    /**
     * 按插件根 {@link #PLUGIN_SKILL_ROOT} 物化 loader 上全部技能包——插件 {@code activate()} 的
     * 标准一行调用。
     *
     * @param skillsDir 系统技能目录
     * @param cl        插件自己的 classloader
     * @return 已安装的 id 列表,按 id 升序
     * @throws IOException 发现或物化过程中的 IO 失败
     */
    public static List<String> materializeAllFromClasspath(Path skillsDir, ClassLoader cl)
            throws IOException {
        return materializeAll(skillsDir, cl, PLUGIN_SKILL_ROOT);
    }

    /**
     * 从资源 URL 提取 id:路径倒数第二段目录名。
     *
     * <p>两种形态均以 '/' 分段:目录形态 {@code file:/.../<root>/<id>/skill.md}、jar 形态
     * {@code jar:file:/...jar!/<root>/<id>/skill.md}——倒数第二段都是 id。路径做 URL 解码,
     * 目录名含空格等编码字符时不至于解析出伪 id(解码后不合 id 规范同样会被跳过)。
     */
    private static String idOf(Resource resource) throws IOException {
        String path = URLDecoder.decode(resource.getURL().getPath(), StandardCharsets.UTF_8);
        String[] segments = path.split("/");
        return segments.length >= 2 ? segments[segments.length - 2] : null;
    }
}
