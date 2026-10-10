package dev.everyagent.plugin.sandbox.codex.setup;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * 编排层 → 提权 helper 的载荷（对应 codex ElevationPayload，设计文档 §2.4）。
 *
 * <p>编码链对齐 run_setup_exe_payload：payload → JSON → BASE64_STANDARD →
 * helper 命令行单参数（{@code --setup-payload <b64>}；超过 24,000 UTF-16 单位的
 * argv 阈值时落 {@code <codexHome>/.sandbox/setup_payload.json} 传
 * {@code --setup-payload-file <path>}）。字段名 snake_case 对齐 codex wire 形态。
 *
 * <p>roots 全由 payload 带入（helper 不做策略决策——分析文档 §5 分层原则）。
 */
public final class SetupPayload {

    /** 协议版本（对齐 codex SETUP_VERSION=5；marker/secrets/payload 三处共用闸门值）。 */
    public static final int SETUP_VERSION = 5;

    /** setup 模式（对齐 SetupMode 的取用子集 + 自有 Remove）。 */
    public enum Mode {
        /** 完整 setup：账户 + 网络 + ACL + 目录锁定 + marker（对齐 run_setup_full）。 */
        FULL("full"),
        /** 只建账户/网络/目录，不动 roots（对齐 run_provision_only）。 */
        PROVISION_ONLY("provision-only"),
        /** 两阶段卸载（对齐 clean_up_packaged_windows_sandbox）。 */
        REMOVE("remove");

        private final String wire;

        Mode(String wire) {
            this.wire = wire;
        }

        public String wire() {
            return wire;
        }

        public static Mode fromWire(String wire) {
            for (Mode m : values()) {
                if (m.wire.equals(wire)) {
                    return m;
                }
            }
            throw new IllegalArgumentException("unknown setup mode: " + wire);
        }
    }

    /** 载荷落盘文件名（超 argv 阈值时）。 */
    public static final String PAYLOAD_FILE_NAME = "setup_payload.json";

    /** argv 单参数阈值（对齐 launch_environment::needs_environment 的 24,000 UTF-16 单位）。 */
    public static final int MAX_ARG_UTF16_UNITS = 24_000;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 载荷模型（字段名即 JSON 键）。 */
    public static final class Model {
        @JsonProperty("version")
        public int version = SETUP_VERSION;
        @JsonProperty("offline_username")
        public String offlineUsername;
        @JsonProperty("online_username")
        public String onlineUsername;
        @JsonProperty("group_name")
        public String groupName;
        @JsonProperty("codex_home")
        public String codexHome;
        @JsonProperty("real_user")
        public String realUser;
        @JsonProperty("command_cwd")
        public String commandCwd;
        @JsonProperty("read_roots")
        public List<String> readRoots = new ArrayList<>();
        @JsonProperty("write_roots")
        public List<String> writeRoots = new ArrayList<>();
        @JsonProperty("deny_read_paths")
        public List<String> denyReadPaths = new ArrayList<>();
        @JsonProperty("deny_write_paths")
        public List<String> denyWritePaths = new ArrayList<>();
        @JsonProperty("proxy_ports")
        public List<Integer> proxyPorts = new ArrayList<>();
        @JsonProperty("allow_local_binding")
        public boolean allowLocalBinding;
        @JsonProperty("mode")
        public String mode = Mode.FULL.wire;
        @JsonProperty("refresh_only")
        public boolean refreshOnly;
    }

    private final Model model;

    private SetupPayload(Model model) {
        this.model = model;
    }

    /** 构造默认载荷（version 已置 SETUP_VERSION）。 */
    public static SetupPayload create() {
        return new SetupPayload(new Model());
    }

    public Model model() {
        return model;
    }

    public Mode mode() {
        return Mode.fromWire(model.mode);
    }

    public SetupPayload mode(Mode mode) {
        model.mode = mode.wire;
        return this;
    }

    public SetupPayload accounts(String prefix, String codexHome, String realUser) {
        model.offlineUsername = dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts
                .offlineUsername(prefix);
        model.onlineUsername = dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts
                .onlineUsername(prefix);
        model.groupName = dev.everyagent.plugin.sandbox.codex.accounts.SandboxAccounts
                .groupName(prefix);
        model.codexHome = Objects.requireNonNull(codexHome);
        model.realUser = Objects.requireNonNull(realUser);
        return this;
    }

    public SetupPayload proxyPorts(List<Integer> ports) {
        model.proxyPorts = new ArrayList<>(ports);
        return this;
    }

    public SetupPayload writeRoots(List<String> roots) {
        model.writeRoots = new ArrayList<>(roots);
        return this;
    }

    /** JSON 序列化（UTF-8）。 */
    public String toJson() {
        try {
            return MAPPER.writeValueAsString(model);
        } catch (IOException e) {
            throw new IllegalStateException("serialize setup payload failed", e);
        }
    }

    /** BASE64(JSON)——命令行单参数形态。 */
    public String encodeBase64() {
        return Base64.getEncoder().encodeToString(toJson().getBytes(StandardCharsets.UTF_8));
    }

    /** 从 BASE64(JSON) 解码（helper 侧入口）。 */
    public static SetupPayload decodeBase64(String base64) throws IOException {
        byte[] json = Base64.getDecoder().decode(base64);
        Model model = MAPPER.readValue(json, Model.class);
        if (model.version != SETUP_VERSION) {
            throw new IOException("setup version mismatch: expected " + SETUP_VERSION
                    + ", got " + model.version);
        }
        return new SetupPayload(model);
    }

    /**
     * argv 阈值判定（对齐 needs_environment：b64 长度 ≤ 阈值即可走单参数）。
     */
    public static boolean fitsSingleArg(String payloadBase64) {
        return payloadBase64.length() <= MAX_ARG_UTF16_UNITS;
    }

    /** 载荷落盘（超阈值回退路径）。 */
    public static Path writePayloadFile(Path codexHome, SetupPayload payload) throws IOException {
        Path file = codexHome.resolve(".sandbox").resolve(PAYLOAD_FILE_NAME);
        Files.createDirectories(file.getParent());
        Files.writeString(file, payload.toJson(), StandardCharsets.UTF_8);
        return file;
    }

    /** 从落盘文件读取载荷。 */
    public static SetupPayload readPayloadFile(Path file) throws IOException {
        Model model = MAPPER.readValue(Files.readString(file, StandardCharsets.UTF_8),
                Model.class);
        if (model.version != SETUP_VERSION) {
            throw new IOException("setup version mismatch: expected " + SETUP_VERSION
                    + ", got " + model.version);
        }
        return new SetupPayload(model);
    }
}
