package dev.everyagent.worker.skill;

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
 * 把激活的内置 skill 以<b>渐进式披露</b>形式注入 system prompt(对应 nagent 的
 * skill 知识 seed 到 /skills;披露方式改为「索引进提示词、正文按需 read_file」)。
 *
 * <p>Spring AI 2.0 没有内置 Skill 抽象;本 advisor 在 {@link #before} 阶段把
 * {@link BuiltInSkills#getActiveSkills()} 的「标题 + 一句话描述 + 知识包路径」清单以
 * 额外 {@link SystemMessage} 插入到首部系统指令区之后(不落会话末位),不改动会话中
 * 既有的 system message——完全复用 Spring AI 的 prompt / advisor
 * 原语,不手搓 prompt 拼接。知识包正文不在此注入:文件已由 {@code BuiltInSkills#materialize}
 * 物化到系统技能目录 {@code <系统目录>/skills/}(§13.8,对 AI 工具只读放行),
 * AI 需要执行某技能时自行 {@code read_file} 按知识包绝对路径读取,
 * 避免完整方法论每轮全量占用上下文。
 *
 * <p>顺序:作为外层 advisor({@link Ordered#HIGHEST_PRECEDENCE} 附近,高于
 * {@code ToolCallingAdvisor}),保证 skill 索引对整轮(含工具循环每一迭代)可见。
 */
public class SkillAdvisor implements BaseAdvisor {

    private final List<Skill> skills;

    public SkillAdvisor(List<Skill> skills) {
        this.skills = skills;
    }

    public SkillAdvisor(BuiltInSkills builtInSkills) {
        this(builtInSkills.getActiveSkills());
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
        if (skills.isEmpty()) {
            return chatClientRequest;
        }
        StringBuilder sb = new StringBuilder("# 可用技能\n\n");
        for (Skill s : skills) {
            sb.append(s.toSystemText()).append("\n");
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
}
