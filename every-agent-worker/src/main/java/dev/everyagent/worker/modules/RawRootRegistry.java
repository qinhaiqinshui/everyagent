package dev.everyagent.worker.modules;

import dev.everyagent.plugin.api.exception.BadParamsException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raw 系列会话级用户放行目录注册表(架构 §5.5 fs.grantRawRoot 行 / §14.6):
 * `fs.readRaw`/`fs.writeRaw`/`fs.statRaw` 的合法根集合除工作区根/外部授权根/技能目录/
 * PermissionGate 在途授权根之外,还并入本表——「本次会话用户经确认放行的目录」。
 *
 * <p>形态参照 {@link GrantRegistry}/{@link WorkspaceManager} 既有风格:
 * <ul>
 *   <li><b>分区键 = ownerKey</b>(连接命名空间,与频道 ACL 同一信任边界):同 apiKey 的
 *       多个前端共享放行集(一端确认、多端可用),与其他 apiKey 隔离;</li>
 *   <li><b>放行粒度 = 目录</b>,存「realpath + 词法形态」双形态(参照 {@link GrantRegistry}
 *       rootsOnGrant:用户经符号链接形态确认的路径,重试时词法/realpath 双前缀校验均能命中,
 *       不会陷入「确认后仍越界」循环):注册时解析最近已存在祖先,目录取自身、文件取所在
 *       目录;文件系统根(盘根)拒收;</li>
 *   <li><b>会话级内存态</b>:不落盘、不进沙箱后端下发,worker 重启即清——与任务级
 *       grants.json(§7.8)刻意区分:这是前端人工操作的临时放行,不是 AI 执行授权;</li>
 *   <li><b>唯一写入口 = 显式确认动作产生的 RPC</b>(fs.grantRawRoot,前端弹确认框后
 *       调用);readRaw/writeRaw/statRaw 自身绝不自动注册放行,防自授权循环。</li>
 * </ul>
 */
@Component
public class RawRootRegistry {

    /** ownerKey → 已放行目录(realpath + 词法形态;空 = 无放行)。 */
    private final Map<String, Set<Path>> byOwner = new ConcurrentHashMap<>();

    /**
     * 注册放行目录:路径须为机器绝对路径且**自身或父目录真实存在**(文件→取所在目录;
     * 不存在但父目录存在→取父目录,新建文件直写场景;再往上不回溯——避免把缺失路径
     * 放大成宽泛祖先目录),词法/realpath 双形态一并入册;文件系统根拒收。幂等。
     *
     * @return 实际放行的目录(realpath)
     */
    public Path grant(String ownerKey, String rawPath) {
        if (ownerKey == null || ownerKey.isBlank()) {
            throw new BadParamsException("缺少连接命名空间,无法注册放行目录");
        }
        if (rawPath == null || rawPath.isBlank()) {
            throw new BadParamsException("缺少参数 path(机器绝对路径)");
        }
        Path in = Path.of(rawPath.trim());
        if (!in.isAbsolute()) {
            throw new BadParamsException("path 必须是机器绝对路径: " + rawPath);
        }
        Path probe = in.normalize();
        if (!Files.exists(probe)) {
            probe = probe.getParent(); // 新建文件目标:父目录存在即可
        }
        if (probe == null || !Files.exists(probe)) {
            throw new BadParamsException("路径不存在(自身与父目录均缺失),无法放行: " + rawPath);
        }
        Path real;
        try {
            real = probe.toRealPath();
        } catch (IOException e) {
            throw new BadParamsException("路径不可解析(realpath 失败),无法放行: " + rawPath);
        }
        Path lexRoot = Files.isDirectory(probe) ? probe : probe.getParent();
        Path realRoot = Files.isDirectory(real) ? real : real.getParent();
        if (realRoot == null || realRoot.equals(realRoot.getRoot())) {
            throw new BadParamsException("放行目录不得是文件系统根(盘根): " + real);
        }
        Set<Path> roots = byOwner.computeIfAbsent(ownerKey, k -> ConcurrentHashMap.newKeySet());
        roots.add(realRoot);
        if (lexRoot != null && !lexRoot.equals(realRoot)) {
            roots.add(lexRoot); // 词法形态(符号链接形态确认的场景,重试时词法前缀可命中)
        }
        return realRoot;
    }

    /** 该命名空间已放行的目录列表(realpath + 词法形态;无放行返回空)。 */
    public List<Path> rootsOf(String ownerKey) {
        Set<Path> roots = byOwner.get(ownerKey);
        return roots == null ? List.of() : List.copyOf(roots);
    }
}
