package dev.everyagent.worker.task;

import dev.everyagent.worker.git.GitAutoSyncToken;
import dev.everyagent.worker.modules.GitService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.core.Ordered;

/**
 * 任务完成后自动 git 同步 advisor(对齐 old {@code nodes/autoSyncOnCompleteNode.ts}
 * 的 {@code git.auto_sync_on_complete},order 10.5 / unwind 阶段执行;worker 侧用 advisor 实现)。
 *
 * <p>一个 advisor 只负责一个功能(红线):本 advisor 仅做「本轮选中 /自动同步 时,任务完成后
 * 自动提交并推送」。它是一个<b>外层</b> advisor：
 * <ul>
 *   <li>{@link #before} 仅<b>读取</b>本轮最后一条 user 消息是否含 {@code git.auto_sync}
 *       token(enabled)——此时 token 尚未被 {@code SlashTokenResolveAdvisor}(order +150)剥离,
 *       故能命中标记;不修改消息。</li>
 *   <li>仅扫<b>最后一条</b> user 消息(本轮用户输入),实现老项目「仅本轮有效」语义——历史里的
 *       旧 token 不会跨轮误触发。</li>
 *   <li>{@link #after} 位于工具循环 advisor 之外(order 低于 {@code ToolCallingAdvisor}
 *       的 +300),故在整轮(含子 agent 工具循环)收口后执行;标记命中则触发
 *       {@link GitService#syncRemote}。</li>
 * </ul>
 *
 * <p>错误语义:同步失败只记 WARN 日志,不向上抛出——自动同步是链收口后的旁路动作,不应影响
 * 任务终态与队列串行循环(同 old autoSyncOnCompleteNode 吞错)。同步走静默模式(复用本机已存
 * 凭证:credential.helper / ssh-agent / 工作区加密凭证),不弹凭证补全窗。
 *
 * <p>装配:仅挂主 agent({@code AgentClientFactory#forMain}),与 {@code SkillAdvisor} 同档——
 * 子 agent 在主 agent 工具循环内递归执行,其收口已包含在主 agent 整轮内,无需重复同步。
 */
public class GitAutoSyncAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(GitAutoSyncAdvisor.class);

    /** 位于 SlashTokenResolveAdvisor(+150) 之前、ToolCallingAdvisor(+300) 之外。 */
    private static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 140;

    private final AgentEntity a;
    private final GitService gitService;
    /** 本轮是否自动同步(per-request 状态,advisor 实例随 forMain 新建而物化)。 */
    private volatile boolean autoSync = false;

    public GitAutoSyncAdvisor(AgentEntity a, GitService gitService) {
        this.a = a;
        this.gitService = gitService;
    }

    @Override
    public String getName() {
        return "Git Auto Sync Advisor";
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        detect(chatClientRequest);
        return chatClientRequest;
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        if (autoSync) {
            runAutoSync();
        }
        return chatClientResponse;
    }

    /** 扫描本轮最后一条 user 消息,命中 git.auto_sync(enabled) 即置标记。 */
    private void detect(ChatClientRequest req) {
        var instructions = req.prompt().getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message m = instructions.get(i);
            if (m instanceof UserMessage um) {
                String text = um.getText();
                if (text != null && GitAutoSyncToken.enabledIn(text)) {
                    autoSync = true;
                }
                break; // 只看最后一条 user 消息(本轮输入)
            }
        }
    }

    /** 执行「完成后同步」:完整同步(同 Git 面板同步按钮),静默模式,全部异常兜底为日志。 */
    private void runAutoSync() {
        try {
            GitService.SyncResult result = gitService.syncRemote(
                    a.task.workspaceRoot, GitAutoSyncToken.buildCommitMessage(a.task.taskId));
            switch (result.status()) {
                case SUCCESS -> log.info("自动同步完成: {}", result.message());
                case NOT_INITIALIZED, NO_REMOTE, NOOP ->
                        log.debug("自动同步跳过: {} ({})", result.status(), result.message());
                default -> log.warn("自动同步未完成: {} ({})", result.status(), result.message());
            }
        } catch (Exception e) {
            log.warn("自动同步执行异常: {}", e.getMessage());
        }
    }
}
