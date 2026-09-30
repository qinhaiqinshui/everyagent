package dev.everyagent.plugin.sandbox.codex.acl;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * deny-read 目标解析器——对齐 codex {@code deny_read_resolver.rs}：
 * 精确路径原样透传（含尚不存在者）；glob 快照展开为已存在的文件/目录
 * （Windows ACL 不理解 glob，必须物化为逐路径 ACE）。
 *
 * <p><b>与 codex 的实现差异</b>（简化项，语义对齐）：
 * <ul>
 *   <li>不捆绑 ripgrep——glob 扫描用 java.nio 简化 walker 快照（等价于
 *       rg 缺失时回退的 {@code deny_read_walker.rs} 全量遍历路径，非 rg 快路径）；</li>
 *   <li>匹配恒为大小写不敏感（codex 的 rg 固定 {@code --glob-case-insensitive}，
 *       最终 matcher 在 Windows 上大小写不敏感；本插件仅面向 Windows，统一不敏感）；</li>
 *   <li>不跟随符号链接目录（codex 解析重解析目标并检测环；java.nio 默认不跟随，
 *       链接文件仍按名匹配——不授予额外访问面）；</li>
 *   <li>glob 计划的「根起递归无深度上限即报错」语义保留（对齐
 *       {@code glob_scan_plans} 的 fail-closed）。</li>
 * </ul>
 */
public final class DenyReadGlobs {

    private DenyReadGlobs() {
    }

    /** glob 扫描计划——对齐 deny_read_resolver.rs::GlobScanPlan。 */
    record GlobScanPlan(Path root, Integer maxDepth, List<String> globs) {
    }

