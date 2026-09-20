package dev.everyagent.worker.task;

import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UnattendedAskUserCallback} 单元测试:任务级无人值守开关实时生效,
 * 合成结果与真实执行按 {@code TaskEntry.unattended} 分流。
 */
class UnattendedAskUserCallbackTest {

    private TaskEntry task() {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "https://api.test", "test-model", null);
        return new TaskEntry("t-1", "任务", snap, "k", "ws", "defaultworkspace", "main-agent", 10_000);
    }

    @Test
    void interceptsWhenUnattendedTrue() {
        TaskEntry t = task();
        t.unattended = true;

        // delegate 若被调用则抛异常,证明短路未触达真实执行。
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("ask_user").description("test").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                throw new IllegalStateException("delegate should not be called");
            }
        };

        UnattendedAskUserCallback wrapped = new UnattendedAskUserCallback(delegate, t);
        String result = wrapped.call("{\"questions\":[{\"question\":\"选哪个?\",\"options\":[\"A\",\"B\"]}]}");

        assertEquals("当前无人值守,请按你推荐的实现。", result);
    }

    @Test
    void delegatesWhenUnattendedFalse() {
        TaskEntry t = task();
        t.unattended = false;

        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("ask_user").description("test").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                return "真实回答";
            }
        };

        UnattendedAskUserCallback wrapped = new UnattendedAskUserCallback(delegate, t);
        String result = wrapped.call("{\"questions\":[]}");

        assertEquals("真实回答", result);
    }

    @Test
    void realTimeToggle() {
        TaskEntry t = task();
        final int[] callCount = {0};
        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("ask_user").description("test").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                callCount[0]++;
                return "真实回答";
            }
        };

        UnattendedAskUserCallback wrapped = new UnattendedAskUserCallback(delegate, t);

        // 关闭:透传真实执行
        t.unattended = false;
        assertEquals("真实回答", wrapped.call("{}"));
        assertEquals(1, callCount[0]);

        // 开启:拦截,不再触达 delegate
        t.unattended = true;
        assertEquals("当前无人值守,请按你推荐的实现。", wrapped.call("{}"));
        assertEquals(1, callCount[0]); // delegate 未被再调用

        // 再关闭:恢复透传
        t.unattended = false;
        assertEquals("真实回答", wrapped.call("{}"));
        assertEquals(2, callCount[0]);
    }

    @Test
    void toolDefinitionDelegates() {
        TaskEntry t = task();
        t.unattended = true; // 开启也不影响定义透传

        ToolCallback delegate = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name("ask_user").description("向用户提问").inputSchema("{\"type\":\"object\"}").build();
            }

            @Override
            public String call(String toolInput) {
                return "unused";
            }
        };

        UnattendedAskUserCallback wrapped = new UnattendedAskUserCallback(delegate, t);
        assertEquals("ask_user", wrapped.getToolDefinition().name());
        assertEquals("向用户提问", wrapped.getToolDefinition().description());
    }
}
