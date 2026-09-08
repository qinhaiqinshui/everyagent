package dev.everyagent.worker.tools.permission;

/**
 * 授权档位(权限责任链授权决议的结论范围,与授权弹窗三选项一一对应):
 * <ul>
 *   <li>{@code RUN}:本轮运行内允许(纯内存,下一条用户输入到达即清);</li>
 *   <li>{@code TASK}:本任务全程允许(持久化 {@code data/tasks/&lt;taskId&gt;/grants.json});</li>
 *   <li>{@code DENY}:拒绝。</li>
 * </ul>
 */
public enum GrantScope {
    RUN, TASK, DENY
}