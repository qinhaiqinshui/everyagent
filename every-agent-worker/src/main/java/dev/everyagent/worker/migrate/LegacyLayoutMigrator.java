package dev.everyagent.worker.migrate;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.proto.ShortIds;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 旧磁盘布局迁移命令(手动执行一次,幂等、可重试;不随 worker 启动自动跑)。
 *
 * <p>旧布局(相对系统目录 ~/.everyagent):
 * <pre>
 * data/workspaces.json          # 工作区注册表(无 id)
 * data/workspace-default.json   # 默认工作区纠正根覆盖(已废弃)
 * data/tasks/&lt;taskId&gt;/      # 任务平铺
 * data/sandbox/                 # 沙箱持久状态
 * data/keys/git-credential.key  # git 凭证全局密钥
 * workspace/                    # 默认工作区根(旧名)
 * wsl/distro/                   # WSL 托管发行版 rootfs
 * </pre>
 *
 * <p>新布局:
 * <pre>
 * defaultworkspace/             # 默认工作区根(改名)
 * workspaces/workspaces.json    # 唯一注册表(条目含 id)
 * workspaces/&lt;workspaceId&gt;/tasks/&lt;taskId&gt;/
 * sandbox/                      # 沙箱持久状态
 * sandbox/distro/               # WSL 托管发行版 rootfs
 * &lt;workspaceRoot&gt;/.everyagent/.git-credential.key  # git 凭证每工作区密钥
 * </pre>
 *
 * <p>distro 是 WSL 注册表绑定的 rootfs,不能直接搬目录——由本命令调用
 * {@code wsl.exe --unregister eagent}(Windows)后删除旧 wsl/,重启 worker 自动导入到
 * sandbox/distro。非 Windows 或 wsl.exe 不可用则仅提示。
 */
public final class LegacyLayoutMigrator {

    private static final String DEFAULT_WORKSPACE_ID = "defaultworkspace";
    private static final String REGISTRY_NAME = "workspaces.json";
    private static final String DEFAULT_OVERRIDE_NAME = "workspace-default.json";
    /** 旧全局密钥文件名(旧 GitCredentialStore 落 data/keys/git-credential.key,不带点)。 */
    private static final String OLD_GIT_KEY_NAME = "git-credential.key";
    /** 新工作区密钥文件名(与密文同级,带点隐藏文件 .git-credential.key)。 */
    private static final String GIT_KEY_NAME = ".git-credential.key";
    private static final String GIT_CREDENTIALS_NAME = ".git-credentials.enc";

    private final Path home;
    private final Path dataDir;
    private final Path workspacesDir;
    private final Path sandboxDir;

    private LegacyLayoutMigrator(Path home) {
        this.home = home;
        this.dataDir = home.resolve("data");
        this.workspacesDir = home.resolve("workspaces");
        this.sandboxDir = home.resolve("sandbox");
    }

    public static void main(String[] args) throws Exception {
        Path home = resolveHome(args);
        LegacyLayoutMigrator m = new LegacyLayoutMigrator(home);
        m.run();
    }

    private static Path resolveHome(String[] args) {
        for (int i = 0; i < args.length; i++) {
            if ("--home".equals(args[i]) && i + 1 < args.length) {
                return Path.of(args[++i]).toAbsolutePath().normalize();
            }
        }
        String env = System.getenv("EVERYAGENT_HOME");
        if (env != null && !env.isBlank()) {
            return Path.of(env).toAbsolutePath().normalize();
        }
        return Path.of(System.getProperty("user.home"), ".everyagent").toAbsolutePath().normalize();
    }

    private void run() throws IOException, InterruptedException {
        System.out.println("[migrate] 系统目录: " + home);

        // 幂等:新注册表已存在且旧任务目录不存在 → 已迁移过。
        Path newRegistry = workspacesDir.resolve(REGISTRY_NAME);
        Path oldTasks = dataDir.resolve("tasks");
        if (Files.isRegularFile(newRegistry) && !Files.isDirectory(oldTasks)) {
            System.out.println("[migrate] 已迁移(workspaces.json 已存在且旧 data/tasks 不存在),跳过");
            return;
        }

        // 1) 默认工作区改名 workspace → defaultworkspace。
        renameDefaultWorkspace();

        // 2) 载入旧注册表并分配 id。
        List<Entry> entries = loadOldRegistry();
        if (entries.isEmpty() && !Files.isDirectory(oldTasks)) {
            System.out.println("[migrate] 无旧注册表且无旧任务目录,无需迁移");
            return;
        }

        // 3) 迁移任务目录(按 meta.workspace 映射到 workspaceId)。
        migrateTasks(entries);

        // 4) 写新注册表(含 id;默认工作区纠正根并入 defaultworkspace 条目)。
        writeNewRegistry(entries);

        // 5) 沙箱持久状态 data/sandbox → sandbox。
        moveDirIfAbsent(dataDir.resolve("sandbox"), sandboxDir, "沙箱持久状态");

        // 6) git 凭证密钥:旧全局密钥复制给已有密文的工作区。
        copyGitKeyPerWorkspace(entries);

        // 7) 清理旧 data 与 wsl。
        cleanLegacyData();
        cleanLegacyWsl();

        System.out.println("[migrate] 迁移完成。重启 worker 生效(托管发行版将由 autoImport 重建到 sandbox/distro)");
    }

