package dev.everyagent.plugin.sandbox.codex.setup;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * setup_error.json 错误协议（对应 codex setup_error.rs；分析文档 §5.1.5）。
 *
 * <p>时序：orchestrator 启动 helper 前 {@link #clear}（清除失败只记日志）→
 * helper 失败时 {@link #write} {@code {code,message}} → orchestrator 见非 0
 * 退出码后 {@link #read} 还原 {@link SetupException}；读到即精确错误，
 * 读不到降级 orchestrator_helper_exit_nonzero，读失败
 * orchestrator_helper_report_read_failed。
 *
 * <p>code 为 snake_case 稳定标识（对齐 SetupErrorCode，兼作 metric tag）。
 */
public final class SetupErrorReport {

    /** 错误报告文件名。 */
    public static final String FILE_NAME = "setup_error.json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SetupErrorReport() {
    }

    // ---- 编排侧 code（orchestrator_*，对齐 SetupErrorCode 同名条目） ----

    public static final String ORCHESTRATOR_SANDBOX_DIR_CREATE_FAILED =
            "orchestrator_sandbox_dir_create_failed";
    public static final String ORCHESTRATOR_ELEVATION_CHECK_FAILED =
            "orchestrator_elevation_check_failed";
    public static final String ORCHESTRATOR_PAYLOAD_SERIALIZE_FAILED =
            "orchestrator_payload_serialize_failed";
    public static final String ORCHESTRATOR_HELPER_LAUNCH_FAILED =
            "orchestrator_helper_launch_failed";
    /** 用户在 UAC 弹窗点了「否」（ERROR_CANCELLED=1223 单列，不当作普通失败）。 */
    public static final String ORCHESTRATOR_HELPER_LAUNCH_CANCELED =
            "orchestrator_helper_launch_canceled";
    public static final String ORCHESTRATOR_HELPER_EXIT_NONZERO =
            "orchestrator_helper_exit_nonzero";
    public static final String ORCHESTRATOR_HELPER_REPORT_READ_FAILED =
            "orchestrator_helper_report_read_failed";
    /** helper 退出码 0 但 marker 未就绪（防假成功）。 */
    public static final String ORCHESTRATOR_HELPER_INCOMPLETE =
            "orchestrator_helper_incomplete";

    // ---- helper 侧 code（helper_*，对齐 SetupErrorCode 同名条目） ----

    public static final String HELPER_REQUEST_ARGS_FAILED = "helper_request_args_failed";
    public static final String HELPER_SANDBOX_DIR_CREATE_FAILED = "helper_sandbox_dir_create_failed";
    public static final String HELPER_USERS_GROUP_CREATE_FAILED = "helper_users_group_create_failed";
    public static final String HELPER_USER_CREATE_OR_UPDATE_FAILED =
            "helper_user_create_or_update_failed";
    public static final String HELPER_DPAPI_PROTECT_FAILED = "helper_dpapi_protect_failed";
    public static final String HELPER_USERS_FILE_WRITE_FAILED = "helper_users_file_write_failed";
    public static final String HELPER_SETUP_MARKER_WRITE_FAILED = "helper_setup_marker_write_failed";
    public static final String HELPER_SID_RESOLVE_FAILED = "helper_sid_resolve_failed";
    public static final String HELPER_FIREWALL_COM_INIT_FAILED = "helper_firewall_com_init_failed";
    public static final String HELPER_FIREWALL_POLICY_ACCESS_FAILED =
            "helper_firewall_policy_access_failed";
    public static final String HELPER_FIREWALL_POLICY_INEFFECTIVE =
            "helper_firewall_policy_ineffective";
    public static final String HELPER_FIREWALL_RULE_CREATE_OR_ADD_FAILED =
            "helper_firewall_rule_create_or_add_failed";
    public static final String HELPER_FIREWALL_RULE_VERIFY_FAILED =
            "helper_firewall_rule_verify_failed";
    public static final String HELPER_WFP_INSTALL_FAILED = "helper_wfp_install_failed";
    public static final String HELPER_SANDBOX_LOCK_FAILED = "helper_sandbox_lock_failed";
    public static final String HELPER_SETUP_LOCK_FAILED = "helper_setup_lock_failed";
    public static final String HELPER_ACL_APPLY_FAILED = "helper_acl_apply_failed";
    public static final String HELPER_UNKNOWN_ERROR = "helper_unknown_error";

    /** 报告模型（{code,message}）。 */
    public static final class Report {
        @JsonProperty("code")
        public String code;
        @JsonProperty("message")
        public String message;

        public Report() {
        }

        public Report(String code, String message) {
            this.code = code;
            this.message = message;
        }
    }

    /** 结构化 setup 失败（code + 可读消息；供上层按 code 分类重试/引导）。 */
    public static final class SetupException extends RuntimeException {
        private final String code;

        public SetupException(String code, String message) {
            super(code + ": " + message);
            this.code = code;
        }

        public SetupException(String code, String message, Throwable cause) {
            super(code + ": " + message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 报告路径：{@code <codexHome>/.sandbox/setup_error.json}。 */
    public static Path errorFile(Path codexHome) {
        return codexHome.resolve(".sandbox").resolve(FILE_NAME);
    }

    /** 清除旧报告（启动 helper 前调用；NotFound 视作已清）。 */
    public static void clear(Path codexHome) throws IOException {
        try {
            Files.delete(errorFile(codexHome));
        } catch (NoSuchFileException ignored) {
            // 对齐 clear_setup_error_report：NotFound 即成功
        }
    }

    /** helper 侧写报告（目录不存在则建）。 */
    public static void write(Path codexHome, String code, String message) throws IOException {
        Path file = errorFile(codexHome);
        Files.createDirectories(file.getParent());
        MAPPER.writerWithDefaultPrettyPrinter()
                .writeValue(file.toFile(), new Report(code, message));
    }

    /** orchestrator 侧读报告；缺失返回 empty，损坏抛 IOException（→ REPORT_READ_FAILED）。 */
    public static Optional<Report> read(Path codexHome) throws IOException {
        Path file = errorFile(codexHome);
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        return Optional.of(MAPPER.readValue(new String(bytes, StandardCharsets.UTF_8),
                Report.class));
    }
}
