package dev.everyagent.worker.task;

import dev.everyagent.worker.os.wsl.WslPathMapper;

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
 * 把「当前工作区 / 当前操作系统」环境信息注入 system prompt(架构 §5.2 + 红线:
 * 一个 advisor 只负责一个功能——本 advisor 只注入环境事实,不参与工具循环 / 日志)。
 *
 * <p>注入内容:
 * <pre>
 * # 工作区
 * - 当前工作区位置： &lt;workspaceRoot&gt;(wsl-bwrap 后端为 /workspace,wsl-direct 为原路径挂载点 /c/a/foo)
 * - 当前操作系统：Windows 11(wsl 系列后端为 Linux)
 * </pre>
 * 让模型知道自己在哪个工作区、跑在什么系统上(Windows 注册 powershell、Linux/macOS
 * 注册 bash,命令选择与路径语义依赖该信息);wsl 系列后端时 AI 实际运行在 Linux
 * 沙箱内:wsl-bwrap 工作区以 /workspace 挂载,wsl-direct 以原路径挂载点
 * {@code /c/a/foo} 挂载,故均按 Linux 视角注入。工作区位置缺失(旧格式冷启动
 * {@code workspaceRoot == null})时省略该行,不伪造路径。
 *
 * <p>插入方式与 {@link SkillAdvisor} 一致:以额外 {@link SystemMessage} 插入到首部
 * 连续 SystemMessage 区之后(不落会话末位——末位必须是 user/ToolResponse 消息,
 * 追加系统消息会破坏模型侧消息序语义),完全复用 Spring AI 的 prompt / advisor 原语。
 *
 * <p>顺序:order = {@code HIGHEST_PRECEDENCE + 50},早于 {@link SkillAdvisor}
 * ({@code +100}),让环境信息紧贴基础 system 指令区、skill 索引随后。
 */
public class SystemInfoAdvisor implements BaseAdvisor {

    /** 本任务工作区根(Windows 域,可为 null:旧格式冷启动无工作区,省略该行)。 */
    private final String workspaceRoot;
    /** 操作系统描述(进程启动后不变,静态只读)。 */
    private final String os;
    /** 是否 wsl 系列沙箱后端:是则以 Linux 视角注入(当前操作系统:Linux)。 */
    private final boolean wslBackend;
    /** 是否 wsl-direct 后端:工作区以原路径挂载点 /c/a/foo 注入(而非 /workspace)。 */
    private final boolean wslDirect;

    public SystemInfoAdvisor(String workspaceRoot, boolean wslBackend, boolean wslDirect) {
        this.workspaceRoot = workspaceRoot;
        this.wslBackend = wslBackend;
        this.wslDirect = wslDirect;
        this.os = wslBackend ? "Linux" : System.getProperty("os.name", "unknown");
    }

    @Override
    public String getName() {
        return "System Info Advisor";
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 50;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 身份\n").append("你AI助手，你的名字是EveryAgent。一可以通过网络浏览器随时随地召唤的AI助手。\n");
        if (workspaceRoot != null && !workspaceRoot.isBlank()) {
            // wsl 系列后端:AI 看到的路径是沙箱内 Linux 挂载点(wsl-bwrap 为 /workspace、
            // wsl-direct 为原路径挂载点 /c/a/foo),命令也在其中执行;宿主机 Windows 路径
            // 只存在于外部,注入反而会误导命令路径语义。
            String shownWorkspace;
            if (wslDirect) {
                String m = WslPathMapper.toDirectMount(java.nio.file.Path.of(workspaceRoot));
                shownWorkspace = m != null ? m : workspaceRoot;
            } else {
                shownWorkspace = wslBackend ? WslPathMapper.WORKSPACE_MOUNT : workspaceRoot;
            }
            String shellHint = wslBackend ? "bash" : "powershell";
            sb.append("# 工作区\n");
            sb.append("- 当前工作区位置： ").append(shownWorkspace).append('\n')
            .append("文件操作相关命令默认目录为当前工作区目录\n")
                    .append(shellHint).append("工具会在沙箱中执行，如果你要创建临时文件，务必在当前工作区的.everyagent目录下进行。\n")
                    .append("在工作区下读取和修改文件不会被沙箱拦截。");
        }
        sb.append("\n- 当前操作系统：").append(os);
        List<Message> instructions = new ArrayList<>(chatClientRequest.prompt().getInstructions());
        // 插入位置:首部连续 SystemMessage 区的末尾(与 SkillAdvisor 一致,不落会话末位)。
        int insertAt = 0;
        while (insertAt < instructions.size() && instructions.get(insertAt) instanceof SystemMessage) {
            insertAt++;
        }
        instructions.add(insertAt, new SystemMessage(sb.toString()));
        Prompt newPrompt = new Prompt(instructions, chatClientRequest.prompt().getOptions());
        return chatClientRequest.mutate().prompt(newPrompt).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }
}
