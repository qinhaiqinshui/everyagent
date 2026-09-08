package dev.everyagent.worker.skill;

import java.util.List;

/**
 * 内置 skill 领域模型(纯数据,不耦合 Spring AI 或 prompt 构造)。
 *
 * <p>对应 nagent 前端 {@code SkillManifest},但 Spring AI 2.0 没有内置 Skill 类型,
 * 这里仅作为 worker 侧的数据载体。
 *
 * <p><b>渐进式披露</b>:模型只携带「标题 + 一句话描述 + 知识包路径」,
 * 完整方法论正文不再进 system prompt(避免每轮全量占用上下文)。知识包由
 * {@link BuiltInSkills#materialize} 物化到<b>系统技能目录</b>
 * {@code <系统目录>/skills/}(§5.10/§13.8,不再拷入任务工作区),AI 需要执行某技能时
 * 经 {@code read_file} 按 {@link #knowledgePath()}(绝对路径,系统技能目录
 * 对 AI 工具只读放行)自行读取。
 *
 * <p>工具绑定复用 worker 已有的 {@code SubAgentTools}/{@code AskUserTool}(由 toolIds 声明对齐)。
 *
 * <p>红线(AGENTS.md §13):skill 的知识注入与工具授权一律交给 Spring AI 的
 * advisor / tool 原语,本类不实现任何 prompt 拼接或工具循环逻辑。
 */
public record Skill(

        /** 稳定标识,如 agent-dispatch。 */
        String id,

        /** 展示名。 */
        String title,

        /** 一句话说明何时使用该 skill(渐进式披露的「触发条件」索引)。 */
        String description,

        /**
         * 知识包路径,<b>绝对路径</b>(如 {@code C:\Users\...\.everyagent\skills\agent-dispatch.md})。
         * 指向 {@code classpath:skill/<id>.md} 物化到系统技能目录后的位置,AI 按需用
         * {@code read_file} 读取,不随 system prompt 全量注入。系统技能目录是系统目录中
         * 对 AI 文件工具唯一只读开放的子目录(PermissionGate READ 窄例外 + Sandbox 只读附加根),
         * 写操作仍硬拒。
         */
        String knowledgePath,

        /**
         * 该 skill 依赖的工具名(对齐 worker 实际工具名,而非 nagent 原始名):
         * nagent 的 {@code list_agent}/{@code wait_agent} 在 worker 侧为
         * {@code list_agents}/{@code wait_agents}。worker 主 Agent 已全量获得这些工具,
         * toolIds 仅作"文档声明"对齐,不额外做工具级 gate。
         */
        List<String> toolIds) {

    /** 渐进式披露条目:标题 + 一句话描述 + 知识包路径,不含方法论正文。 */
    public String toSystemText() {
        return "- **" + title + "**(`" + id + "`):" + description
                + " 完整方法论在: `" + knowledgePath;
    }
}
