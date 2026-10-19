package dev.everyagent.plugin.api.spi;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * per-run 工具执行结果账本 —— {@code tool.result} 事件 {@code data.status} 的<b>结构化</b>判定源。
 *
 * <p><b>为什么需要它</b>：Spring AI 2.0 的类型层面没有任何失败位——
 * {@code ToolResponseMessage.ToolResponse} 只有 {@code (id, name, responseData)}；工具抛出的异常已被
 * {@code ToolExecutionExceptionProcessor} 转成文本回灌，成功与失败在 {@code responseData} 上同形；
 * {@code ToolContext} 只是 {@code chatOptions.getToolContext()}（不含工具调用 id），
 * {@code ToolExecutionException} 只带 {@code ToolDefinition}。曾用「匹配失败文案」兜底，
 * 结果是「读/grep 到该文案源码」的命令被自指误判为失败。故改由执行侧在<b>知道结构化结果的那一刻</b>
 * 记账（shell 退出码 / 工具抛异常），发射侧按结构化键查账。
 *
 * <p><b>键怎么定</b>：框架不把工具调用 id 交给工具层，唯一双方都能确定性复现的是
 * 「工具名 + 该次调用可复现的载荷」——shell 用命令串（执行器与工具入参都有），
 * 抛异常用入参 JSON（{@code ToolCallback.call} 收到的原串与 {@code ToolCall.arguments()} 同源）。
 * 同一轮里同键重复调用时按 FIFO 消费，与调用顺序一致。per-call 工具执行链落地后，
 * 切口握有 callId：链上行按 callId 写账（{@link #recordCallOutcome}），后置消费者
 * 直查 callId，不再依赖键拼合（见类尾 callId 段；旧键 API 保留至消费方全部迁移）。
 *
 * <p><b>生命周期</b>：per-run（挂在 agent 实体上，随 run 结束回收），线程安全
 * （工具执行可能切换线程，故不得用 ThreadLocal —— 见 {@code InterceptingToolCallingManager} 注释）。
 */
public final class ToolOutcomeLog {

    private final Map<String, ConcurrentLinkedQueue<ShellExit>> shellExits = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentLinkedQueue<Boolean>> toolThrows = new ConcurrentHashMap<>();
    /** 「曾抛过异常」键影子集合(非消费存在性查询的供体,见 {@link #toolThrew})。 */
    private final Set<String> throwMarks = ConcurrentHashMap.newKeySet();

    /** 命令执行的结局（结构化）：退出码 + 是否被沙箱超时中止。 */
    public record ShellExit(int exitCode, boolean aborted) {

        /** 是否算失败：非零退出码即失败（用户决策 2026-12：非 0 都标红），超时中止同样算失败。 */
        public boolean failed() {
            return exitCode != 0 || aborted;
        }
    }

    /**
     * 记录一次命令执行结局（由 shell 执行器在拿到 {@code ExecResult} 处调用）。
     *
     * @param toolName 工具名（{@code powershell} / {@code bash}），与 {@code ShellExecutor} 收到的
     *                 {@code shell} 参数一致
     * @param command  本次执行的命令原文（与工具入参 {@code command} 逐字一致，键的构成部分）
     */
    public void recordShellExit(String toolName, String command, int exitCode, boolean aborted) {
        if (toolName == null || command == null) {
            return;
        }
        queue(shellExits, key(toolName, command)).add(new ShellExit(exitCode, aborted));
    }

    /**
     * 取出并移除一次命令执行结局（FIFO）。返回 {@code null} 表示本次执行没有记账
     * （非 shell 工具，或执行器尚未接入），调用方应回退到其它判据而非臆断失败。
     */
    public ShellExit consumeShellExit(String toolName, String command) {
        if (toolName == null || command == null) {
            return null;
        }
        ConcurrentLinkedQueue<ShellExit> q = shellExits.get(key(toolName, command));
        return q == null ? null : q.poll();
    }

    /**
     * 记录一次工具调用抛出异常（由 worker 的 per-call 工具回调包装器调用）。
     * 正常返回不记账——查不到即视为成功，避免为每条成功结果也写一份。
     */
    public void recordToolThrow(String toolName, String arguments) {
        if (toolName == null) {
            return;
        }
        String k = key(toolName, arguments == null ? "" : arguments);
        queue(toolThrows, k).add(Boolean.TRUE);
        throwMarks.add(k);
    }

    /** 取出并移除一次「该次调用抛过异常」的记录（FIFO）；{@code false} 表示未记录（=成功）。 */
    public boolean consumeToolThrow(String toolName, String arguments) {
        if (toolName == null) {
            return false;
        }
        ConcurrentLinkedQueue<Boolean> q = toolThrows.get(key(toolName, arguments == null ? "" : arguments));
        return q != null && q.poll() != null;
    }

    /**
     * 非消费查询:该键在本 run 内是否曾记录过「抛异常」。
     *
     * <p><b>为什么需要它</b>:FIFO {@link #consumeToolThrow} 是<b>消费</b>语义,供首个查账者
     * ({@code WorkerToolEventAdvisor} 的 {@code tool.result} status)按次精确裁决;但后置消费者
     * (如 file-change 插件在下一轮请求入口补判工具成败)拿到的时序必然晚于首个查账者,
     * 队列已被 poll 空——只能查「曾抛过」的存在性影子集合({@link #throwMarks},随 run 生命周期
     * 累积,仅含抛过异常的键,量级极小)。
     *
     * <p><b>精度边界</b>:存在性 ≠ 按次精确。同键(工具名 + 入参逐字节相同的两次调用)一败一成时,
     * 本查询对成功的那次也会返回 true(偏保守)。对 file-change 场景,同参数的 create_file/update_file
     * 原样重发(失败后不改参数)概率≈0,可接受;需要按次精确裁决的消费方仍应走
     * {@link #consumeToolThrow}。
     */
    public boolean toolThrew(String toolName, String arguments) {
        if (toolName == null) {
            return false;
        }
        return throwMarks.contains(key(toolName, arguments == null ? "" : arguments));
    }

    // ---- callId 记账(per-call 链上行写账,架构 §7.14.3) ----
    //
    // per-call 工具执行链的切口握有工具调用 id(框架类型层不提供,链切口独有):链上行
    // (发射 tool.result 处)按失败前缀约定 + 旧结构化键判定成败后,以 callId 写账一次——
    // 后置消费者(file-change 一阶段记账等)按 callId 直查,不再依赖「工具名+载荷」
    // 的可复现键拼合。旧键 API(shell 退出码 / 抛异常)保留至消费方全部迁移。

    private final Map<String, Boolean> callOutcomes = new ConcurrentHashMap<>();

    /**
     * 记录一次工具调用的结局(链上行按 callId 写账;同 callId 后写覆盖——每个 callId
     * 在一次调用生命周期内只写一次)。
     *
     * @param callId 工具调用 id(与 {@code message.toolCalls[].id} / {@code tool.result.callId} 同源)
     * @param failed 成败(链上单点判定:失败前缀约定 + 结构化账本兜底)
     */
    public void recordCallOutcome(String callId, boolean failed) {
        if (callId == null) {
            return;
        }
        callOutcomes.put(callId, failed);
    }

    /**
     * 非消费查询一次调用的结局(按 callId;file-change 一阶段记账等后置消费者的查询键)。
     *
     * @return {@code Boolean.TRUE} = 失败 / {@code Boolean.FALSE} = 成功 /
     *         {@code null} = 无记账(该调用未经链上行——如守卫 WARN 轮合成/裸用场景)
     */
    public Boolean callOutcome(String callId) {
        return callId == null ? null : callOutcomes.get(callId);
    }

    /** 键 = 工具名 + NUL 分隔 + 载荷（NUL 不出现在工具名与命令/JSON 文本里，避免歧义拼接）。 */
    private static String key(String toolName, String payload) {
        return toolName + '\u0000' + payload;
    }

    /**
     * 取（必要时创建）该键的 FIFO 队列。
     *
     * <p>值类型用具体类 {@link ConcurrentLinkedQueue} 而非 {@code Deque} 接口：本模块编译时
     * javac 对「泛型 helper 里以 {@code Deque<E>} 为目标类型的菱形 new」推断失败
     * （报「不存在类型变量 E 的实例,使 ConcurrentLinkedQueue&lt;E&gt; 与 Deque&lt;E&gt; 一致」，
     * 同样的写法直接内联则正常），改用具体类型即可，语义等价。
     */
    private static <E> ConcurrentLinkedQueue<E> queue(
            Map<String, ConcurrentLinkedQueue<E>> map, String key) {
        ConcurrentLinkedQueue<E> existing = map.get(key);
        if (existing != null) {
            return existing;
        }
        ConcurrentLinkedQueue<E> created = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<E> prev = map.putIfAbsent(key, created);
        return prev != null ? prev : created;
    }
}