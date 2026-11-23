package dev.everyagent.plugin.workflow;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import dev.everyagent.plugin.api.spi.WorkspaceManager;
import dev.everyagent.plugin.api.spi.WorkspaceSandbox;

/**
 * 测试用 WorkspaceManager 桩：systemDir 指向临时目录，注册表由测试手工配置
 * （「工作区缺失」标记的判定路径与真实实现同构：roots + idOfRoot）。
 */
final class FakeWorkspaces implements WorkspaceManager {

    private final Path systemDir;
    private final Map<String, String> rootToId = new ConcurrentHashMap<>();
    private final List<Path> roots = new CopyOnWriteArrayList<>();

    FakeWorkspaces(Path systemDir) {
        this.systemDir = systemDir;
    }

    /** 注册一个工作区（root → 稳定 id）。 */
    void register(Path root, String id) {
        Path norm = root.toAbsolutePath().normalize();
        roots.add(norm);
        rootToId.put(norm.toString(), id);
    }

    @Override
    public String idOfRoot(String root) {
        return rootToId.get(Path.of(root).toAbsolutePath().normalize().toString());
    }

    @Override
    public Path systemDir() {
        return systemDir;
    }

    @Override
    public Path defaultRoot() {
        return systemDir;
    }

    @Override
    public WorkspaceSandbox sandboxFor(String raw) throws IOException {
        throw new UnsupportedOperationException("测试桩不提供沙箱");
    }

    @Override
    public List<Path> pruneStaleAndListMountRoots() {
        return List.copyOf(roots);
    }
}
