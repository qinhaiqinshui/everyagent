package dev.everyagent.contract.schema;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.everyagent.contract.json.Json;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * events.schema.json 契约校验(架构 §3.3)。用 networknt json-schema-validator 按
 * draft 2020-12 校验(与 schema $schema 一致)。回归点:streamEvent.event 已含 task.trace,
 * payload 形状与前端 TaskTraceRecord 同形(traceId/kind/title/summary/content/status/
 * metadata/createdAt),kind=auth.review 的 metadata 携带审议字段且放行。
 */
class EventsSchemaTest {

    /** 合法 stream 频道:u.&lt;64 位 hex&gt;.worker.&lt;workerId&gt;.task.&lt;taskId&gt;.stream(worker 段 = 归属隔离)。 */
    private static final String STREAM_CHANNEL =
            "u." + "0123456789abcdef".repeat(4) + ".worker.company-pc.task.demo.stream";

    /** 合法 tasks 频道:u.&lt;64 位 hex&gt;.worker.&lt;workerId&gt;.tasks。 */
    private static final String TASKS_CHANNEL =
            "u." + "0123456789abcdef".repeat(4) + ".worker.company-pc.tasks";

    private static Schema schema() {
        try (InputStream in = EventsSchemaTest.class
                .getResourceAsStream("/schema/events.schema.json")) {
            if (in == null) {
                throw new IllegalStateException("classpath 找不到 /schema/events.schema.json");
            }
            SchemaRegistry registry =
                    SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
            return registry.getSchema(in);
        } catch (Exception e) {
            throw new IllegalStateException("schema 加载失败", e);
        }
    }

    private static List<Error> validate(String json) {
        return schema().validate(Json.parse(json));
    }

    @Test
    void authReviewTracePasses() {
        // 合法 task.trace:kind=auth.review,metadata 含全部审议字段
        String json = """
                {
                  "channel": "%s",
                  "event": "task.trace",
                  "payload": {
                    "traceId": "trace_abc123",
                    "kind": "auth.review",
                    "title": "AI 安全审议",
                    "summary": "已授权写入工作区外路径",
                    "content": "allow: 写入 /tmp/x.conf",
                    "status": "done",
                    "createdAt": 1756000000000,
                    "metadata": {
                      "decision": "ALLOW",
                      "confidence": 0.92,
                      "reason": "路径在 scope 内且操作只读",
                      "scope": "run",
                      "grantKey": "w.docs",
                      "prompt": "写入 /tmp/x.conf",
                      "taskId": "task_123",
                      "agentId": "review-abc"
                    }
                  }
                }
                """.formatted(STREAM_CHANNEL);
        List<Error> errors = validate(json);
        assertTrue(errors.isEmpty(), () -> "合法 auth.review trace 应通过校验:" + errors);
    }

    @Test
    void nonAuthReviewTracePasses() {
        // 既有 kind(request_retry / task_duration)不受新增 auth.review 影响,仍合法
        String json = """
                {
                  "channel": "%s",
                  "event": "task.trace",
                  "payload": {
                    "traceId": "trace_retry_1",
                    "kind": "request_retry",
                    "summary": "第 1/3 次失败,5s 后重试",
                    "status": "retrying",
                    "createdAt": 1756000000000,
                    "metadata": { "attempt": 1, "maxAttempts": 3 }
                  }
                }
                """.formatted(STREAM_CHANNEL);
        List<Error> errors = validate(json);
        assertTrue(errors.isEmpty(), () -> "合法 request_retry trace 应通过校验:" + errors);
    }

    @Test
    void authReviewMetadataIsFreeForm() {
        // metadata 为各 kind 自定义:即使带额外/缺失字段也不因 metadata 拒绝
        // (metadata 不做严格枚举,未知字段 must-ignore)
        String json = """
                {
                  "channel": "%s",
                  "event": "task.trace",
                  "payload": {
                    "traceId": "trace_xyz",
                    "kind": "auth.review",
                    "createdAt": 1756000000000,
                    "metadata": { "decision": "DENY", "reason": "越权", "extra": { "n": 1 } }
                  }
                }
                """.formatted(STREAM_CHANNEL);
        List<Error> errors = validate(json);
        assertTrue(errors.isEmpty(), () -> "metadata 自由对象应通过校验:" + errors);
    }

    @Test
    void unknownEventFails() {
        // event 不在 enum → 整体不匹配任何事件类型 → 校验失败
        String json = """
                {
                  "channel": "%s",
                  "event": "bogus.event",
                  "payload": { "text": "x" }
                }
                """.formatted(STREAM_CHANNEL);
        assertFalse(validate(json).isEmpty(), "event 不在 enum 的帧应校验失败");
    }

    @Test
    void taskTraceMissingRequiredFieldsFails() {
        // task.trace 缺必填 traceId / kind → payload 校验失败
        String json = """
                {
                  "channel": "%s",
                  "event": "task.trace",
                  "payload": { "createdAt": 1756000000000 }
                }
                """.formatted(STREAM_CHANNEL);
        assertFalse(validate(json).isEmpty(), "缺 traceId/kind 的 task.trace 应校验失败");
    }

    @Test
    void taskUpdatedWithWorkerIdPasses() {
        // tasks 事件:worker 段频道 + payload.workerId 必填(架构 §5.2/§5.3)
        String json = """
                {
                  "channel": "%s",
                  "event": "task.updated",
                  "payload": {
                    "taskId": "t_k3f0",
                    "workerId": "company-pc",
                    "status": "running",
                    "createdAt": 1756000000000
                  }
                }
                """.formatted(TASKS_CHANNEL);
        List<Error> errors = validate(json);
        assertTrue(errors.isEmpty(), "合法 task.updated 应通过校验: " + errors);
    }

    @Test
    void taskUpdatedMissingWorkerIdFails() {
        // payload 缺 workerId → 归属不可判定,必须拒绝(worker 实现曾漏发该字段)
        String json = """
                {
                  "channel": "%s",
                  "event": "task.updated",
                  "payload": { "taskId": "t_k3f0", "status": "running", "createdAt": 1756000000000 }
                }
                """.formatted(TASKS_CHANNEL);
        assertFalse(validate(json).isEmpty(), "缺 workerId 的 task.updated 应校验失败");
    }

    @Test
    void ownerKeyLevelTasksChannelRejected() {
        // 回归锁:旧形态 u.<K>.tasks(无 worker 段)不再合法 —— 同 apiKey 两台 worker 会互相串台
        String legacyChannel = "u." + "0123456789abcdef".repeat(4) + ".tasks";
        String json = """
                {
                  "channel": "%s",
                  "event": "task.updated",
                  "payload": {
                    "taskId": "t_k3f0",
                    "workerId": "company-pc",
                    "status": "running",
                    "createdAt": 1756000000000
                  }
                }
                """.formatted(legacyChannel);
        assertFalse(validate(json).isEmpty(), "无 worker 段的 tasks 频道应校验失败");
    }

    @Test
    void streamChannelWithoutWorkerSegmentRejected() {
        // 回归锁:旧形态 u.<K>.task.<id>.stream(无 worker 段)不再合法 —— hub 的订阅通知无法定向
        String legacyChannel = "u." + "0123456789abcdef".repeat(4) + ".task.demo.stream";
        String json = """
                {
                  "channel": "%s",
                  "event": "message",
                  "payload": { "text": "hi" }
                }
                """.formatted(legacyChannel);
        assertFalse(validate(json).isEmpty(), "无 worker 段的 stream 频道应校验失败");
    }
}
