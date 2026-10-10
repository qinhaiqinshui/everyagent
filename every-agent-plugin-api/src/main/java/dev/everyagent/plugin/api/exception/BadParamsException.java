package dev.everyagent.plugin.api.exception;

/** 参数缺失/非法 → rpc.err BAD_PARAMS。 */
public class BadParamsException extends RuntimeException {
    public BadParamsException(String message) {
        super(message);
    }
}
