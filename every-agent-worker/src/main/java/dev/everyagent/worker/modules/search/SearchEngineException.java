package dev.everyagent.worker.modules.search;

/**
 * 搜索引擎不可用/执行失败(rg 缺失、rg 异常退出且无结果等):provider 以本异常上抛,
 * 由统一 search 的调用护栏识别为「客户端可见错误」直接回灌 rpc.err(INTERNAL),
 * 而非按「单 provider 失败仅 WARN 跳过」处理——避免把「引擎整体不可用」静默成空结果。
 */
public class SearchEngineException extends RuntimeException {

    public SearchEngineException(String message) {
        super(message);
    }

    public SearchEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}