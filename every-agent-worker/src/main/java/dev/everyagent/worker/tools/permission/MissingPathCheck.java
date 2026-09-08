package dev.everyagent.worker.tools.permission;

import dev.everyagent.worker.rpc.NotFoundException;
import dev.everyagent.worker.tools.PermissionGate.Op;

import java.nio.file.Files;

import org.springframework.stereotype.Component;

/**
 * 责任链节点 2:读目标不存在 → NotFound 拒绝(IO 语义,不是授权拒绝)。
 * 仅对 READ 生效;写目标允许不存在(交由沙箱 resolveTarget 创建)。
 */
@Component
public class MissingPathCheck implements PermissionCheck {

    @Override
    public PermissionDecision check(PermissionContext ctx) {
        if (ctx.op() == Op.READ && ctx.norm() != null && !Files.exists(ctx.norm())) {
            return PermissionDecision.deny(new NotFoundException("路径不存在: " + ctx.rel()));
        }
        return PermissionDecision.skip();
    }
}