// ea: 引擎测试桩 —— WorkerServices/AgentFactory/Agent/NativeExec 全部就位（不依赖 worker，§14.9 红线）
package dev.everyagent.plugin.workflow;

import java.io.IOException;

import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.tool.ToolCallback;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.agent.Agent;
import dev.everyagent.plugin.api.agent.AgentActivity;
import dev.everyagent.plugin.api.agent.AgentBuilder;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.agent.AgentFactory;
import dev.everyagent.plugin.api.agent.ConversationArchive;
import dev.everyagent.plugin.api.event.Usage;
import dev.everyagent.plugin.api.event.Events.ToolCallPart;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.model.EventEmitter;
import dev.everyagent.plugin.api.model.ModelConfig;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.spi.ExecResult;
import dev.everyagent.plugin.api.spi.NativeExec;
import dev.everyagent.plugin.api.spi.WorkspaceManager;
import dev.everyagent.plugin.api.task.RoundClosedListener;
import dev.everyagent.plugin.api.spi.FileWriteListener;
import dev.everyagent.plugin.api.task.TaskService;
import dev.everyagent.plugin.api.task.TaskStoreService;
import dev.everyagent.plugin.api.event.StreamEmitter;
import dev.everyagent.plugin.api.spi.IdGenerator;
import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.config.WorkerConfig;
import dev.everyagent.plugin.api.slash.SlashTokenService;
import dev.everyagent.plugin.workflow.engine.AgentSessions;
import dev.everyagent.plugin.workflow.model.AgentEventLine;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 引擎测试桩家族：FakeWorkerServices（agentFactoryFor 注入点伪造 AgentEntity 行为）+
 * FakeNativeExec（ProcessBuilder 真执行，argv 直传与 worker OsSandbox 同语义）。
 */
final class Fakes {

    private Fakes() {
    }

    // ══════════════════════════ WorkerServices ══════════════════════════

    /** agentFactoryFor 注入点返回伪造工厂；其余服务按需供给，未用方法抛 UnsupportedOperationException。 */
    static final class FakeWorkerServices implements WorkerServices {

        final WorkspaceManager workspaces;
        final NativeExec nativeExec;
        final FakeAgentFactory factory;
        final List<ModelConfig> modelConfigs;

        FakeWorkerServices(WorkspaceManager workspaces, NativeExec nativeExec,
                FakeAgentFactory factory, List<ModelConfig> modelConfigs) {
            this.workspaces = workspaces;
            this.nativeExec = nativeExec;
            this.factory = factory;
            this.modelConfigs = modelConfigs;
        }

        @Override
        public SandboxBackend sandbox() {
            throw new UnsupportedOperationException();
        }

        @Override
        public NativeExec nativeExec() {
            return nativeExec;
        }

        @Override
        public WorkspaceManager workspaces() {
            return workspaces;
        }

        @Override
        public TokenEstimator tokenEstimator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SlashTokenService slashTokens() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.everyagent.plugin.api.interaction.InteractionService interaction() {
            throw new UnsupportedOperationException();
        }

        @Override
        public WorkerConfig config() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IdGenerator ids() {
            throw new UnsupportedOperationException();
        }

