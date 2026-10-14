package dev.everyagent.worker.skill;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.AdvisorChain;
import org.springframework.ai.chat.client.advisor.api.BaseAdvisor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import dev.everyagent.worker.modules.SkillExclusionStore;
import dev.everyagent.worker.os.SandboxPathRegistry;

/**
 * 把 skill 以<b>渐进式披露</b>形式注入 system prompt(对应 nagent 的
 * skill 知识 seed 到 /skills;披露方式为「索引进提示词、正文按需 read_file」)。
 *
 * <p>数据源<b>唯一</b>(目录即真相源,§7.17):{@link SkillCatalog#scan()} 实扫系统
 * 技能目录——内置包、插件物化包与用户自制 skill 落盘后统一由此进入,<b>默认全部
 * 注入</b>;仅剔除「当前工作区被排除」的 skill(暴露控制 = 按工作区的排除配置,
 * {@link SkillExclusionStore};不配置 = 全部工作区暴露)。{@code /} 菜单不受该
 * 配置影响(菜单永远全量,见 SkillSlashProvider)。
 *
 * <p><b>per-run 实例</b>:排除过滤需要「本次 run 所属工作区根」,共享单例拿不到——
 * 由 {@code SkillAdvisorProvider.create(ctx)} 按每次 {@code ctx.workspaceRoot()}
 * 新建本实例(本类无状态,per-run 零成本);{@code workspaceRoot} 为 null/空白时
 * <b>不过滤、全部注入</b>(宁多勿漏,记 WARN——静默丢索引比多注入更难排查)。
 *
 * <p>「标题 + 一句话描述 + 知识包路径」清单以额外 {@link SystemMessage} 插入到首部
 * 系统指令区之后(不落会话末位),不改动会话中既有的 system message——完全复用
 * Spring AI 的 prompt / advisor 原语,不手搓 prompt 拼接。知识包正文不在此注入:
 * 文件已物化到系统技能目录 {@code <skillsDir>/<id>/skill.md}(§7.17,对 AI 工具只读
 * 放行),AI 需要执行某技能时自行 {@code read_file} 按知识包绝对路径读取,
 * 避免完整方法论每轮全量占用上下文。
 *
 * <p>顺序:作为外层 advisor({@link Ordered#HIGHEST_PRECEDENCE} + 100,高于
 * {@code ToolCallingAdvisor}),保证 skill 索引对整轮(含工具循环每一迭代)可见。
 */
public class SkillAdvisor implements BaseAdvisor {

    private static final Logger log = LoggerFactory.getLogger(SkillAdvisor.class);

    private final SkillCatalog catalog;
    private final SkillExclusionStore exclusions;
    private final SandboxPathRegistry pathRegistry;

    /**
     * 本 run 所属工作区根(normalize 后的绝对路径;null/空白 = 无工作区信息,
     * 构造时 WARN 一次、过滤跳过)。
     */
    private final String workspaceRoot;

    /**
     * @param workspaceRoot 本 run 所属工作区根({@code AdvisorContext.workspaceRoot()}
     *        直取的 String);normalize 口径与 RPC 层({@code skill.exclusions.set})
     *        一致:{@code Path.of(raw).toAbsolutePath().normalize().toString()}
     *        ——排除清单落盘前已按同款 normalize 加工,两边字符串相等即命中。
     *        null/空白时不过滤(全部注入,宁多勿漏)。
     */
    public SkillAdvisor(SkillCatalog catalog, SkillExclusionStore exclusions,
            SandboxPathRegistry pathRegistry, String workspaceRoot) {
        this.catalog = catalog;
        this.exclusions = exclusions;
        this.pathRegistry = pathRegistry;
        if (workspaceRoot == null || workspaceRoot.isBlank()) {
            log.warn("workspaceRoot 缺失(null/空白),skill 排除过滤跳过,全部注入(宁多勿漏)");
            this.workspaceRoot = null;
        } else {
            this.workspaceRoot = normalize(workspaceRoot);
        }
    }

    /**
     * 与 RPC 层({@code skill.exclusions.set})同款的 root normalize:
     * {@code toAbsolutePath().normalize()} 后取字符串。极端非法路径(无法 Path.of)
     * 降级为 trim 后原串比对——过滤口径宁松勿紧。
     */
    private static String normalize(String raw) {
        String trimmed = raw.trim();
        try {
            return Path.of(trimmed).toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return trimmed;
        }
    }

    /**
     * 目录扫描(唯一真相源)→ 剔除当前工作区被排除的 skill。
     * 无排除配置 = 全部注入;workspaceRoot 缺失 = 不过滤(决策:宁多勿漏)。
     */
    private List<Skill> visibleSkills() {
        List<Skill> skills = catalog.scan();
        if (workspaceRoot == null) {
            return skills;
        }
        List<Skill> visible = new ArrayList<>(skills.size());
        for (Skill s : skills) {
            if (!exclusions.excludedWorkspaces(s.id()).contains(workspaceRoot)) {
                visible.add(s);
            }
        }
        return visible;
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
        List<Skill> skills = visibleSkills();
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
