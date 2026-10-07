package dev.everyagent.plugin.sandbox.codex;

import dev.everyagent.plugin.sandbox.codex.setup.SetupErrorReport;
import dev.everyagent.plugin.sandbox.codex.setup.SetupOrchestrator;
import dev.everyagent.plugin.sandbox.codex.setup.SetupPayload;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * setup 触发器（{@link CodexSandboxProvider#create} 首次路径与
 * {@link CodexCommandExecutor} 凭据自愈路径共用）。
 *
 * <p>从 {@link CodexSandboxManager} 当前状态（options + 写根）组装 FULL 载荷交给
 * {@link SetupOrchestrator}：
 * <ul>
 *   <li>force=false：marker + 凭据双闸门短路（幂等），仅在首次 create 时触发；</li>
 *   <li>force=true：跳过短路无条件重跑完整 setup——凭据失配（1326 等凭据类失败码）
 *       自愈路径。完整 setup 会重新生成两账户密码（已存在账户经
 *       {@code NetUserSetInfo(1003)} 重置）并重写 DPAPI 凭据文件；未提权时经
 *       runas 弹一次 UAC（与首次 setup 同一通道），随后调用方原地重试命令。</li>
 * </ul>
 *
 * <p>payload 组装逻辑原先内联在 Provider 里；执行期自愈需要同一份（含 UAC 取消
 * 的错误映射），抽取至此避免两处漂移。
 */
final class CodexSetupCoordinator {

    private static final Logger LOG = System.getLogger(CodexSetupCoordinator.class.getName());

    private CodexSetupCoordinator() {
    }

    /** 幂等 setup（marker 短路）；{@link CodexSandboxProvider#create} 首次路径。 */
    static void ensure(CodexSandboxManager manager) {
        run(manager, false);
    }

    /** 凭据轮换（强制完整重 setup，重新生成密码）；执行期凭据失配自愈路径。 */
    static void rotateCredentials(CodexSandboxManager manager) {
        run(manager, true);
    }

    private static void run(CodexSandboxManager manager, boolean force) {
        CodexSandboxOptions options = manager.options();
        List<String> roots = new ArrayList<>();
        for (Path root : manager.writeRoots()) {
            roots.add(root.toString());
        }
        SetupPayload payload = SetupPayload.create()
                .mode(SetupPayload.Mode.FULL)
                .accounts(options.accountPrefix(), options.codexHome().toString(),
                        System.getProperty("user.name"))
                .writeRoots(roots)
                .proxyPorts(options.proxyPorts());
        payload.model().allowLocalBinding = options.allowLocalBinding();
        try {
            SetupOrchestrator.ensureSetup(payload, force);
        } catch (SetupErrorReport.SetupException e) {
            String msg = "codex 沙箱 setup 失败: code=" + e.code() + " " + e.getMessage();
            if (SetupErrorReport.ORCHESTRATOR_HELPER_LAUNCH_CANCELED.equals(e.code())) {
                msg += "(用户在 UAC 弹窗拒绝了提权)";
            }
            LOG.log(Level.ERROR, msg);
            throw new IllegalStateException(msg, e);
        }
        if (force) {
            LOG.log(Level.INFO, "codex 沙箱凭据轮换完成(重 setup,marker 版本 {0},codexHome={1})",
                    new Object[] { SetupPayload.SETUP_VERSION, options.codexHome() });
        }
    }
}
