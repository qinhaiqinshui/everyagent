package dev.everyagent.worker.agent;

import dev.everyagent.contract.json.Json;
import dev.everyagent.plugin.api.agent.AgentContext;
import dev.everyagent.plugin.api.event.Events;
import dev.everyagent.plugin.api.model.EmitEvent;
import dev.everyagent.plugin.api.proto.SnowflakeId;
import dev.everyagent.plugin.api.shell.ExecResults;
import dev.everyagent.plugin.api.spi.ToolExecutionChain;
import dev.everyagent.plugin.api.spi.ToolExecutionContext;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.worker.skill.Skill;
import dev.everyagent.worker.skill.SkillCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * skill 工具自动暴露节点(per-call 工具执行拦截链<b>被动观察者</b>,架构 §7.14):
 * AI 经 {@code read_file} <b>成功</b>读取某 skill 知识包(skill.md)后,该 skill frontmatter
 * {@code tools} 绑定的工具名自动「暴露」——写入当前 agent 的 exposedTools 集合
 * ({@link AgentContext#exposeTools} 通道,worker AgentEntity 覆写),AgentRunner 组装期取
 * 与 hidden 池交集以完整定义并入请求 toolCallbacks。
 *
 * <p><b>判定口径</b>(上行段,next.proceed 返回后):
 * <ul>
 *   <li>结果串为 null 或以 {@link ExecResults#TOOL_FAILURE_PREFIX} 开头(读取失败)不触发;</li>
 *   <li>工具名非 {@code read_file} 不触发;</li>
 *   <li>入参 JSON 的 {@code path} 经沙箱反译({@link SandboxPathRegistry#toHostPath},
 *       与 FsToolSupport 同口径;无映射原样保留)后与 {@link SkillCatalog} 各 skill 的
 *       {@code knowledgePath()} 归一比对(Path.normalize + 分隔符折叠;Windows 下大小写
 *       不敏感,与 SandboxPathRegistry.key 同口径);命中且 boundTools 非空 → 整表暴露。</li>
 * </ul>
 *
 * <p><b>双写入通道</b>:① 内存——经 {@code ctx.agents().get(ctx.agentId())} 取
 * {@link AgentContext} 调 {@code exposeTools}(只写可见性集合,不改工具注册与解析);
 * ② 持久——{@link AgentLedger#recordExposedTools} 并入台账条目 {@code exposedTools}
 * 数组(既有 30s 定时 + persistFinal 两路径落盘 agents.json,不另起写文件)。
 * 冷启动/续跑回灌在 {@code AgentBuilder.build()} 统一收口(exposedToolsOf)。
 *
 * <p><b>被动纪律</b>:下行段不改参数不合成;上行段只判定 + 写入,结果串<b>原样返回</b>;
 * 暴露副作用内部吞异常(观察者故障绝不影响工具结果)。瞬态 {@code task.trace}
 * (kind=skill_tool_expose,persist=false)供前端时间线观察「读取 skill → 暴露工具」。
 *
 * <p><b>order=450</b>:被动节点,避开既有链序 rg-hint(0)/tool-args-guard(50)/
 * unattended(100)/tool-events(500)/secret-redaction(900),位于发射节点内侧——
 * 暴露 trace 与 tool.result 同批可见。
 */
public final class SkillToolExposeInterceptor implements ToolExecutionInterceptor {

    private static final Logger log = LoggerFactory.getLogger(SkillToolExposeInterceptor.class);

    /** 触发判定的工具名(知识包读取的唯一契约入口,§7.17)。 */
    private static final String READ_FILE_TOOL = "read_file";

    /** 宿主为 Windows 时路径大小写不敏感(归一键须折叠大小写,同 SandboxPathRegistry)。 */
    private static final boolean CASE_INSENSITIVE =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final SkillCatalog catalog;
    private final SandboxPathRegistry pathRegistry;
    private final AgentLedger ledger;

    public SkillToolExposeInterceptor(SkillCatalog catalog, SandboxPathRegistry pathRegistry,
            AgentLedger ledger) {
        this.catalog = catalog;
        this.pathRegistry = pathRegistry;
        this.ledger = ledger;
    }

    @Override
    public String id() {
        return "skill-tool-expose";
    }

    @Override
    public float order() {
        return 450f;
    }

    @Override
    public String invoke(ToolExecutionContext ctx, ToolExecutionChain next) throws Exception {
        String result = next.proceed(ctx);
        // 只认成功结果:失败前缀(异常处理器/缺工具兜底的产出)不暴露
        if (result == null || result.startsWith(ExecResults.TOOL_FAILURE_PREFIX)) {
            return result;
        }
        String tool = ctx.toolCall() == null ? null : ctx.toolCall().name();
        if (!READ_FILE_TOOL.equals(tool)) {
            return result;
        }
        try {
            exposeIfSkillKnowledge(ctx);
        } catch (RuntimeException e) {
            // 被动观察者:暴露副作用失败仅记日志,结果串不受影响
            log.warn("[skill-expose] 工具暴露副作用失败(不影响工具结果) subject={} agent={} tool={}",
                    ctx.subjectId(), ctx.agentId(), tool, e);
        }
        return result;
    }

    /** 判定 + 双通道写入:路径命中某 skill 知识包且 boundTools 非空才动作。 */
    private void exposeIfSkillKnowledge(ToolExecutionContext ctx) {
        String path = pathOf(ctx.arguments());
        if (path == null || path.isBlank()) {
            return;
        }
        Skill hit = matchSkill(path);
        if (hit == null || hit.boundTools().isEmpty()) {
            return;
        }
        List<String> names = hit.boundTools();
        String agentId = ctx.agentId();
        // 通道一(内存):AgentContext.exposeTools → entity.exposedTools(组装期消费)
        Map<String, AgentContext> agents = ctx.agents();
        if (agents != null && agentId != null && !agentId.isEmpty()) {
            AgentContext agent = agents.get(agentId);
            if (agent != null) {
                agent.exposeTools(names);
            }
        }
        // 通道二(持久):并入台账条目 exposedTools(既有落盘路径,不另起写文件)
        if (ledger != null) {
            ledger.recordExposedTools(ctx.subjectId(), agentId, names);
        }
        emitTrace(ctx, hit, names);
    }

    /** 从工具入参 JSON 取 {@code path}(read_file 契约参数);非对象/缺参/非文本返回 null。 */
    private static String pathOf(String arguments) {
        if (arguments == null || arguments.isBlank()) {
            return null;
        }
        try {
            JsonNode node = Json.parse(arguments);
            JsonNode path = node == null ? null : node.path("path");
            return path != null && path.isTextual() ? path.asText() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 反译后的宿主路径与全部 skill 的 knowledgePath 归一比对,命中返回该 skill。 */
    private Skill matchSkill(String rawPath) {
        String host = toHostPath(rawPath);
        String key = normKey(host);
        if (key.isEmpty()) {
            return null;
        }
        for (Skill s : catalog.scan()) {
            if (s.boundTools().isEmpty()) {
                continue; // 无工具绑定的 skill 读包不触发暴露
            }
            if (key.equals(normKey(s.knowledgePath()))) {
                return s;
            }
        }
        return null;
    }

    /** AI 视角路径 → 宿主路径(与 FsToolSupport.resolveSandboxPath 同口径);无映射/失败原样保留。 */
    private String toHostPath(String sandboxPath) {
        if (pathRegistry == null) {
            return sandboxPath;
        }
        try {
            String host = pathRegistry.toHostPath(sandboxPath);
            return host != null ? host : sandboxPath;
        } catch (RuntimeException e) {
            return sandboxPath;
        }
    }

    /**
     * 归一比较键:Path.normalize + 分隔符折叠('/' 统一)+ 去尾斜杠;
     * Windows 下折叠大小写(与 SandboxPathRegistry.key 同口径)。
     */
    private static String normKey(String p) {
        if (p == null || p.isBlank()) {
            return "";
        }
        String s = p;
        try {
            s = Path.of(p).normalize().toString();
        } catch (RuntimeException ignore) {
            // 非法路径串:退回原文(仍能做字符串级比对)
        }
        s = s.replace('\\', '/');
        while (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return CASE_INSENSITIVE ? s.toLowerCase(Locale.ROOT) : s;
    }

    /** 瞬态 task.trace(不落盘):「读取 skill → 暴露工具」前端时间线可见;发射失败静默。 */
    private void emitTrace(ToolExecutionContext ctx, Skill skill, List<String> names) {
        if (ctx.terminal()) {
            return; // leak-guard:主体收口后不再发射
        }
        dev.everyagent.plugin.api.model.EventEmitter emitter = ctx.emitter();
        if (emitter == null) {
            return;
        }
        try {
            long id = SnowflakeId.next();
            tools.jackson.databind.node.ObjectNode meta = Json.obj();
            meta.put("skill", skill.id());
            meta.set("tools", Json.toJson(names));
            Events.TracePayload payload = new Events.TracePayload(Long.toString(id),
                    "skill_tool_expose", "skill 工具自动暴露",
                    "读取 " + skill.title() + " → 暴露工具 [" + String.join(", ", names) + "]",
                    null, "done", System.currentTimeMillis(), meta);
            emitter.emit(EmitEvent.transientOf(id, Events.TASK_TRACE, ctx.agentId(),
                    null, null, null, null, payload, EmitEvent.Mode.REPLACE));
        } catch (RuntimeException e) {
            log.debug("[skill-expose] 暴露 trace 发射失败(忽略) skill={} agent={}",
                    skill.id(), ctx.agentId(), e);
        }
    }
}
