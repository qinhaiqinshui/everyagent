package dev.everyagent.plugin.sandbox.wslubuntu;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 已授权根的「发行版内视图」——宿主路径 ↔ WSL 内路径的<b>纯映射</b>（无 IO、无副作用）。
 *
 * <p>翻译只对<b>已授权的根及其子路径</b>生效，其余原样返回宿主形态：未挂进来的路径
 * 不该被报成发行版内路径。挂载点由 {@link WslPathMapper#toDirectMount} 推导
 * （{@code C:\a\b} → {@code /c/a/b}）。
 *
 * <p>与 {@link WslUbuntuSandboxBackend} 分开是为了可测：真实 {@code grant} 会发起
 * {@code wsl.exe} 挂载（best-effort、可能数十秒），而路径映射逻辑本身是纯函数。
 */
final class WslPathView {

    /** 已授权宿主根（宿主形态、已 normalize）。 */
    private final Set<Path> roots = ConcurrentHashMap.newKeySet();

    void add(Path hostRoot) {
        if (hostRoot != null) {
            roots.add(hostRoot.normalize());
        }
    }

    void remove(Path hostRoot) {
        if (hostRoot != null) {
            roots.remove(hostRoot.normalize());
        }
    }

    boolean contains(Path hostPath) {
        return hostPath != null && longestRoot(hostPath.normalize()) != null;
    }

    /** 宿主路径 → 发行版内路径；不在任何已授权根内则原样返回。 */
    String toSandbox(Path hostPath) {
        if (hostPath == null) {
            return null;
        }
        Path root = longestRoot(hostPath.normalize());
        if (root == null) {
            return hostPath.toString();
        }
        String mountPoint = WslPathMapper.toDirectMount(root);
        if (mountPoint == null) {
            return hostPath.toString();
        }
        Path tail = root.relativize(hostPath.normalize());
        return tail.toString().isEmpty()
                ? mountPoint
                : mountPoint + "/" + tail.toString().replace('\\', '/');
    }

    /** 发行版内路径 → 宿主路径；不在任何已授权挂载点内返回 null。 */
    Path toHost(String sandboxPath) {
        if (sandboxPath == null || sandboxPath.isBlank()) {
            return null;
        }
        String bestMount = null;
        Path bestRoot = null;
        for (Path root : roots) {
            String mp = WslPathMapper.toDirectMount(root);
            if (mp == null) {
                continue;
            }
            if (sandboxPath.equals(mp) || sandboxPath.startsWith(mp + "/")) {
                if (bestMount == null || mp.length() > bestMount.length()) {
                    bestMount = mp;
                    bestRoot = root;
                }
            }
        }
        if (bestMount == null) {
            return null;
        }
        String tail = sandboxPath.substring(bestMount.length());
        return tail.isEmpty() ? bestRoot : bestRoot.resolve(tail.substring(1));
    }

    /** 命中该宿主路径的最长已授权根（含自身）。 */
    private Path longestRoot(Path norm) {
        Path best = null;
        for (Path root : roots) {
            if (norm.startsWith(root) && (best == null || root.getNameCount() > best.getNameCount())) {
                best = root;
            }
        }
        return best;
    }
}