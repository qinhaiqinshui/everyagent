package dev.everyagent.worker.task;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

/**
 * 把工作区根目录下的 {@code agents.md}(忽略大小写,如 {@code AGENTS.md})读取并注入
 * system prompt(一个 advisor 只负责一个功能,红线)。
 *
 * <p>注入内容:
 * <pre>
 * # agents.md
 * 以下内容来自工作区根目录agents.md,你需要严格遵守其中约束。
 * &lt;agents.md 原文&gt;
 * </pre>
 *
 * <p>查找为大小写不敏感:遍历工作区根目录,取文件名 {@code equalsIgnoreCase("agents.md")}
 * 的第一个普通文件读取(UTF-8)。文件不存在 / 工作区缺失 / 目录不可读时静默跳过,不打断 agent。
 *
 * <p>读取时机:每次 {@link #before} 实时重读(任务运行中修改 agents.md 下一轮即生效),
 * 与 {@link SystemInfoAdvisor} 的静态注入不同。读取路径使用宿主机工作区根
 * {@code workspaceRoot}(与 fs/git RPC 同源),不随 wsl-bwrap 后端做 /workspace 翻译——
 * 本 advisor 运行在 worker 宿主机 JVM 上,按宿主机真实路径读文件。
 *
 * <p>插入方式与 {@link SystemInfoAdvisor}/{@link SkillAdvisor} 一致:以额外
 * {@link SystemMessage} 插入到首部连续 SystemMessage 区之后(不落会话末位——末位必须是
 * user/ToolResponse 消息,追加系统消息会破坏模型侧消息序语义)。
 *
 * <p>顺序:order = {@code HIGHEST_PRECEDENCE + 60},介于 {@link SystemInfoAdvisor}
 * ({@code +50})与 {@link SkillAdvisor}({@code +100})之间,使约束位于环境信息之后、技能索引之前。
 */
public class AgentsMdAdvisor implements BaseAdvisor {

    /** 注入的固定头部:声明来源并要求模型严格遵守。 */
    private static final String HEADER = "# agents.md\n"
            + "以下内容来自工作区根目录agents.md,你需要严格遵守其中约束。\n";

    /** 本任务工作区根(宿主机绝对路径,可为 null/blank:旧格式冷启动无工作区,跳过)。 */
    private final String workspaceRoot;

    public AgentsMdAdvisor(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    @Override
    public String getName() {
        return "Agents.md Advisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 60;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        String content = readAgentsMd();
        if (content == null || content.isBlank()) {
            return chatClientRequest;
        }
        List<Message> instructions = new ArrayList<>(chatClientRequest.prompt().getInstructions());
        // 插入位置:首部连续 SystemMessage 区的末尾(与 SystemInfoAdvisor 一致,不落会话末位)。
        int insertAt = 0;
        while (insertAt < instructions.size() && instructions.get(insertAt) instanceof SystemMessage) {
            insertAt++;
        }
        instructions.add(insertAt, new SystemMessage(HEADER + content));
        Prompt newPrompt = new Prompt(instructions, chatClientRequest.prompt().getOptions());
        return chatClientRequest.mutate().prompt(newPrompt).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    /** 实时读取工作区根目录下的 agents.md(忽略大小写);不存在或不可读时返回 null。 */
    private String readAgentsMd() {
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            return null;
        }
        Path root = Path.of(workspaceRoot);
        if (!Files.isDirectory(root)) {
            return null;
        }
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p) && "agents.md".equalsIgnoreCase(p.getFileName().toString())) {
                    return Files.readString(p, StandardCharsets.UTF_8);
                }
            }
        } catch (IOException e) {
            // 目录不可读等 IO 异常:静默跳过,不打断 agent(与「找不到文件」同语义)。
            return null;
        }
        return null;
    }
}
