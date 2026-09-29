package dev.everyagent.worker.os;

import dev.everyagent.plugin.api.spi.SandboxBackend;
import dev.everyagent.plugin.api.spi.SandboxBackend.Access;
import dev.everyagent.plugin.api.spi.SandboxBackend.MountRequest;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 路径翻译中间人 —— 管理 {宿主路径 → 沙箱内路径} 映射表。
 *
 * <p>核心组件通过此注册表做路径翻译，消费方只调核心不感知沙箱。
 * 核心调 {@link SandboxBackend#mount} 拿到映射关系后自己查表翻译。
 *
 * <p>注册时机：工作区创建时、用户授权时、skills 目录初始化时。
 * 核心在适当时机调 {@link #register}，沙箱的 mount() 须幂等。
 */
@Component
public class SandboxPathRegistry {

    private static final Logger log = LoggerFactory.getLogger(SandboxPathRegistry.class);

    private final SandboxBackend sandbox;

    /** 宿主路径 → 沙箱内路径 */
    private final Map<Path, String> hostToSandbox = new ConcurrentHashMap<>();

    /** 沙箱内路径 → 宿主路径 */
    private final Map<String, Path> sandboxToHost = new ConcurrentHashMap<>();

    public SandboxPathRegistry(SandboxBackend sandbox) {
        this.sandbox = sandbox;
    }

    /**
     * 批量注册需要被沙箱访问的宿主路径。
     * 调沙箱的 mount()，保存返回的映射表。
     */
    public void register(List<MountRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        try {
            Map<Path, String> mounted = sandbox.mount(requests);
            hostToSandbox.putAll(mounted);
            mounted.forEach((host, sandboxPath) -> sandboxToHost.put(sandboxPath, host));
        } catch (RuntimeException e) {
            log.warn("[path-registry] mount 失败,路径翻译将退化为原路径: {}", e.getMessage());
        }
    }

    /** 单个注册（方便调用方）。 */
    public void register(Path hostPath, Access access) {
        register(List.of(new MountRequest(hostPath, access)));
    }

    /** 单个注册，默认读写。 */
    public void register(Path hostPath) {
        register(hostPath, Access.READ_WRITE);
    }

    /**
     * 宿主路径 → AI 可见路径;无映射则原样返回。
     * 用于把宿主路径注入 system prompt（如 skill 知识包路径）。
     */
    public String toSandboxPath(Path hostPath) {
        return hostToSandbox.getOrDefault(hostPath, hostPath.toString());
    }

    /**
     * AI 视角路径 → 宿主路径;无映射则返回 null。
     * 用于工具参数翻译：AI 传入沙箱内路径，翻译为宿主路径供 Java NIO 操作。
     * null 表示注册表外路径，调用方原样保留，让 Java NIO 自然报错。
     */
    public String toHostPath(String sandboxPath) {
        if (sandboxPath == null) {
            return null;
        }
        for (var entry : sandboxToHost.entrySet()) {
            String mount = entry.getKey();
            if (sandboxPath.equals(mount)) {
                return entry.getValue().toString();
            }
            if (sandboxPath.startsWith(mount + "/")) {
                return entry.getValue().toString() + sandboxPath.substring(mount.length());
            }
        }
        return null;
    }

    /**
     * 工作区删除时通知沙箱清理 + 清理映射表。
     */
    public void onWorkspaceRemoved(Path root) {
        try {
            sandbox.onWorkspaceRemoved(root);
        } catch (RuntimeException e) {
            log.warn("[path-registry] onWorkspaceRemoved 失败: {}", e.getMessage());
        }
        hostToSandbox.remove(root);
        sandboxToHost.values().remove(root);
    }
}
