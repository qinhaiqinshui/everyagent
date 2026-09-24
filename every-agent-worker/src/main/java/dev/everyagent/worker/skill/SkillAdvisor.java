package dev.everyagent.worker.skill;

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

import dev.everyagent.plugin.api.skill.PluginSkill;
import dev.everyagent.worker.os.OsSandbox;
import dev.everyagent.worker.os.wsl.WslPathMapper;
import dev.everyagent.worker.plugin.registry.SkillContributorRegistry;

/**
 * 把激活的 skill 以<b>渐进式披露</b>形式注入 system prompt(对应 nagent 的
 * skill 知识 seed 到 /skills;披露方式改为「索引进提示词、正文按需 read_file」)。
 *
 * <p>Spring AI 2.0 没有内置 Skill 抽象;本 advisor 在 {@link #before} 阶段把
 * {@link BuiltInSkills#getActiveSkills()} + {@link SkillContributorRegistry#getSkills()}
 * 的「标题 + 一句话描述 + 知识包路径」清单以额外 {@link SystemMessage} 插入到首部
 * 系统指令区之后(不落会话末位),不改动会话中既有的 system message——完全复用
 * Spring AI 的 prompt / advisor 原语,不手搓 prompt 拼接。知识包正文不在此注入:
 * 文件已物化到系统技能目录 {@code <系统目录>/skills/}(§13.8,对 AI 工具只读放行),
 * AI 需要执行某技能时自行 {@code read_file} 按知识包绝对路径读取,
 * 避免完整方法论每轮全量占用上下文。
 *
 * <p>顺序:作为外层 advisor({@link Ordered#HIGHEST_PRECEDENCE} 附近,高于
 * {@code ToolCallingAdvisor}),保证 skill 索引对整轮(含工具循环每一迭代)可见。
 */
public class SkillAdvisor implements BaseAdvisor {

    private final BuiltInSkills builtInSkills;
    private final SkillContributorRegistry skillContributorRegistry;
    private final OsSandbox osSandbox;

    public SkillAdvisor(BuiltInSkills builtInSkills, SkillContributorRegistry skillContributorRegistry,
            OsSandbox osSandbox) {
        this.builtInSkills = builtInSkills;
        this.skillContributorRegistry = skillContributorRegistry;
        this.osSandbox = osSandbox;
    }

    /**
     * 兼容旧构造器（无 SkillContributorRegistry，不合并插件贡献的 skill）。
     * 仅用于测试或无插件场景。
     */
    public SkillAdvisor(List<Skill> skills, OsSandbox osSandbox) {
        this.builtInSkills = null;
        this.skillContributorRegistry = null;
        this.osSandbox = osSandbox;
        this.cachedSkills = skills;
    }

    /** 缓存的 skill 列表（仅旧构造器路径使用；新路径每次 before() 合并）。 */
    private List<Skill> cachedSkills;

    /**
     * 合并内置 skill + 插件贡献的 skill。
     * 内置优先；按 pluginId + skillId 去重（内置 skill 的 id 与插件贡献的同 id 时内置优先）。
     */
    private List<Skill> mergedSkills() {
        if (cachedSkills != null) {
            return cachedSkills;
        }
        List<Skill> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // 内置 active skill 优先
        if (builtInSkills != null) {
            for (Skill s : builtInSkills.getActiveSkills()) {
                if (seen.add(s.id())) {
                    merged.add(s);
                }
            }
        }
        // 插件贡献的 skill
        if (skillContributorRegistry != null) {
            for (PluginSkill ps : skillContributorRegistry.getSkills()) {
                if (seen.add(ps.id())) {
                    merged.add(new Skill(ps.id(), ps.title(), ps.description(),
                            ps.knowledgePath(), ps.toolIds()));
                }
            }
        }
        return merged;
    }

    @Override
    public String getName() {
        return "Skill Advisor";
    }

    @Override
    public int getOrder() {
        // 高于 ToolCallingAdvisor(其 DEFAULT_ORDER = HIGHEST_PRECEDENCE + 300),
        // 让 skill 索引作为最外圈注入,对工具循环的每一轮都生效。
        return Ordered.HIGHEST_PRECEDENCE + 100;
    }

    @Override
    public ChatClientRequest before(ChatClientRequest chatClientRequest, AdvisorChain advisorChain) {
        List<Skill> skills = mergedSkills();
        if (skills.isEmpty()) {
            return chatClientRequest;
        }
        StringBuilder sb = new StringBuilder("# 可用技能\n\n");
        for (Skill s : skills) {
            sb.append(s.toSystemText(resolveKnowledgePath(s))).append("\n");
        }
        sb.append("\n使用规则:以上技能只需知道其存在与适用场景。实际要执行某个技能时,"
                + "先按上面的路径用 read_file 读取对应知识包文件,再按其中的详细步骤与规则操作;"
                + "用不到时不要读取,避免浪费上下文。");
        List<Message> instructions = new ArrayList<>(chatClientRequest.prompt().getInstructions());
        // 插入位置:首部连续 SystemMessage 区的末尾(系统指令区)。
        // 不得 add 到会话末位——末位必须是 user/ToolResponse 消息,追加系统消息会破坏
        // 模型侧消息序语义,也会吞掉「末条消息」分派协议(FakeChatModel 触发词即按末条分派)。
        int insertAt = 0;
        while (insertAt < instructions.size() && instructions.get(insertAt) instanceof SystemMessage) {
            insertAt++;
        }
        instructions.add(insertAt, new SystemMessage(sb.toString().strip()));
        Prompt newPrompt = new Prompt(instructions, chatClientRequest.prompt().getOptions());
        return chatClientRequest.mutate().prompt(newPrompt).build();
    }

    @Override
    public ChatClientResponse after(ChatClientResponse chatClientResponse, AdvisorChain advisorChain) {
        return chatClientResponse;
    }

    /**
     * 将知识包 Windows 宿主路径解析为 AI 沙箱内可见的路径(§7.17)。
     *
     * <p>WSL 系列沙箱下 AI 以 Linux 视角运行,bash 工具 {@code cat}/{@code grep} 技能包
     * 与 {@code read_file}(经 FsToolSupport 反向翻译)均需 {@code /} 开头的沙箱内路径:
     * <ul>
     *   <li>wsl-direct:{@code C:\Users\...\skills\x.md → /c/Users/.../skills/x.md}
     *       ({@link WslPathMapper#toDirectMount},与 drvfs 挂载点一致);</li>
     *   <li>wsl-bwrap:{@code → /mnt/c/Users/.../skills/x.md}
     *       ({@link WslPathMapper#toWsl},与 --ro-bind 挂载点一致);</li>
     *   <li>非 WSL 后端(windows-mic / direct / 非 Windows):原样返回宿主路径。</li>
     * </ul>
     */
    private String resolveKnowledgePath(Skill skill) {
        String raw = skill.knowledgePath();
        if (osSandbox == null || !osSandbox.isWslBackend()) {
            return raw;
        }
        Path winPath = Path.of(raw);
        String sandboxPath = osSandbox.isWslDirect()
                ? WslPathMapper.toDirectMount(winPath)
                : WslPathMapper.toWsl(winPath);
        // 映射失败(UNC 等)时回退原始路径,read_file 的 WSL 反向翻译同样处理兜底
        return sandboxPath != null ? sandboxPath : raw;
    }
}
