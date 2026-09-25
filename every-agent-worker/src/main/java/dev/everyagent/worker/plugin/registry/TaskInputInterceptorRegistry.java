package dev.everyagent.worker.plugin.registry;

import dev.everyagent.plugin.api.task.TaskInputInterceptor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TaskInputInterceptor 注册表（第 9 个注册表）。
 * 插件注册拦截器后，核心 onTaskInput / onDialogInsert 遍历此表先交给插件处理。
 */
@Component
public class TaskInputInterceptorRegistry {

    private final CopyOnWriteArrayList<TaskInputInterceptor> interceptors = new CopyOnWriteArrayList<>();

    public void register(TaskInputInterceptor interceptor) {
        interceptors.add(interceptor);
    }

    public List<TaskInputInterceptor> getInterceptors() {
        return interceptors;
    }

    public boolean isRegistered() {
        return !interceptors.isEmpty();
    }
}
