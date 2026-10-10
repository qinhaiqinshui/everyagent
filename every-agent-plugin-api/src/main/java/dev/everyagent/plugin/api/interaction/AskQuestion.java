package dev.everyagent.plugin.api.interaction;

import java.util.List;
import java.util.Map;

/**
 * 交互选择题。id 由 worker 在 ask() 时以真实 askId 派生(askId_i)。
 *
 * <p>{@code fields} 为结构化信息槽（§12）：「标签 → 值」键值对，前端在 prompt 下、
 * options 上以信息块渲染（如授权弹窗的「目录: /c/root」「授权类型: 写入」）——
 * 机器可读字段与人类可读文案分离。仅作展示增强，不改变 ask 协议语义与回答解析；
 * 默认空 map（wire 序列化对空 map 省略该字段，must-ignore 双向兼容）。
 */
public record AskQuestion(String id, String prompt, List<AskOption> options,
                          Map<String, String> fields) {

    /** 兼容旧构造（fields 默认空）。 */
    public AskQuestion(String id, String prompt, List<AskOption> options) {
        this(id, prompt, options, Map.of());
    }
}
