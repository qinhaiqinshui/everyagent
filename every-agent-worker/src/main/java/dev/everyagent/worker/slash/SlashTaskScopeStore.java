package dev.everyagent.worker.slash;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * slash 任务级 token 自管存储(slash 层自己读写 {@code <dataDir>/slash-tokens.json},
 * 不碰 TaskManager / TaskStore / meta.json)。依赖方向:task → slash(task 使用 slash),
 * slash 通过 {@link WorkerServices#dataDirOf(String)} 获取目录、
 * {@link WorkerServices#emitterOf(String)} 获取 emitter 广播变更。
 *
 * <p>apply/remove 读写 {@code slash-tokens.json}(JSON 字符串数组),运行中任务经 emitter
 * 发瞬态事件 {@code slash.tokens.changed};终态任务 emitter 为 null,仅落盘不广播。
 * 业务 onSelect 由调用方(SlashMethods.applyTaskToken)在 token 写入后另行触发。
 */
@Component
public class SlashTaskScopeStore {

    private static final Logger log = LoggerFactory.getLogger(SlashTaskScopeStore.class);

    private static final String FILE_NAME = "slash-tokens.json";

    private final WorkerServices workerServices;

    /**
     * 同一任务 token 改动的串行化锁(taskId → lock):worker 端 RPC 在独立虚拟线程并发执行,
     * slash.select 一次返回多个 bottom 胶囊时(如「无人值守」联动「AI 审议」),前端会对每个胶囊
     * 并行发 {@code slash.taskTokens.apply};若不加锁,两个 apply 的「读取文件快照 → 写回」
     * 交错时,后写方的<b>陈旧快照会覆盖</b>先写方刚新增的 token。此处对同一任务串行化
     * apply/remove 的「token 列表变更 + 落盘 + 广播」,根除该竞态。
     */
    private final java.util.Map<String, Object> taskLocks = new java.util.concurrent.ConcurrentHashMap<>();

    private Object lockOf(String taskId) {
        return taskLocks.computeIfAbsent(taskId, k -> new Object());
    }

    public SlashTaskScopeStore(WorkerServices workerServices) {
        this.workerServices = workerServices;
    }

    /**
     * 写入一条 slash 任务级 token(去重幂等)。
     * 不做 owner 隔离:ownerKey 参数仅为调用方签名兼容,不参与归属校验。
     *
     * @return true=已写入(重复项幂等不变);false=token 非合法 opaque / 任务不可寻。
     */
    public boolean apply(String taskId, String ownerKey, String token) {
        if (SlashTokenEncoder.parseToken(token) == null) {
            return false; // token 非合法 opaque
        }
        synchronized (lockOf(taskId)) {
            Path dir = workerServices.dataDirOf(taskId);
            if (dir == null) {
                return false; // 任务不可寻
            }
            List<String> tokens = readTokens(dir);
            if (!tokens.contains(token)) {
                tokens.add(token);
            }
            writeTokens(dir, tokens);
            emitChanged(taskId, tokens);
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
            Path dir = workerServices.dataDirOf(taskId);
            if (dir == null) {
                return false;
            }
            List<String> tokens = readTokens(dir);
            tokens.removeIf(t -> t.equals(token));
            writeTokens(dir, tokens);
            emitChanged(taskId, tokens);
            return true;
        }
    }

    /** 当前任务已存储的 slash 任务级 token 快照(只读)。 */
    public List<String> tokensOf(String taskId) {
        Path dir = workerServices.dataDirOf(taskId);
        if (dir == null) {
            return List.of();
        }
        return readTokens(dir);
    }

    // ---- 内部 ----

    /** 读取 {@code slash-tokens.json};文件不存在视为空列表。 */
    private List<String> readTokens(Path dir) {
        Path file = dir.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new ArrayList<>();
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            JsonNode node = Json.parse(content);
            List<String> tokens = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode n : node) {
                    if (n.isTextual()) {
                        tokens.add(n.asText());
                    }
                }
            }
            return tokens;
        } catch (IOException e) {
            log.error("slash 任务 token 读取失败 file={}", file, e);
            return new ArrayList<>();
        }
    }

    /** 写入 {@code slash-tokens.json}(JSON 字符串数组)。 */
    private void writeTokens(Path dir, List<String> tokens) {
        Path file = dir.resolve(FILE_NAME);
        try {
            ArrayNode arr = Json.arr();
            for (String t : tokens) {
                arr.add(t);
            }
            Files.writeString(file, Json.write(arr), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("slash 任务 token 落盘失败 file={}", file, e);
        }
    }

    /**
     * 经 emitter 发瞬态事件 {@code slash.tokens.changed}(REPLACE 整体替换)。
     * 终态任务 emitter 为 null,跳过广播。
     */
    private void emitChanged(String taskId, List<String> latestTokens) {
        EventEmitter emitter = workerServices.emitterOf(taskId);
        if (emitter == null) {
            return; // 终态:不广播
        }
        emitter.emit(EmitEvent.transientOf(
                SnowflakeId.next(),
                "slash.tokens.changed",
                null, null, null, null, null,
                latestTokens,
                EmitEvent.Mode.REPLACE));
    }
}
