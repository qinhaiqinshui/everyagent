package dev.everyagent.worker.slash;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.worker.task.TaskManager;
import dev.everyagent.worker.task.TaskStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * slash 任务级 token 公共存储(仅 slash 层读写任务 meta 的 {@code slashTaskTokens} 字段,
 * 不碰任何业务标记):apply/remove 在「运行中」与「终态(磁盘)」两条路径都落盘并广播
 * task.updated;业务 onSelect 由调用方(SlashMethods.applyTaskToken)在 token 写入后另行触发。
 */
@Component
public class SlashTaskScopeStore {

    private static final Logger log = LoggerFactory.getLogger(SlashTaskScopeStore.class);

    private final TaskManager taskManager;
    private final TaskStore store;

    /**
     * 同一任务 token 改动的串行化锁(taskId → lock):worker 端 RPC 在独立虚拟线程并发执行,
     * slash.select 一次返回多个 bottom 胶囊时(如「无人值守」联动「AI 审议」),前端会对每个胶囊
     * 并行发 {@code slash.taskTokens.apply};若不加锁,两个 apply 的「读取 summaryJson 快照 →
     * ATOMIC_MOVE 写 meta」交错时,后写方的<b>陈旧快照会覆盖</b>先写方刚新增的 token(meta 丢失
     * 更新,前端回显只剩其中一个胶囊)。此处对同一任务串行化 apply/remove 的「token 列表变更 +
     * meta 写入 + 广播」,根除该竞态。
     */
    private final java.util.Map<String, Object> taskLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private Object lockOf(String taskId) {
        return taskLocks.computeIfAbsent(taskId, k -> new Object());
    }

    public SlashTaskScopeStore(TaskManager taskManager, TaskStore store) {
        this.taskManager = taskManager;
        this.store = store;
    }

    /**
     * 写入一条 slash 任务级 token(仅 {@code slashTaskTokens} 字段)。
     * 不做 owner 隔离:ownerKey 参数仅为调用方签名兼容,不参与归属校验(apiKey 只认证、不决定归属)。
     *
     * @return true=已写入(重复项幂等不变);false=token 非合法 opaque / 任务不可寻。
     */
    public boolean apply(String taskId, String ownerKey, String token) {
        if (SlashTokenEncoder.parseToken(token) == null) {
            return false; // token 非合法 opaque
        }
        synchronized (lockOf(taskId)) {
            TaskEntry t = taskManager.runningTask(taskId);
            if (t != null) {
                // 运行中:内存槽(判空去重)→ 落盘 meta → 广播 task.updated
                t.addSlashTaskToken(token);
                store.updateMeta(taskId);
                taskManager.publishTaskUpdated(taskId);
                return true;
            }
            // 终态(未运行):改写磁盘 meta.json,严禁原地改共享 summary
            TaskStore.StoredTask st = taskManager.diskEntry(taskId);
            if (st == null) {
                return false; // 归属校验后的竞态兜底(任务刚被认领/删除)
            }
            ObjectNode meta = st.summary() == null ? Json.obj() : st.summary().deepCopy();
            ArrayNode arr = Json.arr();
            JsonNode old = meta.path("slashTaskTokens");
            boolean exists = false;
            if (old.isArray()) {
                for (JsonNode n : old) {
                    if (n.isTextual()) {
                        String s = n.asText();
                        if (s.equals(token)) {
                            exists = true;
                        }
                        arr.add(s);
                    }
                }
            }
            if (!exists) {
                arr.add(token);
            }
            meta.set("slashTaskTokens", arr);
            rewriteDisk(taskId, st, meta);
            return true;
        }
    }

    /**
     * 移除一条 slash 任务级 token(幂等:token 原本不存在也返回 true,只要任务存在)。
     * 不做 owner 隔离:ownerKey 参数仅为调用方签名兼容,不参与归属校验。
     *
     * @return false=任务不可寻。
     */
    public boolean remove(String taskId, String ownerKey, String token) {
        synchronized (lockOf(taskId)) {
            TaskEntry t = taskManager.runningTask(taskId);
            if (t != null) {
                t.removeSlashTaskToken(token);
                store.updateMeta(taskId);
                taskManager.publishTaskUpdated(taskId);
                return true;
            }
            TaskStore.StoredTask st = taskManager.diskEntry(taskId);
            if (st == null) {
                return false;
            }
            ObjectNode meta = st.summary() == null ? Json.obj() : st.summary().deepCopy();
            JsonNode old = meta.path("slashTaskTokens");
            ArrayNode kept = Json.arr();
            if (old.isArray()) {
                for (JsonNode n : old) {
                    if (n.isTextual()) {
                        String s = n.asText();
                        if (s.equals(token)) {
                            continue; // 过滤掉与目标相等的项
                        }
                        kept.add(s);
                    }
                }
            }
            if (kept.isEmpty()) {
                meta.remove("slashTaskTokens"); // 空数组移除字段更干净
            } else {
                meta.set("slashTaskTokens", kept);
            }
            rewriteDisk(taskId, st, meta);
            return true;
        }
    }

    /** 当前任务已存储的 slash 任务级 token 快照(只读,不含业务标记)。 */
    public List<String> tokensOf(String taskId) {
        return taskManager.slashTaskTokens(taskId);
    }

    /** 磁盘路径终态改写:落盘 meta → 回写磁盘索引 → 广播 task.updated。 */
    private void rewriteDisk(String taskId, TaskStore.StoredTask st, ObjectNode meta) {
        try {
            TaskStore.writeMeta(st.dir(), meta);
        } catch (IOException e) {
            log.error("slash 任务 token 落盘失败 task={}", taskId, e);
            return; // 落盘失败:不更新索引、不广播(磁盘仍是真相源);调用方按幂等成功应答
        }
        taskManager.replaceDiskEntry(new TaskStore.StoredTask(taskId, st.dir(), meta));
        taskManager.publishTaskUpdated(taskId);
    }
}