    private void renameDefaultWorkspace() throws IOException {
        Path oldDefault = home.resolve("workspace");
        Path newDefault = home.resolve("defaultworkspace");
        if (Files.isDirectory(oldDefault)) {
            if (Files.exists(newDefault)) {
                System.out.println("[migrate] 旧默认工作区 workspace/ 与 defaultworkspace/ 同时存在,保留 defaultworkspace/");
            } else {
                Files.move(oldDefault, newDefault);
                System.out.println("[migrate] 默认工作区改名: workspace → defaultworkspace");
            }
        }
    }

    private record Entry(String id, String root, long addedAt, List<String> externalRoots) {
    }

    private List<Entry> loadOldRegistry() throws IOException {
        List<Entry> out = new ArrayList<>();
        Path f = dataDir.resolve(REGISTRY_NAME);
        if (!Files.isRegularFile(f)) {
            return out;
        }
        Path initial = home.resolve("defaultworkspace").toAbsolutePath().normalize();
        Path oldInitial = home.resolve("workspace").toAbsolutePath().normalize();
        // 默认工作区纠正根覆盖(旧 workspace-default.json):若存在,默认条目 root 以它为准。
        String overrideRoot = readDefaultOverride();
        JsonNode arr = Json.parse(Files.readString(f));
        if (!arr.isArray()) {
            return out;
        }
        for (JsonNode n : arr) {
            String root = n.path("root").asString("");
            if (root.isEmpty()) {
                continue;
            }
            String key = Path.of(root).toAbsolutePath().normalize().toString();
            String id = n.path("id").isTextual() ? n.path("id").asString() : "";
            List<String> external = readExternalRoots(n);
            if (id.isEmpty()) {
                boolean isDefault = key.equals(initial.toString()) || key.equals(oldInitial.toString());
                id = isDefault ? DEFAULT_WORKSPACE_ID : ShortIds.next("w");
                if (isDefault) {
                    root = (overrideRoot != null && !overrideRoot.isBlank())
                            ? overrideRoot : home.resolve("defaultworkspace").toString();
                }
            }
            out.add(new Entry(id, root,
                    n.path("addedTs").asLong(System.currentTimeMillis()), external));
        }
        return out;
    }

