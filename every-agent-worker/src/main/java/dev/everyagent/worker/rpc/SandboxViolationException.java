package dev.everyagent.worker.rpc;

/** 沙箱越界(架构 §13.6):映射为 rpc.err{SANDBOX_DENIED}。 */
public class SandboxViolationException extends RuntimeException {

    public SandboxViolationException(String message) {
        super(message);
    }
}
