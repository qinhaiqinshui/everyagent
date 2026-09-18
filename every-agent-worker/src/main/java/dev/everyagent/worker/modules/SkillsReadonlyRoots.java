package dev.everyagent.worker.modules;

import dev.everyagent.worker.config.WorkerProperties;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 系统技能目录只读附加根(realpath + 词法形态)的共享解析(§13.8/§7.17)。
 *
 * <p>{@code skills/} 是 AI 文件工具对系统路径的唯一只读免授权例外;同时供 fs.* RPC
 * 的只读操作(read/list/reveal)消费——前端「打开文件」标签页读取 AI 已读的 skill
 * 正文时,经此根放行,与 read_file 工具同源。<b>写操作不在此放行</b>(§7.17),消费方
 * 自行决定是否把本根并入沙箱附加根。
 *
 * <p>解析成功后缓存(目录不会在运行期移动);<b>失败不缓存</b>——技能目录尚未物化
 * (BuiltInSkills.materialize 失败/延迟)时返回空列表,但不写入缓存,下次调用重试,
 * 使「物化晚于首次使用」能自愈(否则会永久缓存空根,直到 worker 重启)。
 */
public final class SkillsReadonlyRoots {

    private final WorkerProperties props;

    private volatile List<Path> cached;

    public SkillsReadonlyRoots(WorkerProperties props) {
        this.props = props;
    }

    /** 技能目录只读根列表(realpath + 词法形态);目录尚未物化时为空列表。 */
    public List<Path> get() {
        List<Path> snapshot = cached;
        if (snapshot != null) {
            return snapshot;
        }
        synchronized (this) {
            if (cached != null) {
                return cached;
            }
            Path lexical = props.resolveSkillsDir();
            try {
                Path real = lexical.toRealPath();
                List<Path> built = new ArrayList<>();
                built.add(real);
                if (!real.equals(lexical)) {
                    built.add(lexical);
                }
                cached = List.copyOf(built); // 仅成功才缓存
                return cached;
            } catch (IOException e) {
                // 技能目录尚未物化:不加根(其下路径本就按 NotFound 报错),不阻断;
                // 不缓存空结果,使后续物化可自愈
                return List.of();
            }
        }
    }
}
