package dev.everyagent.plugin.api.task;

/**
 * 准入检查结果。
 * @param admitted 是否准入
 * @param rejectReason 拒绝原因（admitted=false 时有值）
 */
public record AdmissionResult(boolean admitted, String rejectReason) {
    public static AdmissionResult admit() {
        return new AdmissionResult(true, null);
    }
    public static AdmissionResult reject(String reason) {
        return new AdmissionResult(false, reason);
    }
}
