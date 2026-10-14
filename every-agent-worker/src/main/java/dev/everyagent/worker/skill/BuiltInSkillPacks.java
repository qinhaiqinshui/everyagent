package dev.everyagent.worker.skill;

import java.io.IOException;
import java.util.List;

import dev.everyagent.plugin.api.skill.SkillMaterializer;
import dev.everyagent.worker.config.WorkerProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * worker 内置技能包的<b>物化器</b>(唯一职责):把 classpath 资源根
 * {@code builtin-skill/<id>/skill.md} 下的全部技能包经 plugin-api 的
 * {@link SkillMaterializer#materializeAll} 原子落盘到系统技能目录
 * {@code <skillsDir>/<id>/skill.md}(规格见 docs/ARCHITECTURE.md §7.17)。
 *
 * <p><b>不持有任何 title/description/清单</b>:目录即真相源,内置包与插件包、
 * 用户自制 skill 落盘后一律由 {@link SkillCatalog} 扫描发现;本类只负责「把 worker
 * 自带的包写到盘上」。物化幂等(与磁盘逐字节比较,一致零写入)、被删/被改坏可经
 * 重启 worker 复原。
 *
 * <p>资源根用 {@code builtin-skill/}(而非插件的 {@code skill/}):插件 classloader
 * 以 worker classloader 为 parent,同根会让任意插件把 worker 的内置包错当自己的代装
 * (父委派泄漏,实测复现过),两个根是隔离手段——详见 {@link SkillMaterializer} javadoc。
 *
 * <p>失败策略:物化异常 WARN 降级、不阻断启动(代价是内置 skill 整体消失,
 * 可重启 worker 后重试复原)。
 */
@Component
public class BuiltInSkillPacks {

    private static final Logger log = LoggerFactory.getLogger(BuiltInSkillPacks.class);

    private final WorkerProperties props;

    public BuiltInSkillPacks(WorkerProperties props) {
        this.props = props;
    }

    /** 启动时物化一次(幂等)。 */
    @PostConstruct
    void init() {
        materialize();
    }

    /**
     * 把 worker 内置技能包物化到系统技能目录(幂等;可反复调用以复原被误改/误删的包)。
     *
     * @return 已安装(发现并落盘,含幂等零写入)的 id 列表,按 id 升序;物化失败降级为空列表
     */
    public List<String> materialize() {
        try {
            List<String> installed = SkillMaterializer.materializeAll(
                    props.resolveSkillsDir(),
                    getClass().getClassLoader(),
                    SkillMaterializer.BUILTIN_SKILL_ROOT);
            log.info("内置技能包物化完成: {}", installed);
            return installed;
        } catch (IOException e) {
            log.warn("内置技能包物化失败,降级为跳过(不阻断启动;可重启 worker 后重试复原)", e);
            return List.of();
        }
    }
}
