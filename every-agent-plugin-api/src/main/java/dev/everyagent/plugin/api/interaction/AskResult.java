package dev.everyagent.plugin.api.interaction;

/**
 * 交互结果。status: "answered" | "timeout" | "cancelled"。
 */
public record AskResult(String status, String text) {
}
