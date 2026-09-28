package dev.everyagent.worker.task;

import dev.everyagent.contract.json.Json;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本轮(单次主 agent run)文件改动聚合器,移植自 novel_agent-n 的 task-file-changes 插件
 * (old/plugins/task-file-changes/extensions.ts)。
 *
 * <p>职责:收集本回合内所有文件保存记录(create_file / update_file),聚合为文件级净状态
 * (created / updated / deleted),回合收口时由 {@link FileChangeAdvisor} 把轻量摘要与全文
 * 分别填充到 {@link TaskEntry#fileChangesLight} / {@link TaskEntry#fileChangesFull},
 * 由 {@link RoundIndexStore} 落盘(摘要内联进 rounds.jsonl 每轮行、全文写
 * {@code file-changes/<roundId>.json})。不再发 kind='file_changes' 的 task.trace。
 * 与 node 侧一致:删除是终态(本版文件工具无 delete 工具,保留分支防御)。
 *
 * <p>生命周期:主 agent 每次 {@code runner.run(main)} 前由 FileChangeAdvisor 新建并置入
 * {@link TaskEntry#fileChanges},run 收口(含异常/取消)后填充 light/full 槽并置空。主 agent 与子 agent 的
 * 工具调用均经 FileChangeAdvisor 记录到同一实例(子 agent 在主 agent run 内部递归执行),
 * 因此聚合器必须线程安全(子 agent 并行写文件)。
 */
public final class FileChangesCollector {

    /** 文件保存来源。 */
    public enum Source {
        MAIN, SUB_AGENT
    }

    /**
     * 单条文件保存记录。filePath 为<b>完整业务路径</b>:工作区内锚定为 '/a/b/c'
     * (前导斜杠,与前端资源树/文件标签同一形态);工作区外授权写为绝对路径。
     */
    public record Entry(String id, String agentId, Source source, String filePath, String fileName,
            String before, String after, String changeType, long savedAt) {
    }

    /**
     * 文件级聚合结果(与前端 TaskFileChangeSummary 同形)。filePath 为<b>完整业务路径</b>
     * ('/a/b.c' 或工作区外绝对路径),保证展开态「路径」列与文件名不重合。
     */
    public record Summary(String filePath, String fileName, String changeType,
            String beforeContent, String afterContent, int saveCount) {
    }

    /** changeType 与 git 状态字母映射(A=新建/M=修改/D=删除),与 node 侧 CHANGE_GIT_LETTER 一致。 */
    private static String gitLetter(String changeType) {
        return switch (changeType) {
            case "created" -> "A";
            case "deleted" -> "D";
            default -> "M";
        };
    }

    /** 原始保存事件列表(保序)。 */
    private final List<Entry> changes = new ArrayList<>();
    /** 文件级聚合(按首次出现顺序)。 */
    private final Map<String, Summary> summaries = new LinkedHashMap<>();
    /** 同轮事件去重集合(并发安全)。 */
    private final Set<String> dedupeKeys = ConcurrentHashMap.newKeySet();

    /** 记录一次文件保存;同轮内 (agentId, path, type, before, after) 完全相同的保存去重。 */
    public void onFileSaved(String agentId, String filePath, String before, String after,
            String changeType) {
        if (agentId == null || agentId.isEmpty() || filePath == null || filePath.isEmpty()) {
            return;
        }
        // 先归一再入表:同一文件的不同写法('/a.md'、'a.md'、'./a.md'、'sub\c.md')
        // 折叠成同一条 filePath,去重键与文件级聚合都以归一后路径为准。
        String path = normalizePathOf(filePath);
        String key = String.join("\u0000", agentId, path,
                changeType == null ? "" : changeType,
                before == null ? "" : before,
                after == null ? "" : after);
        if (!dedupeKeys.add(key)) {
            return;
        }
        long now = System.currentTimeMillis();
        Entry entry = new Entry(java.util.UUID.randomUUID().toString(),
                agentId, Source.MAIN, path, fileNameOf(path),
                before == null ? "" : before, after == null ? "" : after,
                changeType == null ? "modified" : changeType, now);
        synchronized (this) {
            changes.add(entry);
            merge(entry);
        }
    }

    /** 本回合是否没有任何文件保存记录。 */
    public boolean isEmpty() {
        synchronized (this) {
            return summaries.isEmpty();
        }
    }

    /** 按业务路径排序的文件级聚合快照。 */
    public List<Summary> buildSummaries() {
        synchronized (this) {
            List<Summary> out = new ArrayList<>(summaries.values());
            out.sort(Comparator.comparing(Summary::filePath));
            return out;
        }
    }

    /** 收起态标题:「文件名 + 状态字母」列表(与展开详情一一对应)。 */
    public String buildTitle() {
        StringBuilder sb = new StringBuilder();
        for (Summary s : buildSummaries()) {
            if (sb.length() > 0) {
                sb.append('、');
            }
            sb.append(s.fileName()).append(' ').append(gitLetter(s.changeType()));
        }
        return sb.toString();
    }

    /** 展开态正文:{ changes: [...] },与前端 TaskFileChangesTraceContent 同形。 */
    public ObjectNode buildContent() {
        ArrayNode arr = Json.arr();
        for (Summary s : buildSummaries()) {
            ObjectNode o = arr.addObject();
            o.put("filePath", s.filePath());
            o.put("fileName", s.fileName());
            o.put("changeType", s.changeType());
            o.put("beforeContent", s.beforeContent());
            o.put("afterContent", s.afterContent());
            o.put("saveCount", s.saveCount());
        }
        ObjectNode content = Json.obj();
        content.set("changes", arr);
        return content;
    }

    /**
     * 轻量摘要数组(随 rounds.jsonl 行内联,供 task.rounds 每轮行直接展示):
     * 仅含 filePath/fileName/changeType/saveCount 四个字段,不含 before/after 全文,
     * 全文保持 {@link #buildContent()} 单独落盘到 {@code file-changes/<roundId>.json}。
     */
    public ArrayNode buildLightSummary() {
        ArrayNode arr = Json.arr();
        for (Summary s : buildSummaries()) {
            ObjectNode o = arr.addObject();
            o.put("filePath", s.filePath());
            o.put("fileName", s.fileName());
            o.put("changeType", s.changeType());
            o.put("saveCount", s.saveCount());
        }
        return arr;
    }

    /** 元数据(数据版本 + 原始保存事件,供排查/扩展)。 */
    public ObjectNode buildMetadata() {
        ObjectNode meta = Json.obj();
        meta.put("pluginId", "task-file-changes");
        meta.put("version", 1);
        ArrayNode arr = Json.arr();
        synchronized (this) {
            for (Entry e : changes) {
                ObjectNode o = arr.addObject();
                o.put("agentId", e.agentId());
                o.put("source", e.source().name());
                o.put("filePath", e.filePath());
                o.put("changeType", e.changeType());
                o.put("savedAt", e.savedAt());
            }
        }
        meta.set("changes", arr);
        return meta;
    }

    /** 把保存事件合并到文件级聚合(仿 node 侧 mergeSummary)。 */
    private void merge(Entry entry) {
        Summary current = summaries.get(entry.filePath());
        if (current == null) {
            String changeType = "deleted".equals(entry.changeType())
                    ? "deleted"
                    : (entry.before().isEmpty() ? "created" : "updated");
            summaries.put(entry.filePath(), new Summary(entry.filePath(), entry.fileName(),
                    changeType, entry.before(), entry.after(), 1));
            return;
        }
        // 删除是终态:只要本回合出现过删除(无论之前是新建还是修改),净结果都是「删除」。
        String nextType;
        if ("deleted".equals(entry.changeType())) {
            nextType = "deleted";
        } else if ("created".equals(current.changeType())) {
            nextType = "created";
        } else {
            nextType = entry.before().isEmpty() ? "created" : "updated";
        }
        // 删除后最新内容为空;保留既有 beforeContent(删除前最后已知内容)供 diff 展示。
        String nextAfter = "deleted".equals(entry.changeType()) ? "" : entry.after();
        summaries.put(entry.filePath(), new Summary(entry.filePath(), current.fileName(),
                nextType, current.beforeContent(), nextAfter, current.saveCount() + 1));
    }

    /** 取文件名(按 / 规范化后取末段)。 */
    private static String fileNameOf(String filePath) {
        String normalized = filePath.replace('\\', '/');
        int idx = normalized.lastIndexOf('/');
        return idx >= 0 ? normalized.substring(idx + 1) : normalized;
    }

    /**
     * 把模型给的原始工具路径规范化为「完整业务路径」(对齐前端 pathUtils 坐标系):
     * <ul>
     *   <li>反斜杠归一为 '/',词法消解 '.'/'..' 段(向上越出根时截断在根);</li>
     *   <li>相对任务工作区根的裸/相对/前导斜杠路径 → 锚定为 '/a/b/c' 业务绝对路径
     *       (与前端资源树行路径、文件标签 id 同一形态;根级文件即 '/a.md',
     *       展开态「路径」列不再与文件名列重合——旧实现记录模型原始路径,
     *       根级文件两列内容一样);</li>
     *   <li>Windows 盘符('C:/…')/ UNC('//server/…')绝对路径(工作区外授权写)
     *       保持绝对路径形态,仅归一分隔符。</li>
     * </ul>
     */
    private static String normalizePathOf(String raw) {
        String s = raw.trim().replace('\\', '/');
        if (s.isEmpty()) {
            return s;
        }
        boolean drive = s.length() >= 2 && s.charAt(1) == ':' && Character.isLetter(s.charAt(0));
        if (drive || s.startsWith("//")) {
            return s;
        }
        List<String> parts = new ArrayList<>();
        for (String seg : s.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.remove(parts.size() - 1);
                }
                continue;
            }
            parts.add(seg);
        }
        return "/" + String.join("/", parts);
    }
}