    /**
     * 解析 deny-read 目标集。
     *
     * @param exactPaths         精确路径（绝对或相对 {@code cwd}；含缺失者，原样透传）
     * @param globPatterns       glob 模式（相对模式以 {@code cwd} 为根）
     * @param cwd                相对路径/相对模式的锚点
     * @param configuredMaxDepth {@code glob_scan_max_depth}；null = 未配置（递归 glob 无界）
     */
    public static List<Path> resolve(List<Path> exactPaths, List<String> globPatterns, Path cwd,
            Integer configuredMaxDepth) throws IOException {
        List<Path> paths = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Path exact : exactPaths) {
            Path absolute = (exact.isAbsolute() ? exact : cwd.resolve(exact)).normalize();
            if (seen.add(DenyReadPlanner.lexicalPathKey(absolute))) {
                paths.add(absolute);
            }
        }
        if (globPatterns == null || globPatterns.isEmpty()) {
            return paths;
        }
        // 语法先验证（对齐 ReadDenyMatcher::try_new：非法 glob 在展开前即失败）
        for (String pattern : globPatterns) {
            compileGlob(scanSuffix(pattern));
        }
        for (GlobScanPlan plan : scanPlans(globPatterns, configuredMaxDepth, cwd)) {
            if (!Files.isDirectory(plan.root())) {
                continue;
            }
            for (String suffix : plan.globs()) {
                walk(plan.root(), plan.root(), compileGlob(suffix), plan.maxDepth(), paths, seen);
            }
        }
        return paths;
    }

    // ---- 扫描计划（对齐 windows_deny_read_glob_scan + glob_scan_plans） ----

    /** 合并同根计划：同根取最深 maxDepth（null=无界优先），glob 串接为一次扫描。 */
    static List<GlobScanPlan> scanPlans(List<String> patterns, Integer configuredMaxDepth,
            Path cwd) throws IOException {
        Map<String, GlobScanPlan> plans = new LinkedHashMap<>();
        for (String pattern : patterns) {
            GlobScanPlan plan = scanPlan(pattern, configuredMaxDepth, cwd);
            if (plan.maxDepth() == null && plan.root().getParent() == null) {
                throw new IOException("unreadable glob `" + pattern + "` cannot be safely "
                        + "expanded from a filesystem root without `glob_scan_max_depth`; "
                        + "configure `glob_scan_max_depth` or use a non-root directory prefix");
            }
            plans.merge(plan.root().toString(), plan, (a, b) -> {
                Integer merged = (a.maxDepth() != null && b.maxDepth() != null)
                        ? Math.max(a.maxDepth(), b.maxDepth())
                        : null;
                List<String> globs = new ArrayList<>(a.globs());
                globs.addAll(b.globs());
                return new GlobScanPlan(a.root(), merged, globs);
            });
        }
        return List.copyOf(plans.values());
    }

    /**
     * 单模式扫描计划——字面量前缀根 + 深度：任一组件为 {@code **} → 无界
     * （受 configuredMaxDepth 封顶）；否则 = 组件数（受 configuredMaxDepth 封顶）。
     */
    static GlobScanPlan scanPlan(String pattern, Integer configuredMaxDepth, Path cwd) {
        int firstGlob = indexOfAny(pattern, "*", "?", "[");
        String literalPrefix = pattern.substring(0, firstGlob < 0 ? pattern.length() : firstGlob);
        String suffix;
        Path root;
        int separator = lastIndexOfAny(literalPrefix, "/", "\\");
        if (separator >= 0) {
            boolean driveRoot = separator > 0 && literalPrefix.charAt(separator - 1) == ':';
            int end = (separator == 0 || driveRoot) ? separator + 1 : separator;
            root = Path.of(literalPrefix.substring(0, end));
            suffix = pattern.substring(separator + 1);
        } else {
            root = cwd; // 相对模式：以 cwd 为根（codex 的 "." 根 + matcher 相对解析）
            suffix = pattern;
        }
        if (!root.isAbsolute()) {
            root = cwd.resolve(root).normalize();
        }
        String[] components = suffix.split("[/\\\\]+");
        int componentCount = 0;
        boolean recursive = false;
        for (String component : components) {
            if (component.isEmpty()) {
                continue;
            }
            componentCount++;
            recursive = recursive || component.equals("**");
        }
        Integer maxDepth;
        if (recursive) {
            maxDepth = configuredMaxDepth; // ** 递归：无界，除非配置封顶
        } else if (configuredMaxDepth == null) {
            maxDepth = componentCount;
        } else {
            maxDepth = Math.min(configuredMaxDepth, componentCount);
        }
        return new GlobScanPlan(root, maxDepth, List.of(suffix));
    }

    static String scanSuffix(String pattern) {
        int firstGlob = indexOfAny(pattern, "*", "?", "[");
        String literalPrefix = pattern.substring(0, firstGlob < 0 ? pattern.length() : firstGlob);
        int separator = lastIndexOfAny(literalPrefix, "/", "\\");
        return separator >= 0 ? pattern.substring(separator + 1) : pattern;
    }

    // ---- walker（java.nio 简化快照；目录含空目录一并保留） ----

    private static void walk(Path root, Path dir, Pattern pattern, Integer maxDepth,
            List<Path> paths, Set<String> seen) throws IOException {
        List<Path> children;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            children = new ArrayList<>();
            for (Path entry : stream) {
                children.add(entry);
            }
        } catch (DirectoryIteratorException e) {
            IOException error = scanError(e.getCause(), dir);
            if (error != null) {
                throw error;
            }
            return; // 迭代中分支消失/受保护：放弃该目录（对齐 accessible_entry）
        } catch (java.nio.file.NoSuchFileException | java.nio.file.AccessDeniedException e) {
            return; // 打不开的分支跳过，不授予额外访问面
        }
        for (Path entry : children) {
            Path relative = root.relativize(entry);
            if (pattern.matcher(toKey(relative)).matches()
                    && seen.add(DenyReadPlanner.lexicalPathKey(entry))) {
                paths.add(entry);
            }
            if (Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)
                    && (maxDepth == null || relative.getNameCount() < maxDepth)) {
                walk(root, entry, pattern, maxDepth, paths, seen);
            }
        }
    }

    /** NotFound/PermissionDenied → null（跳过）；其余错误包装上报（对齐 accessible_entry）。 */
    private static IOException scanError(Throwable cause, Path path) {
        if (cause instanceof java.nio.file.NoSuchFileException
                || cause instanceof java.nio.file.AccessDeniedException) {
            return null;
        }
        return new IOException("failed to enumerate unreadable glob paths under " + path + ": "
                + cause, cause);
    }

    private static String toKey(Path relative) {
        return relative.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    // ---- glob → 正则（大小写不敏感；非法类/区间在编译期失败） ----

    static Pattern compileGlob(String suffix) throws IOException {
        String normalized = suffix.replace('\\', '/');
        String[] components = normalized.split("/", -1);
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < components.length; i++) {
            boolean last = i == components.length - 1;
            String component = components[i];
            if (component.equals("**")) {
                regex.append("(?:[^/]+/)*");
                if (last) {
                    regex.append("[^/]+"); // 以 ** 结尾：至少一层子孙
                }
                continue;
            }
            regex.append(componentRegex(component));
            if (!last && !"**".equals(components[i + 1])) {
                regex.append('/');
            }
        }
        try {
            return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        } catch (PatternSyntaxException e) {
            throw new IOException("invalid deny-read glob pattern `" + suffix + "`: "
                    + e.getMessage(), e);
        }
    }

    private static String componentRegex(String component) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < component.length(); i++) {
            char ch = component.charAt(i);
            switch (ch) {
                case '*' -> regex.append("[^/]*");
                case '?' -> regex.append("[^/]");
                case '[' -> {
                    int close = component.indexOf(']', i + 1);
                    if (close < 0) {
                        regex.append(Pattern.quote("[")); // 未闭合类：按字面量（对齐 ripgrep_glob）
                        continue;
                    }
                    String body = component.substring(i + 1, close);
                    if (body.startsWith("!") || body.startsWith("^")) {
                        body = "^" + body.substring(1); // glob 否定语法
                    }
                    regex.append('[').append(escapeClass(body)).append(']');
                    i = close;
                }
                default -> {
                    if ("\\.[]{}()*+-?^$|".indexOf(ch) >= 0) {
                        regex.append('\\');
                    }
                    regex.append(ch);
                }
            }
        }
        return regex.toString();
    }

    private static String escapeClass(String body) {
        return body.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
    }

    private static int indexOfAny(String s, String... chars) {
        int best = -1;
        for (String c : chars) {
            int idx = s.indexOf(c);
            if (idx >= 0 && (best < 0 || idx < best)) {
                best = idx;
            }
        }
        return best;
    }

    private static int lastIndexOfAny(String s, String... chars) {
        int best = -1;
        for (String c : chars) {
            int idx = s.lastIndexOf(c);
            if (idx > best) {
                best = idx;
            }
        }
        return best;
    }
}
