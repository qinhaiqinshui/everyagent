package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.tools.PermissionGate.Op;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 责任链节点:skill 目录(system skills dir,§13.8)读写放行。
 * READ + WRITE 均直接放行(realpath 前缀判定,符号链接逃逸自然失配);
 * EXEC 不在此放行,继续走授权决议链。
 * 沙箱侧由 FsToolSupport / FsService 把 skills 目录作为附加根加入。
 */
@Component
public class SkillsReadAllowCheck implements PermissionCheck {

    private final WorkerProperties props;

    public SkillsReadAllowCheck(WorkerProperties props) {
        this.props = props;
    }

    @Override
    public PermissionDecision invoke(PermissionContext ctx, PermissionChain next) {
        if (ctx.realPath() == null) {
            return next.proceed(ctx);
        }
        Op op = ctx.op();
        if (op != Op.READ && op != Op.WRITE) {
            return next.proceed(ctx);
        }
        Path skillsReal = skillsRealPath();
        if (skillsReal == null) {
            return next.proceed(ctx); // 技能目录未物化:交下一节点(NotFound/授权链)
        }
        if (ctx.realPath().startsWith(skillsReal)) {
            return PermissionDecision.allow("skills 目录读写放行");
        }
        return next.proceed(ctx);
    }

    /** skills 目录 realpath;未物化(目录不存在)返回 null。 */
    private Path skillsRealPath() {
        try {
            return props.resolveSkillsDir().toRealPath();
        } catch (IOException e) {
            return null;
        }
    }
}