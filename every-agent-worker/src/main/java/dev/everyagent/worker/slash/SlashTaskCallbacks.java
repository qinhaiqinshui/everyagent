package dev.everyagent.worker.slash;

import dev.everyagent.worker.task.TaskEntry;
import dev.everyagent.plugin.api.slash.SlashCommandItem;
import dev.everyagent.plugin.api.slash.SlashTokenEncoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Slash 任务级 token 建后回调（基础设施·slash 基础）：
 * 对任务已写入的每个 token 解析 payload.slashId 反查 slash 条目并调业务 onSelect(taskId)。
 * 从 TaskManager 迁出——slash 注册与反查属 slash 基础设施，非任务编排。
 */
@Component
public class SlashTaskCallbacks {

    private static final Logger log = LoggerFactory.getLogger(SlashTaskCallbacks.class);

    private final SlashCommandRegistry slashRegistry;

    public SlashTaskCallbacks(SlashCommandRegistry slashRegistry) {
        this.slashRegistry = slashRegistry;
    }

    /**
     * slash 任务级 token 建后回调:对每个已写入 token 解析 payload.slashId 反查 slash 条目并调业务
     * onSelect(taskId)——注册方用 taskId 把业务标记写入内存并落盘 meta(自身实现)。整段
     * try/catch(RuntimeException) 兜底,反查 NotFound 与 onSelect 异常仅记日志,
     * 绝不影响任务创建/运行/续跑。新建任务与冷启动续跑(startRerun)共用。
     */
    public void notifySlashCallbacks(TaskEntry t, String taskId) {
        try {
            for (String opaque : t.slashTaskTokens()) {
                SlashTokenEncoder.ParsedToken parsed = SlashTokenEncoder.parseToken(opaque);
                if (parsed == null) {
                    continue;
                }
                String slashId = parsed.payload().path("slashId").asString(null);
                if (slashId == null || slashId.isEmpty()) {
                    continue;
                }
                SlashCommandItem item = slashRegistry.itemById(slashId);
                item.selectHandler().onSelect(item, taskId);
            }
        } catch (RuntimeException e) {
            log.warn("slash 任务令牌建后回调失败 task={}", taskId, e);
        }
    }
}
