package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.tools.PermissionGate.Op;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 责任链节点:读取 skill 目录(system skills dir,§13.8)内容直接放行。
 * 仅对 READ 生效(realpath 前缀判定,符号链接逃逸自然失配);WRITE/EXEC 不在此放行,
 * 继续走授权决议链(弹窗 / AI 审议)。本轮判定只负责「放行」,沙箱侧由
 * FsToolSupport 把 skills 目录作为只读附加根加入,读写是否真的可行仍由 Sandbox 兜底。
 */
@Component
public class SkillsReadAllowCheck implements PermissionCheck {

    private final WorkerProperties props;

    public SkillsReadAllowCheck(WorkerProperties props) {
        this.props = props;
    }

    @Override
    public PermissionDecision check(PermissionContext ctx) {
        if (ctx.op() != Op.READ || ctx.realPath() == null) {
            return PermissionDecision.skip();
        }
        Path skillsReal = skillsRealPath();
        if (skillsReal == null) {
            return PermissionDecision.skip(); // 技能目录未物化:交下一节点(NotFound/授权链)
        }
        if (ctx.realPath().startsWith(skillsReal)) {
            return PermissionDecision.allow("skills 目录只读放行");
        }
        return PermissionDecision.skip();
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