    private String readDefaultOverride() {
        Path f = dataDir.resolve(DEFAULT_OVERRIDE_NAME);
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            String root = Json.parse(Files.readString(f)).path("root").asString("");
            return root.isEmpty() ? null : root;
        } catch (Exception e) {
            System.out.println("[migrate] 读取 workspace-default.json 失败,忽略: " + e.getMessage());
            return null;
        }
    }

    private static List<String> readExternalRoots(JsonNode n) {
        JsonNode arr = n.path("externalRoots");
        if (!arr.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode x : arr) {
            String s = x.asString("");
            if (!s.isEmpty()) {
                out.add(s);
            }
        }
        return List.copyOf(out);
    }

    private void migrateTasks(List<Entry> entries) throws IOException {
        Path oldTasks = dataDir.resolve("tasks");
        if (!Files.isDirectory(oldTasks)) {
            return;
        }
        int moved = 0;
        try (DirectoryStream<Path> taskDirs = Files.newDirectoryStream(oldTasks)) {
            for (Path taskDir : taskDirs) {
                if (!Files.isDirectory(taskDir)) {
                    continue;
                }
                Path meta = taskDir.resolve("meta.json");
                String workspaceRoot = null;
                if (Files.isRegularFile(meta)) {
                    try {
                        workspaceRoot = Json.parse(Files.readString(meta)).path("workspace").asString(null);
                    } catch (Exception e) {
                        System.out.println("[migrate] meta 读取失败(兜底归默认工作区): " + meta);
                    }
                }
                String wsId = idOfRoot(entries, workspaceRoot);
                if (wsId == null) {
                    wsId = DEFAULT_WORKSPACE_ID; // 无法归属:兜底默认工作区
                }
                String taskId = taskDir.getFileName().toString();
                Path dest = workspacesDir.resolve(wsId).resolve("tasks").resolve(taskId);
                if (Files.exists(dest)) {
                    continue; // 已迁移过(幂等)
                }
                Files.createDirectories(dest.getParent());
                moveDir(taskDir, dest);
                moved++;
            }
        }
        if (moved > 0) {
            System.out.println("[migrate] 迁移任务目录 " + moved + " 个");
        }
    }

    private static String idOfRoot(List<Entry> entries, String root) {
        if (root == null || root.isBlank()) {
            return null;
        }
        String key = Path.of(root).toAbsolutePath().normalize().toString();
        for (Entry e : entries) {
            if (Path.of(e.root()).toAbsolutePath().normalize().toString().equals(key)) {
                return e.id();
            }
        }
        return null;
    }

    private void writeNewRegistry(List<Entry> entries) throws IOException {
        if (entries.isEmpty()) {
            return;
        }
        Files.createDirectories(workspacesDir);
        Path f = workspacesDir.resolve(REGISTRY_NAME);
        Path tmp = f.resolveSibling(REGISTRY_NAME + ".tmp");
        ArrayNode arr = Json.arr();
        for (Entry e : entries) {
            ObjectNode o = Json.obj()
                    .put("id", e.id())
                    .put("root", e.root())
                    .put("addedTs", e.addedAt());
            if (!e.externalRoots().isEmpty()) {
                ArrayNode ext = Json.arr();
                e.externalRoots().forEach(ext::add);
                o.set("externalRoots", ext);
            }
            arr.add(o);
        }
        Files.writeString(tmp, Json.write(arr), StandardCharsets.UTF_8);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
        System.out.println("[migrate] 已写新注册表 workspaces/workspaces.json(" + entries.size() + " 项)");
    }

    private void moveDirIfAbsent(Path src, Path dest, String label) throws IOException {
        if (!Files.exists(src)) {
            return;
        }
        if (Files.exists(dest)) {
            System.out.println("[migrate] " + label + " 目标已存在,跳过: " + dest);
            return;
        }
        Files.createDirectories(dest.getParent());
        Files.move(src, dest);
        System.out.println("[migrate] 迁移 " + label + ": " + src + " -> " + dest);
    }

    private void copyGitKeyPerWorkspace(List<Entry> entries) throws IOException {
        Path oldKey = dataDir.resolve("keys").resolve(OLD_GIT_KEY_NAME);
        if (!Files.isRegularFile(oldKey)) {
            return;
        }
        byte[] keyBytes = Files.readAllBytes(oldKey);
        int copied = 0;
        for (Entry e : entries) {
            Path wsRoot = Path.of(e.root());
            Path enc = wsRoot.resolve(".everyagent").resolve(GIT_CREDENTIALS_NAME);
            Path key = wsRoot.resolve(".everyagent").resolve(GIT_KEY_NAME);
            if (Files.isRegularFile(enc) && !Files.isRegularFile(key)) {
                Files.createDirectories(key.getParent());
                Files.write(key, keyBytes);
                copied++;
            }
        }
        if (copied > 0) {
            System.out.println("[migrate] 旧全局 git 密钥复制到 " + copied + " 个工作区 .everyagent/");
        }
    }

    private void cleanLegacyData() throws IOException {
        deleteQuietly(dataDir.resolve("tasks"));
        deleteQuietly(dataDir.resolve("sandbox"));
        deleteQuietly(dataDir.resolve("keys"));
        deleteQuietly(dataDir.resolve(REGISTRY_NAME));
        deleteQuietly(dataDir.resolve(DEFAULT_OVERRIDE_NAME));
        deleteEmptyDir(dataDir);
        System.out.println("[migrate] 旧 data/ 已清理(若为空则删除目录本身)");
    }

    private void cleanLegacyWsl() throws IOException, InterruptedException {
        Path wslDir = home.resolve("wsl");
        if (!Files.exists(wslDir)) {
            return;
        }
        // WSL 托管发行版 rootfs 由注册表绑定,不能搬目录;unregister 后删目录,重启 autoImport 重建。
        boolean unregistered = false;
        if (isWindows()) {
            try {
                Process p = new ProcessBuilder("wsl.exe", "--unregister", "eagent")
                        .redirectErrorStream(true).start();
                p.waitFor();
                unregistered = p.exitValue() == 0;
            } catch (IOException e) {
                System.out.println("[migrate] wsl.exe 不可用,跳过 unregister: " + e.getMessage());
            }
        }
        if (unregistered) {
            System.out.println("[migrate] 已 wsl --unregister eagent(重启 worker 自动导入到 sandbox/distro)");
        } else {
            System.out.println("[migrate] 旧 wsl/ 目录存在:请确认已 wsl --unregister eagent 后删除,"
                    + "或重启 worker 由 autoImport 重建到 sandbox/distro");
        }
        if (unregistered) {
            deleteQuietly(wslDir);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static void moveDir(Path src, Path dest) throws IOException {
        try {
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicUnsupported) {
            Files.move(src, dest);
        }
    }

    private static void deleteQuietly(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try {
            deleteRecursively(p);
        } catch (IOException e) {
            System.out.println("[migrate] 删除失败(可手动清理): " + p + " - " + e.getMessage());
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) {
                    deleteRecursively(c);
                }
            }
        }
        Files.deleteIfExists(p);
    }

    private static void deleteEmptyDir(Path dir) {
        try {
            Files.deleteIfExists(dir);
        } catch (IOException e) {
            // 目录非空则保留,提示手动清理
            System.out.println("[migrate] data/ 非空未删除: " + e.getMessage());
        }
    }

    private LegacyLayoutMigrator() {
        throw new UnsupportedOperationException();
    }
}
