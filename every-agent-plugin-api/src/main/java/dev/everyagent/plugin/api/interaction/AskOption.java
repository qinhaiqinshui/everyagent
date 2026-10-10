package dev.everyagent.plugin.api.interaction;

/**
 * 交互选项。type=radio 渲染为单选按钮，type=input 渲染为文本输入框。
 * 前端纯渲染：收到什么画什么，不追加、不修改、不映射。
 */
public record AskOption(String label, String value, String type) {
    public static final String TYPE_RADIO = "radio";
    public static final String TYPE_INPUT = "input";
}
