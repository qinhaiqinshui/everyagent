package dev.everyagent.worker.slash;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.task.TaskEntry;
import tools.jackson.databind.JsonNode;

/**
 * 统一处理用户消息里 inline opaque token 的提交解析(老项目
 * {@code composerTokenRegistry} 的 worker 侧等价物)。
 *
 * <p>机制:各 token 来源(如 skill / git.auto_sync)自行声明 {@link SlashTokenResolver}
 * 并注册为 Spring bean;本 handler 在构造期收集全部 resolver 为 {@code Map<kind, resolver>},
 * advisor 层({@link dev.everyagent.worker.skill.SlashTokenResolveAdvisor})只负责扫描正文、
 * 逐段调用 {@link #resolve(String, TaskEntry)} 按 kind 分发(透传任务上下文)——核心层对
 * kind 与 payload 内容零感知,
 * 新增来源只需加一个 {@code @Component SlashTokenResolver},核心零改动(对齐 old 可插拔哲学)。
 *
 * <p>兜底:解析失败或未知 kind 时 {@link #resolve(String, TaskEntry)} 返回原 opaque 串
 * (与老项目 {@code resolveTokenReplacement} 保留未知 token 的行为一致),resolver 返回
 * null 同样保留原串,绝不误删半成品 token。
 */
@Component
public class SlashTokenHandler {

    private final Map<String, SlashTokenResolver> resolvers;

    /** 构造期从 Spring 收集的全部 resolver 构建 kind→resolver 表。 */
    public SlashTokenHandler(List<SlashTokenResolver> resolverList) {
        Map<String, SlashTokenResolver> map = new java.util.HashMap<>();
        if (resolverList != null) {
            for (SlashTokenResolver r : resolverList) {
                map.put(r.kind(), r);
            }
        }
        this.resolvers = java.util.Collections.unmodifiableMap(map);
    }

    /** 是否已注册该 kind 的解析器。 */
    public boolean hasKind(String kind) {
        return kind != null && resolvers.containsKey(kind);
    }

    /**
     * 解析单个 opaque token 为提交给 AI 的文本(无任务上下文档,等价
     * {@link #resolve(String, TaskEntry)} 传 {@code task=null},不需要任务上下文的 kind 行为不变)。
     */
    public String resolve(String opaque) {
        return resolve(opaque, null);
    }

    /**
     * 解析单个 opaque token 为提交给 AI 的文本(advisor 层入口,携带任务上下文):
     * <ul>
     *   <li>非 opaque 格式 / 解析失败 → 原样返回(兜底保留);</li>
     *   <li>未知 kind → 原样返回;</li>
     *   <li>已知 kind → resolver 输出:返回空串即「从 AI 上下文剥离」(如 git.auto_sync),
     *       返回 null 表示「本 resolver 放弃解析」→ 同样保留原串(如 system.external_file
     *       缺任务上下文时)——handler 层统一兜底,绝不误删半成品 token。</li>
     * </ul>
     */
    public String resolve(String opaque, TaskEntry task) {
        SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(opaque);
        if (parsed == null) {
            return opaque;
        }
        SlashTokenResolver resolver = resolvers.get(parsed.kind());
        if (resolver == null) {
            return opaque;
        }
        JsonNode payload = parsed.payload() == null ? Json.obj() : parsed.payload();
        String replaced = resolver.resolveSubmissionText(payload, task);
        return replaced == null ? opaque : replaced;
    }

    /** 单个 token kind 的提交解析器(各来源自管,核心只按 kind 分发)。 */
    public interface SlashTokenResolver {

        /** 固定 kind(与构造 opaque 时的 kind 一致)。 */
        String kind();

        /**
         * 把 payload 解析为提交给 AI 的替换文本。
         * 返回空串表示「清空该 token」(如 git.auto_sync 仅是触发标记,不应进入模型上下文),
         * 返回非空串表示用该文本替换胶囊(如 skill 的技能名)。
         */
        String resolveSubmissionText(JsonNode payload);

        /**
         * 任务上下文感知变体(可选覆写):需要任务挂靠信息(workspaceRoot 等)的 kind
         * (如 system.external_file 注册外部授权根)在此实现;默认委托无上下文版本,
         * 既有实现零改动。{@code task} 可空(无任务上下文的调用路径),实现方按需兜底。
         */
        default String resolveSubmissionText(JsonNode payload, TaskEntry task) {
            return resolveSubmissionText(payload);
        }
    }
}
