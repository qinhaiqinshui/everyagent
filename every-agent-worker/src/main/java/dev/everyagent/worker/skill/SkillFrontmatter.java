package dev.everyagent.worker.skill;

import java.util.Locale;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code skill.md} 前置元数据(frontmatter)解析器:纯静态工具,YAML 最小子集,零 YAML 依赖。
 *
 * <p>规格(计划「skill 目录唯一真相源」§1):
 * <ul>
 *   <li>块位于文件<b>首部</b>(允许 UTF-8 BOM),首行 {@code ---} 起、下一个 {@code ---} 止;
 *       缺块 / 未闭合 → 视为无元数据(不报错)。</li>
 *   <li>行语法 {@code key: value}:键须匹配 {@code [A-Za-z][A-Za-z0-9_-]*}(对已知键大小写不敏感);
 *       值去首尾空白、剥离<b>成对</b>的单/双引号(不成对则原样保留);整行 {@code #} 开头
 *       (允许前置空白)视为注释跳过;未知键忽略(log.debug,绝不报错——第三方文件里的
 *       {@code auto-expose} 之类历史/外来键必须被静默忽略,暴露控制语义已迁至 worker 侧
 *       按工作区排除配置);{@code key:} 空值 = 未提供。</li>
 *   <li>已知键仅两个:{@code name}(展示标题,不截断)、{@code description}
 *       (一句话「何时用」,截断 200 字符)。</li>
 *   <li><b>不支持</b>多行折叠 / 嵌套 / 列表。</li>
 * </ul>
 *
 * <p>回落(API 由调用方注入 name 回落值——即 skill <b>目录名</b>;description 回落由本解析器完成):
 * <ul>
 *   <li>{@code name} 缺省(或空值/空白值)→ 回落 {@code fallbackName}(目录名;
 *       id = 目录名是身份键,name 只作展示,二者不一致是常态,不做校验);</li>
 *   <li>{@code description} 缺省 → 回落「正文里首个非空、非 {@code #} 标题、非 {@code ---}
 *       定界的行」(截断 200 字符;跳过孤立 {@code ---} 是为让未闭合块的起始定界行
 *       不被当描述),再缺省则空串。</li>
 * </ul>
 *
 * <p>「正文」= frontmatter 块之后的内容(无块/未闭合则整个文件)。回落扫描<b>从正文首行起</b>,
 * 绝不把闭合块内的 {@code key: value} 行捞来当描述(易错点:必须跳过块本身)。
 *
 * <p>本类对任何畸形输入都不抛异常,一律降级为「无元数据 + 正文回落」;输入 {@code null}
 * 视为空串。仅 worker 读元数据,故住在 worker 的 skill 包(不进 plugin-api——api 只管字节落盘)。
 */
public final class SkillFrontmatter {

    private static final Logger log = LoggerFactory.getLogger(SkillFrontmatter.class);

    /** 合法键:首字符 ASCII 字母,后续字母/数字/下划线/连字符。 */
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*");

    /** frontmatter 起止定界:整行 strip 后精确等于 "---"(容忍尾随空白;不认 "----" 等)。 */
    private static final String DELIMITER = "---";

    /** description 截断上限(UTF-16 字符数,与旧描述提取口径一致)。 */
    private static final int DESC_MAX_CHARS = 200;

    private SkillFrontmatter() {
    }

    /**
     * 解析结果:仅含展示元数据(name/description),无暴露控制字段
     * (暴露 = skill × 工作区的关系,存 worker 侧排除配置,不进 skill.md)。
     */
    public record Frontmatter(String name, String description) {
    }

    /**
     * 解析 skill.md 全文的前置元数据;永不抛异常。
     *
     * @param content      skill.md 全文(允许 UTF-8 BOM;{@code null} 视为空串)
     * @param fallbackName name 缺省/空值时的回落值(skill 目录名,由调用方注入);
     *                     {@code null}/空白 → 空串
     * @return name 与 description 恒非 {@code null}(无任何来源时 description 为空串)
     */
    public static Frontmatter parse(String content, String fallbackName) {
        String text = content == null ? "" : content;
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1); // 剥离 UTF-8 BOM
        }
        String[] lines = text.split("\\R", -1);

        String name = null;
        String description = null;
        // 正文起始行索引:无块/未闭合 = 0(整个文件即正文,回落照常工作)
        int bodyStart = 0;

        if (lines.length > 0 && DELIMITER.equals(lines[0].strip())) {
            int close = -1;
            for (int i = 1; i < lines.length; i++) {
                if (DELIMITER.equals(lines[i].strip())) {
                    close = i;
                    break;
                }
            }
            if (close > 0) {
                for (int i = 1; i < close; i++) {
                    String line = lines[i].strip();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue; // 空行 / 注释(前置空白已被 strip 消除)
                    }
                    int colon = line.indexOf(':');
                    if (colon <= 0) { // 无冒号 / 冒号打头:畸形行,忽略
                        log.debug("skill.md frontmatter 忽略畸形行:{}", line);
                        continue;
                    }
                    String key = line.substring(0, colon).strip();
                    if (!KEY.matcher(key).matches()) {
                        log.debug("skill.md frontmatter 忽略非法键:{}", key);
                        continue;
                    }
                    String value = unquote(line.substring(colon + 1).strip());
                    if (value.isEmpty()) {
                        continue; // key: 空值(含空引号对)= 未提供
                    }
                    switch (key.toLowerCase(Locale.ROOT)) {
                        case "name" -> name = value;
                        case "description" -> description = truncate(value);
                        default -> log.debug("skill.md frontmatter 忽略未知键:{}(暴露控制等语义已迁至 worker 配置)", key);
                    }
                }
                bodyStart = close + 1;
            } else {
                log.debug("skill.md frontmatter 块未闭合,视为无元数据(整个文件即正文)");
            }
        }

        if (name == null || name.isBlank()) {
            name = fallbackName == null ? "" : fallbackName.strip();
        }
        if (description == null || description.isBlank()) {
            description = fallbackDescription(lines, bodyStart);
        }
        return new Frontmatter(name, description);
    }

    /**
     * description 回落:从 {@code bodyStart} 起取首个非空、非 {@code #} 标题、非 {@code ---}
     * 定界的行(strip 后截断 200);无可取行 → 空串。
     *
     * <p><b>易错点</b>:从正文首行(闭合定界之后/文件首行)起扫描保证不会捞到块内
     * {@code key: value} 行当描述。跳过孤立的 {@code ---}:未闭合块的起始定界不是正文内容,
     * 不跳过会得到 {@code "---"} 这种无意义描述。
     */
    private static String fallbackDescription(String[] lines, int bodyStart) {
        for (int i = bodyStart; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty() || line.startsWith("#") || DELIMITER.equals(line)) {
                continue;
            }
            return truncate(line);
        }
        return "";
    }

    /** 剥离成对的单/双引号(仅当首尾成对;不成对或长度不足 2 则原样保留)。 */
    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private static String truncate(String s) {
        return s.length() > DESC_MAX_CHARS ? s.substring(0, DESC_MAX_CHARS) : s;
    }
}