        @Override
        public StreamEmitter stream() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Path dataDirOf(String subjectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EventEmitter emitterOf(String subjectId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addRoundClosedListener(RoundClosedListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addFileWriteListener(FileWriteListener listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String toSandboxPath(Path hostPath) {
            return hostPath.toString();
        }

        @Override
        public AgentFactory agentFactoryFor(ExecContext exec) {
            factory.bound(exec);
            return factory;
        }

        @Override
        public List<ModelConfig> modelConfigs() {
            return modelConfigs;
        }

        @Override
        public TaskService task() {
            throw new UnsupportedOperationException();
        }

        @Override
        public TaskStoreService store() {
            throw new UnsupportedOperationException();
        }
    }

    // ══════════════════════════ AgentFactory / Builder / Agent ══════════════════════════

    /** 伪造工厂：按 agentKey（# 前的裸名）解析行为脚本；build() 时注册进绑定主体的 agents()。 */
    static final class FakeAgentFactory implements AgentFactory {

        volatile ExecContext bound;
        final Map<String, Function<String, Script>> scripts = new ConcurrentHashMap<>();
        volatile Function<String, Script> defaultScript = key -> Script.echo("ok:" + key);
        /** 最近一次 build 出的 FakeAgent（时序断言用）。 */
        final AtomicReference<FakeAgent> lastBuilt = new AtomicReference<>();
        /** 全部 build 出的 FakeAgent（时序/种子断言用）。 */
        final java.util.List<FakeAgent> built = new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 收到的 REMOVE 剔除表（工具面断言用）。 */
        final java.util.List<java.util.List<ToolCallback>> removeTables =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        /** 指定 agent 名的行为脚本（缺省脚本兜底）。 */
        void script(String agentName, Function<String, Script> resolver) {
            scripts.put(agentName, resolver);
        }

        void bound(ExecContext exec) {
            this.bound = exec;
        }

        private Script resolve(String agentKey) {
            String name = agentKey.contains("#")
                    ? agentKey.substring(0, agentKey.lastIndexOf('#')) : null;
            Function<String, Script> r = name != null ? scripts.get(name) : null;
            return (r != null ? r : defaultScript).apply(agentKey);
        }

        @Override
        public AgentBuilder create(String agentId) {
            return new FakeAgentBuilder(bound, agentId, null, this);
        }

        @Override
        public AgentBuilder create(String agentId, String configId) {
            return new FakeAgentBuilder(bound, agentId, configId, this);
        }
    }

    /** Agent 行为脚本：最终文本 / 工具轮 / 延迟 / 抛错。 */
    static final class Script {

        final String text;
        final List<String[]> toolTurns; // {callId, name, arguments, resultText}
        final long delayMs;
        final RuntimeException error;

        private Script(String text, List<String[]> toolTurns, long delayMs, RuntimeException error) {
            this.text = text;
            this.toolTurns = toolTurns;
            this.delayMs = delayMs;
            this.error = error;
        }

        static Script echo(String text) {
            return new Script(text, List.of(), 0, null);
        }

        static Script delayed(String text, long delayMs) {
            return new Script(text, List.of(), delayMs, null);
        }

        static Script failing(RuntimeException error) {
            return new Script("", List.of(), 0, error);
        }

        static Script withTools(String text, String[]... turns) {
            return new Script(text, List.of(turns), 0, null);
        }
    }

    /** 伪造 builder：捕获装配参数（seed/剔除表/configId 供断言），build 出 FakeAgent。 */
    static final class FakeAgentBuilder implements AgentBuilder {

        final ExecContext exec;
        final String agentId;
        final String configId;
        final FakeAgentFactory factory;
        String title = "";
        String creator;
        String systemPrompt;
        String userInput;
        List<Message> seed;
        List<ToolCallback> removedTools;
        boolean built;

        FakeAgentBuilder(ExecContext exec, String agentId, String configId, FakeAgentFactory factory) {
            this.exec = exec;
            this.agentId = agentId;
            this.configId = configId;
            this.factory = factory;
        }

        @Override
        public AgentBuilder title(String title) {
            this.title = title;
            return this;
        }

        @Override
        public AgentBuilder creator(String creator) {
            this.creator = creator;
            return this;
        }

        @Override
        public AgentBuilder agentMetadata(Map<String, Object> metadata) {
            return this;
        }

        @Override
        public AgentBuilder tools(List<ToolCallback> tools, ModifyMode mode) {
            if (mode == ModifyMode.REMOVE) {
                this.removedTools = tools;
                factory.removeTables.add(tools);
            }
            return this;
        }

        @Override
        public AgentBuilder systemPrompt(String prompt) {
            this.systemPrompt = prompt;
            return this;
        }

        @Override
        public AgentBuilder userInput(String input) {
            this.userInput = input;
            return this;
        }

        @Override
        public AgentBuilder conversation(List<Message> conversation) {
            this.seed = conversation;
            return this;
        }

        @Override
        public AgentBuilder options(Consumer<ChatOptions> customizer) {
            return this;
        }

        @Override
        public Agent build() {
            if (built) {
                throw new IllegalStateException("builder 已 build");
            }
            built = true;
            FakeAgent agent = new FakeAgent(agentId, title, factory.resolve(agentId),
                    seed == null ? List.of() : seed, userInput, systemPrompt,
                    exec == null ? null : exec.emitter());
            if (exec != null) {
                exec.agents().put(agentId, agent);
            }
            factory.lastBuilt.set(agent);
            factory.built.add(agent);
            return agent;
        }
    }

    /**
     * 伪造 Agent（plugin-api Agent 接口的可控桩）：run() 按 Script 发射与 worker 同形的
     * message/tool.result/usage 事件（agentId 显式携带——真实 AgentEntity 的 emitter 包装
     * 等价物），维护 conversation/lastText/usage 与 claimTerminal CAS 终态。
     */
    static final class FakeAgent implements Agent {

        final String agentId;
        final String title;
        final Script script;
        final List<Message> seed;
        final String userInput;
        final String systemPrompt;
        final EventEmitter upstream;
        final List<Message> conversation;
        final long created = System.currentTimeMillis();

        volatile String lastText = "";
        volatile boolean finished;
        volatile String status = "running";
        final AtomicBoolean terminalClaimed = new AtomicBoolean();
        volatile Usage usage = Usage.zero();
        long runStartedAt;
        long runEndedAt;

        FakeAgent(String agentId, String title, Script script, List<Message> seed,
                String userInput, String systemPrompt, EventEmitter upstream) {
            this.agentId = agentId;
            this.title = title;
            this.script = script;
            this.seed = seed;
            this.userInput = userInput;
            this.systemPrompt = systemPrompt;
            this.upstream = upstream;
            this.conversation = new java.util.ArrayList<>(seed);
        }

        /** agent 级 emitter 包装（与 AgentEntity 同构：agentId 缺省自动填本 agent）。 */
        void emit(String kind, String content, JsonNode data) {
            if (upstream == null) {
                return;
            }
            upstream.emit(EmitEvent.of(SnowflakeId.next(), kind, agentId,
                    null, null, content, null, data, EmitEvent.Mode.REPLACE));
        }

        /** tool.result：结果串走 summary 槽（ToolEventInterceptor 同形；loader 谓词首选 summary）。 */
        void emitToolResult(String resultText, JsonNode data) {
            if (upstream == null) {
                return;
            }
            upstream.emit(EmitEvent.of(SnowflakeId.next(), Events.TOOL_RESULT, agentId,
                    null, resultText, null, null, data, EmitEvent.Mode.REPLACE));
        }

        @Override
        public void run() throws InterruptedException {
            runStartedAt = System.nanoTime();
            try {
                if (script.error != null) {
                    throw script.error;
                }
                conversation.add(new org.springframework.ai.chat.messages.UserMessage(
                        userInput == null ? "" : userInput));
                long rid = SnowflakeId.next();
                if (!script.toolTurns.isEmpty()) {
                    var data = Json.obj();
                    var calls = Json.arr();
                    for (String[] turn : script.toolTurns) {
                        calls.add(Json.toJson(new ToolCallPart(turn[0], turn[1], turn[2])));
                    }
                    data.set("toolCalls", calls);
                    data.put("thinking", "");
                    emit(Events.MESSAGE, script.text, data);
                    for (String[] turn : script.toolTurns) {
                        var rd = Json.obj().put("callId", turn[0]).put("name", turn[1])
                                .put("status", "success").put("durationMs", 3L);
                        emitToolResult(turn[3], rd); // 结果串走 summary 槽，同 ToolEventInterceptor
                    }
                }
                if (script.delayMs > 0) {
                    Thread.sleep(script.delayMs);
                }
                emit(Events.MESSAGE, script.text, Json.obj().put("thinking", "").set("toolCalls", Json.arr()));
                var ud = Json.obj().put("model", "fake-model").put("contextWindowTokens", 1000000)
                        .set("round", Json.toJson(new Usage(10, 5, 15, 0)))
                        .set("total", Json.toJson(new Usage(100, 50, 150, 0)));
                emit(Events.USAGE, null, ud);
                conversation.add(org.springframework.ai.chat.messages.AssistantMessage.builder()
                        .content(script.text).build());
                lastText = script.text;
                usage = new Usage(100, 50, 150, 0);
                claimTerminal("completed");
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                claimTerminal("stopped", "已取消");
                throw ie;
            } catch (RuntimeException t) {
                claimTerminal("error", String.valueOf(t.getMessage()));
                throw t;
            } finally {
                finished = true;
                runEndedAt = System.nanoTime();
            }
        }

        @Override
        public String agentId() {
            return agentId;
        }

        @Override
        public String title() {
            return title;
        }

        @Override
        public String creator() {
            return "workflow";
        }

        @Override
        public long createdAt() {
            return created;
        }

        @Override
        public EventEmitter emitter() {
            return e -> upstream == null ? 0L
                    : upstream.emit(e.agentId() == null ? e.withAgentId(agentId) : e);
        }

        @Override
        public String status() {
            return status;
        }

        @Override
        public boolean finished() {
            return finished;
        }

        @Override
        public void finished(boolean finished) {
            this.finished = finished;
        }

        @Override
        public String lastText() {
            return lastText;
        }

        @Override
        public List<Message> conversation() {
            return conversation;
        }

        @Override
        public ChatModel chatModel() {
            return null;
        }

        @Override
        public String currentModel() {
            return null;
        }

        @Override
        public Usage lastRound() {
            return null;
        }

        @Override
        public String lastModel() {
            return null;
        }

        @Override
        public Usage usage() {
            return usage;
        }

        @Override
        public AgentActivity activity() {
            return null;
        }

        @Override
        public void updateActivity(String reasoning, String content, String error) {
        }

        @Override
        public void resetForRerun() {
        }

        @Override
        public boolean claimTerminal(String status) {
            if (!terminalClaimed.compareAndSet(false, true)) {
                return false;
            }
            this.status = status;
            this.finished = true;
            return true;
        }

        @Override
        public ExecContext execution() {
            return null;
        }
    }

    // ══════════════════════════ 任务 ExecContext ══════════════════════════

    /**
     * 伪造任务 ExecContext（origin 任务绑定桩，§7.20.8）：emitter 记录事件并按
     * {@code ext.persist} 落 {@code <taskDir>/<agentId>.jsonl}（行形态照抄 task 域
     * per-agent jsonl）；conversationArchive 从同目录读档重建（AgentSessions 谓词）——
     * 「发射→落盘→档案重建」整环与 worker 任务域同构。terminal 可置 true 模拟任务收口。
     */
    static final class FakeTaskExec implements ExecContext {

        final String taskId;
        final String workspaceId;
        final String workspaceRoot;
        final Path taskDir;
        final FakeAgentFactory factory;
        /** 全部发射过的事件（含瞬态；时序/归户断言用）。 */
        final List<EmitEvent> emitted = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Map<String, AgentContext> agents = new ConcurrentHashMap<>();
        volatile boolean terminal;

        FakeTaskExec(Path workspacesRoot, String workspaceId, String taskId,
                FakeAgentFactory factory) {
            this.taskId = taskId;
            this.workspaceId = workspaceId;
            this.workspaceRoot = workspacesRoot.toString();
            this.taskDir = workspacesRoot.resolve(workspaceId).resolve("tasks").resolve(taskId);
            this.factory = factory;
            try {
                java.nio.file.Files.createDirectories(taskDir);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String subjectId() {
            return taskId;
        }

        @Override
        public String workspaceRoot() {
            return workspaceRoot;
        }

        @Override
        public String workspaceId() {
            return workspaceId;
        }

        @Override
        public ModelConfig snapshot() {
            return new ModelConfig(null, null, null, null, null);
        }

        @Override
        public EventEmitter emitter() {
            return e -> {
                emitted.add(e);
                if (e.persist()) {
                    appendLine(e);
                }
                return e.id();
            };
        }

        @Override
        public AgentFactory agentFactory() {
            factory.bound(this);
            return factory;
        }

        @Override
        public Map<String, Object> metadata() {
            return new ConcurrentHashMap<>();
        }

        @Override
        public Path dataDir() {
            return taskDir;
        }

        @Override
        public boolean terminal() {
            return terminal;
        }

        @Override
        public dev.everyagent.plugin.api.interaction.InteractionService interaction() {
            return null;
        }

        @Override
        public ConversationArchive conversationArchive() {
            return agentId -> {
                try {
                    return AgentSessions.rebuild(agentLines(agentId));
                } catch (RuntimeException e) {
                    return List.of();
                }
            };
        }

        @Override
        public Map<String, AgentContext> agents() {
            return agents;
        }

        /** 持久事件追加 per-agent jsonl（行序 = 发射序；与 task 域同形）。 */
        private synchronized void appendLine(EmitEvent e) {
            try {
                AgentEventLine line = new AgentEventLine(Long.toString(e.id()),
                        System.currentTimeMillis(), e.kind(), e.agentId(),
                        AgentSessions.projectPayload(e));
                java.nio.file.Files.writeString(taskDir.resolve(fileName(e.agentId())),
                        Json.write(line) + "\n",
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
        }

        /** 读指定 agent 的任务档案行（坏行跳过；无档案空表）。 */
        List<AgentEventLine> agentLines(String agentId) {
            return readLines(taskDir.resolve(fileName(agentId)));
        }

        /** 按前缀聚合全部匹配档案的行（匿名 agentId 含 runNo，测试不便全名时用）。 */
        List<AgentEventLine> agentLinesByPrefix(String agentIdPrefix) {
            List<AgentEventLine> out = new java.util.ArrayList<>();
            try (var s = java.nio.file.Files.list(taskDir)) {
                for (Path p : s.filter(f -> f.getFileName().toString()
                        .startsWith(agentIdPrefix)).sorted().toList()) {
                    out.addAll(readLines(p));
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            out.sort(java.util.Comparator.comparingLong(l -> Long.parseLong(l.seq())));
            return out;
        }

        private static List<AgentEventLine> readLines(Path file) {
            if (!java.nio.file.Files.isRegularFile(file)) {
                return List.of();
            }
            List<AgentEventLine> out = new java.util.ArrayList<>();
            try {
                for (String raw : java.nio.file.Files.readAllLines(file)) {
                    String line = raw.trim();
                    if (line.isEmpty()) {
                        continue;
                    }
                    try {
                        out.add(Json.MAPPER.readValue(line, AgentEventLine.class));
                    } catch (RuntimeException ignored) {
                        // 坏行跳过（与 task 域读侧容错同纪律）
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return out;
        }

        private static String fileName(String agentId) {
            return (agentId == null || agentId.isEmpty() ? "main" : agentId) + ".jsonl";
        }
    }

    // ══════════════════════════ NativeExec ══════════════════════════

    /**
     * ProcessBuilder 真执行（argv 直传、超时/中断均 destroyForcibly——与 worker OsSandbox 语义对齐）；
     * {@code spawnPowerShell} 与生产 {@code OsSandbox#spawnPowerShell} 同构管线
     * （组装件全部复用 plugin-api，不依赖 worker，§14.9 红线）。
     */
    static final class RealNativeExec implements NativeExec {
        @Override
        public ExecResult spawnNative(String[] argv, Path cwd, Map<String, String> env,
                long timeoutMs) {
            Process p = null;
            try {
                ProcessBuilder pb = new ProcessBuilder(argv).directory(cwd.toFile());
                p = pb.start();
                final Process proc = p;
                StringBuilder out = new StringBuilder();
                StringBuilder err = new StringBuilder();
                Thread t1 = Thread.ofVirtual().start(() -> read(proc.getInputStream(), out));
                Thread t2 = Thread.ofVirtual().start(() -> read(proc.getErrorStream(), err));
                boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!done) {
                    p.destroyForcibly();
                    t1.join(2000);
                    t2.join(2000);
                    return new ExecResult(out.toString(), err.toString(), -1, true);
                }
                t1.join(2000);
                t2.join(2000);
                return new ExecResult(out.toString(), err.toString(), p.exitValue(), false);
            } catch (IOException e) {
                return new ExecResult("", "spawn 失败: " + e.getMessage(), -1, true);
            } catch (InterruptedException e) {
                // 与 OsSandbox 对齐：中断（取消/失败级联）同样不留子进程——销毁后同步收割
                // （Windows 句柄释放异步于 TerminateProcess,不等会让孤儿短暂持有 cwd 锁死
                // 测试 @TempDir 清理）;先清中断标志再等,等待完恢复
                boolean interrupted = Thread.interrupted();
                try {
                    if (p != null) {
                        p.destroyForcibly();
                        p.waitFor(2, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException ie) {
                    // 收割等待自身被中断：放弃等待（尽力而为）
                } finally {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new ExecResult("", "interrupted", -1, true);
            }
        }

        private static void read(java.io.InputStream in, StringBuilder sb) {
            try (var r = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                int c;
                while ((c = r.read()) >= 0) {
                    sb.append((char) c);
                }
            } catch (IOException ignored) {
                // 流关闭
            }
        }

        /**
         * PowerShell 管线桩（与生产 {@code OsSandbox#spawnPowerShell} 同构，组装件复用
         * plugin-api 的 {@code ExecResults}）：{@code buildPowerShellScript}(prefix+包裹+
         * 退出码尾部) + UTF-8 BOM 临时 {@code .ps1} + {@code -File} + stdout/stderr
         * <b>文件承载</b>（PS 5.1 管道承载会按 OEM 码页转码毁中文）+
         * {@code decodeConsoleOutput}/{@code decodeClixml} 还原。宿主无法解析 PowerShell
         * 可执行文件时与生产同语义抛 {@link IllegalStateException}（命令节点回退 cmd.exe）。
         */
        @Override
        public ExecResult spawnPowerShell(String command, Path cwd, Map<String, String> env,
                long timeoutMs) {
            String exe = fakePsExe();
            Path script = null;
            Path outFile = null;
            Path errFile = null;
            Process p = null;
            try {
                script = java.nio.file.Files.createTempFile("ea-fake-ps-", ".ps1");
                byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
                byte[] body = dev.everyagent.plugin.api.shell.ExecResults
                        .buildPowerShellScript(command)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] full = new byte[bom.length + body.length];
                System.arraycopy(bom, 0, full, 0, bom.length);
                System.arraycopy(body, 0, full, bom.length, body.length);
                java.nio.file.Files.write(script, full);
                outFile = java.nio.file.Files.createTempFile("ea-fake-out-", ".tmp");
                errFile = java.nio.file.Files.createTempFile("ea-fake-err-", ".tmp");
                p = new ProcessBuilder(exe, "-NoProfile", "-ExecutionPolicy", "Bypass",
                        "-File", script.toString())
                        .directory(cwd.toFile())
                        .redirectOutput(outFile.toFile())
                        .redirectError(errFile.toFile())
                        .start();
                boolean done = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
                if (!done) {
                    p.destroyForcibly();
                    p.waitFor(2, TimeUnit.SECONDS);
                }
                // 句柄随子进程退出关闭，须等进程结束/强杀后再读文件
                String out = dev.everyagent.plugin.api.shell.ExecResults.decodeConsoleOutput(
                        java.nio.file.Files.readAllBytes(outFile));
                String err = dev.everyagent.plugin.api.shell.ExecResults.decodeClixml(
                        dev.everyagent.plugin.api.shell.ExecResults.decodeConsoleOutput(
                                java.nio.file.Files.readAllBytes(errFile)));
                if (!done) {
                    return new ExecResult(out,
                            err + (err.isEmpty() ? "" : "\n") + "[exec 超时中止: >" + timeoutMs + "ms]",
                            -1, true);
                }
                return new ExecResult(out, err, p.exitValue(), false);
            } catch (InterruptedException e) {
                // 与 spawnNative 对齐：中断（级联取消）不留子进程——销毁后同步收割，恢复中断标记
                Thread.currentThread().interrupt();
                try {
                    if (p != null) {
                        p.destroyForcibly();
                        p.waitFor(2, TimeUnit.SECONDS);
                    }
                } catch (InterruptedException ie) {
                    // 收割等待自身被中断：放弃等待（尽力而为）
                }
                return new ExecResult("", "interrupted", -1, true);
            } catch (IOException e) {
                return new ExecResult("", "spawn 失败: " + e.getMessage(), -1, true);
            } finally {
                deleteQuietly(script);
                deleteQuietly(outFile);
                deleteQuietly(errFile);
            }
        }

        /** PowerShell 宿主解析（与 OsSandbox 同策略）：pwsh.exe 优先 → powershell.exe（PATH/System32 出厂位置）。 */
        private static String fakePsExe() {
            String pwsh = fakeFindInPath("pwsh.exe");
            if (pwsh != null) {
                return pwsh;
            }
            String inPath = fakeFindInPath("powershell.exe");
            if (inPath != null) {
                return inPath;
            }
            String sysRoot = System.getenv("SystemRoot");
            if (sysRoot != null) {
                Path builtin = Path.of(sysRoot, "System32", "WindowsPowerShell", "v1.0",
                        "powershell.exe");
                if (java.nio.file.Files.isRegularFile(builtin)) {
                    return builtin.toString();
                }
            }
            throw new IllegalStateException("测试宿主无法解析 PowerShell 可执行文件(pwsh/powershell 均未寻得)");
        }

        private static String fakeFindInPath(String name) {
            String pathEnv = System.getenv("Path");
            if (pathEnv == null || pathEnv.isBlank()) {
                pathEnv = System.getenv("PATH");
            }
            if (pathEnv == null || pathEnv.isBlank()) {
                return null;
            }
            for (String dir : pathEnv.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                if (dir.isBlank()) {
                    continue;
                }
                Path candidate = Path.of(dir, name);
                if (java.nio.file.Files.isRegularFile(candidate)) {
                    return candidate.toAbsolutePath().toString();
                }
            }
            return null;
        }

        private static void deleteQuietly(Path f) {
            if (f == null) {
                return;
            }
            try {
                java.nio.file.Files.deleteIfExists(f);
            } catch (IOException ignored) {
                // 删不掉不影响结果
            }
        }
    }
}
