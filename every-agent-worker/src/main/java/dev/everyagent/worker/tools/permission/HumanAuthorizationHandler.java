package dev.everyagent.worker.tools.permission;

import dev.everyagent.plugin.api.execution.ExecContext;
import dev.everyagent.plugin.api.permission.AuthorizationChain;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationRequest;
import dev.everyagent.plugin.api.permission.AuthorizationHandler.AuthorizationDecision;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.plugin.registry.AuthorizationHandlerRegistry;
import dev.everyagent.plugin.api.exception.AgentCancelledException;
import dev.everyagent.plugin.api.interaction.AskOption;
import dev.everyagent.plugin.api.interaction.AskQuestion;
import dev.everyagent.plugin.api.interaction.AskResult;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工弹窗授权节点（core 提供，order=300，始终 applies=true）。
 *
 * <p>发起 authorization ask 并解析答案返回 ALLOW/DENY（不返回 PASS——是链的终结节点）。
 * ask 经 {@link ExecContext#interaction()} 绑定交互口发出（context map 的
 * {@code "taskId"} 键由 SubjectBoundInteractionService 自动补填，{@code "agentId"}
 * 由本节点补充）。prompt 为短问句，结构化信息走 {@link AskQuestion#fields()} 信息槽
 * （§12：grantKey 解析出授权类型/目录/类别，原长文案整段保留在 fields 供磁盘回放）。
 */
@Component
public class HumanAuthorizationHandler implements AuthorizationHandler {

    static final List<AskOption> AUTHORIZE_OPTIONS = List.of(
            new AskOption("本轮运行内允许", "run", AskOption.TYPE_RADIO),
            new AskOption("本任务全程允许", "task", AskOption.TYPE_RADIO),
            new AskOption("拒绝", "deny", AskOption.TYPE_RADIO));

    private final AuthorizationHandlerRegistry registry;
    private final WorkerProperties props;

    public HumanAuthorizationHandler(AuthorizationHandlerRegistry registry, WorkerProperties props) {
        this.registry = registry;
        this.props = props;
    }

    @PostConstruct
    void selfRegister() {
        registry.register(this);
    }

    @Override
    public String id() { return "human-authorization"; }

    @Override
    public float order() { return 300f; }

    @Override
    public AuthorizationDecision invoke(AuthorizationRequest req, AuthorizationChain next) throws Exception {
        List<AskQuestion> questions = List.of(
                new AskQuestion("", "是否授权?", AUTHORIZE_OPTIONS, authFields(req)));
        AskResult ans;
        try {
            ans = req.context().interaction().ask(questions,
                    props.getPermissions().getAuthTimeoutMs(),
                    Map.of("agentId", req.agentId()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AgentCancelledException("task cancelled");
        }
        if (!"answered".equals(ans.status())) {
            return new AuthorizationDecision(AuthorizationDecision.Type.DENY, "拒绝");
        }
        GrantScope scope = GrantRegistry.parseScope(ans.text());
        return switch (scope) {
            case RUN, TASK -> new AuthorizationDecision(AuthorizationDecision.Type.ALLOW, "人工授权");
            case DENY -> new AuthorizationDecision(AuthorizationDecision.Type.DENY, "拒绝");
        };
    }

    /**
     * 授权信息槽（§12）：从 grantKey 解析结构化「标签 → 值」（p::op::path → 授权类型+目录、
     * c::verb → 命令类别、priv::name → 提权类别），并把原长文案整段保留在「授权请求」槽
     * ——磁盘回放/通知摘要仍可读，fields 只是展示增强。
     */
    static Map<String, String> authFields(AuthorizationRequest req) {
        Map<String, String> fields = new LinkedHashMap<>();
        String grantKey = req.grantKey();
        if (grantKey != null && grantKey.startsWith("p::")) {
            String rest = grantKey.substring("p::".length());
            int i = rest.indexOf("::");
            if (i > 0) {
                fields.put("授权类型", switch (rest.substring(0, i)) {
                    case "read" -> "读取";
                    case "write" -> "写入";
                    case "exec" -> "访问";
                    default -> rest.substring(0, i);
                });
                fields.put("目录", rest.substring(i + 2));
            }
        } else if (grantKey != null && grantKey.startsWith("c::")) {
            fields.put("命令类别", grantKey.substring("c::".length()));
        } else if (grantKey != null && grantKey.startsWith("priv::")) {
            fields.put("提权类别", grantKey.substring("priv::".length()));
        }
        if (req.prompt() != null && !req.prompt().isBlank()) {
            fields.put("授权请求", req.prompt());
        }
        return fields;
    }
}
