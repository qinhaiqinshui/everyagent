package dev.everyagent.worker.task;

import dev.everyagent.worker.proto.TaskDtos.ModelSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link UnattendedAskUserCallback} 单元测试:任务级无人值守开关实时生效,
 * 拦截后逐题自动选择第一个选项,与真实作答文本格式一致。
 */
class UnattendedAskUserCallbackTest {

    private TaskEntry task() {
        ModelSnapshot snap = new ModelSnapshot("cfg", "openai-compat",
                "https://api.test", "test-model", null);
        return new TaskEntry("t-1", "任务", snap, "k", "ws", "defaultworkspace", "main-agent", 10_000);
    }

    @Test
    void autoAnswerSelectsFirstOption() {
        TaskEntry t = task();
        t.unattended = true;

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
        String result = wrapped.call(
                "{\"questions\":[{\"question\":\"主角性别?\",\"options\":[\"男\",\"女\"]}]}");

        assertEquals("主角性别?：男", result);
    }

    @Test
    void autoAnswerMultipleQuestions() {
        TaskEntry t = task();
        t.unattended = true;

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
        String result = wrapped.call(
                "{\"questions\":[{\"question\":\"选语言?\",\"options\":[\"Java\",\"Python\"]},"
                        + "{\"question\":\"选框架?\",\"options\":[\"Spring\",\"Quarkus\"]}]}");

        assertEquals("选语言?：Java\n选框架?：Spring", result);
    }

    @Test
    void autoAnswerSkipsEmptyQuestion() {
        TaskEntry t = task();
        t.unattended = true;

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
        String result = wrapped.call(
                "{\"questions\":[{\"question\":\"\",\"options\":[\"A\"]},"
                        + "{\"question\":\"有效问题?\",\"options\":[\"X\",\"Y\"]}]}");

        assertEquals("有效问题?：X", result);
    }

    @Test
    void autoAnswerSkipsQuestionWithNoOptions() {
        TaskEntry t = task();
        t.unattended = true;

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
        String result = wrapped.call(
                "{\"questions\":[{\"question\":\"无选项问题?\",\"options\":[]}]}");

        assertEquals("未提供任何有效问题,跳过提问。", result);
    }

    @Test
    void autoAnswerInvalidJson() {
        TaskEntry t = task();
        t.unattended = true;

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
        String result = wrapped.call("not json");

        assertEquals("问题解析失败,跳过提问。", result);
    }

    @Test
    void autoAnswerNoQuestions() {
        TaskEntry t = task();
        t.unattended = true;

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
        String result = wrapped.call("{\"questions\":[]}");

        assertEquals("未提供任何问题,跳过提问。", result);
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
        assertEquals("真实回答", wrapped.call("{\"questions\":[]}"));
        assertEquals(1, callCount[0]);

        // 开启:拦截,自动选第一个
        t.unattended = true;
        assertEquals("问题?：A", wrapped.call(
                "{\"questions\":[{\"question\":\"问题?\",\"options\":[\"A\",\"B\"]}]}"));
        assertEquals(1, callCount[0]); // delegate 未被再调用

        // 再关闭:恢复透传
        t.unattended = false;
        assertEquals("真实回答", wrapped.call("{\"questions\":[]}"));
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
