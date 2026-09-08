package dev.everyagent.worker.task;

/**
 * 一条用户输入。
 *
 * <p>{@code text} 是提交给 AI 的可见文本(前端已把 `@` 文件引用等 opaque token 解析为
 * 可读路径);{@code rawContent} 是原始输入(保留 opaque token 串,仅用于前端消息回放
 * 还原胶囊,不进入 AI 上下文、不参与任何业务语义)。{@code rawContent} 为空表示无原始
 * 内容(旧前端/纯文本输入),消费方应退化为只展示 {@code text}。
 */
public record UserInput(String text, String rawContent) {

    /** 纯文本输入(无原始内容)。 */
    public static UserInput of(String text) {
        return new UserInput(text, null);
    }

    /** 带原始内容的输入;rawContent 为空串时归一为 null。 */
    public static UserInput of(String text, String rawContent) {
        if (rawContent == null || rawContent.isEmpty()) {
            return new UserInput(text, null);
        }
        return new UserInput(text, rawContent);
    }
}
