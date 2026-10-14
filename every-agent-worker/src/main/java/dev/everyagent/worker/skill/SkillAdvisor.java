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
import dev.everyagent.worker.os.SandboxPathRegistry;
import dev.everyagent.worker.plugin.registry.SkillContributorRegistry;

/**
 * 把 skill 以<b>渐进式披露</b>形式注入 system prompt(对应 nagent 的
 * skill 知识 seed 到 /skills;披露方式为「索引进提示词、正文按需 read_file」)。
 *
 * <p>数据源(目录即真相源,§7.17):{@link SkillCatalog#scan()} 实扫系统技能目录
 * ——内置包、插件物化包与用户自制 skill 落盘后统一由此进入,<b>默认全部注入</b>
 * (按工作区的排除过滤是后续步骤的事,本阶段尚未接入);另合并插件 SPI
 * ({@link SkillContributorRegistry},过渡期保留,届时整体删除),同 id 目录条目优先。
 * 「标题 + 一句话描述 + 知识包路径」清单以额外 {@link SystemMessage} 插入到首部
 * 系统指令区之后(不落会话末位),不改动会话中既有的 system message——完全复用
 * Spring AI 的 prompt / advisor 原语,不手搓 prompt 拼接。知识包正文不在此注入:
 * 文件已物化到系统技能目录 {@code <skillsDir>/<id>/skill.md}(§7.17,对 AI 工具只读
 * 放行),AI 需要执行某技能时自行 {@code read_file} 按知识包绝对路径读取,
 * 避免完整方法论每轮全量占用上下文。
 *
 * <p>顺序:作为外层 advisor({@link Ordered#HIGHEST_PRECEDENCE} 附近,高于
 * {@code ToolCallingAdvisor}),保证 skill 索引对整轮(含工具循环每一迭代)可见。
 */
public class SkillAdvisor implements BaseAdvisor {

    private final SkillCatalog catalog;
    private final SkillContributorRegistry skillContributorRegistry;
    private final SandboxPathRegistry pathRegistry;

    public SkillAdvisor(SkillCatalog catalog, SkillContributorRegistry skillContributorRegistry,
            SandboxPathRegistry pathRegistry) {
        this.catalog = catalog;
        this.skillContributorRegistry = skillContributorRegistry;
        this.pathRegistry = pathRegistry;
    }

    /**
     * 合并目录扫描的 skill + 插件 SPI 贡献的 skill(过渡期保留 SPI 分支)。
     * 目录条目优先;按 id 去重(SPI 与目录同 id 时目录条目胜出——目录即真相源)。
     */
    private List<Skill> mergedSkills() {
        List<Skill> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        // 目录扫描(真相源)优先:内置/插件物化包 + 用户自制 skill 一律由此进入
        for (Skill s : catalog.scan()) {
            if (seen.add(s.id())) {
                merged.add(s);
            }
        }
        // 插件贡献的 skill(仅未被目录覆盖的条目;SPI 通道后续步骤整体删除)
        if (skillContributorRegistry != null) {
            for (PluginSkill ps : skillContributorRegistry.getSkills()) {
                if (seen.add(ps.id())) {
                    merged.add(new Skill(ps.id(), ps.title(), ps.description(), ps.knowledgePath()));
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
     * 将知识包宿主路径解析为 AI 沙箱内可见的路径。
     *
     * <p>经 {@link SandboxPathRegistry#toSandboxPath} 翻译;无映射时原样返回（DIRECT 场景）。
     * 这个路径注入 system prompt,供 AI 用 bash 工具访问（不是文件工具）。
     */
    private String resolveKnowledgePath(Skill skill) {
        String raw = skill.knowledgePath();
        if (pathRegistry == null) {
            return raw;
        }
        return pathRegistry.toSandboxPath(Path.of(raw));
    }
}
