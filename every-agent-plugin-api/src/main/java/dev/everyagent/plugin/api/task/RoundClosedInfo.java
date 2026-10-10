package dev.everyagent.plugin.api.task;

/**
 * 闭合轮信息（由 {@link RoundClosedListener} 回调传递）。
 *
 * @param roundId 稳定主键（闭合时由 RoundIndexStore 生成；旧行可能为 null）
 * @param startSeq 轮起始 seq
 * @param endSeq 轮结束 seq（闭合轮非 null）
 * @param index 轮序号（从 1 起）
 */
public record RoundClosedInfo(String roundId, long startSeq, Long endSeq, long index) {
}
