#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""验证 eagent-run.py direct 模式的 seccomp BPF 过滤器逻辑(不依赖 wsl/内核)。

在 Windows/Linux 任意有 python3 的机器上运行:
    python3 scripts/verify-direct-seccomp.py

它复用 eagent-run.py 的常量与过滤器生成函数,用一个最小 BPF 解释器模拟执行,
断言:mount/umount/init_module 等 → EPERM;seccomp/bpf/ptrace → KILL;
execve/openat/read 等正常调用 → ALLOW;非 x86_64 arch → KILL。
"""

import importlib.util
import os
import sys
import types

HERE = os.path.dirname(os.path.abspath(__file__))
RUNNER = os.path.join(HERE, "..", "every-agent-desktop", "resources", "runtime", "eagent-run.py")

# Windows Python 无 resource 模块(Unix 专属):注入 stub 以便 import runner 常量
if "resource" not in sys.modules:
    stub = types.ModuleType("resource")
    stub.RLIMIT_NPROC = 6
    stub.RLIMIT_AS = 9
    stub.RLIMIT_CPU = 0
    stub.RLIMIT_FSIZE = 1
    stub.setrlimit = lambda *a, **k: None
    sys.modules["resource"] = stub

spec = importlib.util.spec_from_file_location("eagent_run", os.path.normpath(RUNNER))
er = importlib.util.module_from_spec(spec)
sys.modules["eagent_run"] = er
spec.loader.exec_module(er)


def simulate(nr, arch):
    """模拟执行 eagent-run 的 deny-mount 过滤器(复用真实指令生成函数)。

    seccomp_data 布局:偏移 0=nr(4B), 4=arch(4B)。LD W ABS k 从该偏移加载到 A。
    返回 (ret, insn_idx)。
    """
    insns = er._deny_mount_insns()
    data = {0: nr, 4: arch}

    pc = 0
    A = 0
    while pc < len(insns):
        ins = insns[pc]
        code = ins.code
        if code == 0x20:  # BPF_LD | BPF_W | BPF_ABS:k = seccomp_data 偏移
            A = data.get(ins.k, 0)
        elif code == 0x15:  # BPF_JMP | BPF_JEQ | BPF_K
            if A == ins.k:
                pc = pc + 1 + ins.jt
            else:
                pc = pc + 1 + ins.jf
            continue
        elif code == 0x06:  # BPF_RET | BPF_K
            return ins.k, pc
        pc += 1
    return None, -1


def expect(nr, arch, kind, desc):
    ret, idx = simulate(nr, arch)
    ok = False
    if kind == "EPERM":
        ok = ret == (er.SECCOMP_RET_ERRNO | 1)  # EPERM=1
    elif kind == "KILL":
        ok = ret == er.SECCOMP_RET_KILL_PROCESS
    elif kind == "ALLOW":
        ok = ret == er.SECCOMP_RET_ALLOW
    status = "PASS" if ok else "FAIL"
    print(f"[{status}] {desc}: nr={nr} -> ret=0x{ret:08x} (期望 {kind})")
    if not ok:
        sys.exit(1)


def main():
    print("=== arch 门禁 ===")
    expect(59, 0, "KILL", "非 x86_64 arch → KILL")
    expect(59, er.AUDIT_ARCH_X86_64, "ALLOW", "x86_64 + execve → ALLOW")

    print("\n=== deny EPERM(mount 族)===")
    for nr in sorted(er.DENY_EPERM):
        expect(nr, er.AUDIT_ARCH_X86_64, "EPERM", f"syscall {nr} → EPERM")
    expect(165, er.AUDIT_ARCH_X86_64, "EPERM", "mount(165) → EPERM")
    expect(166, er.AUDIT_ARCH_X86_64, "EPERM", "umount2(166) → EPERM")
    expect(313, er.AUDIT_ARCH_X86_64, "EPERM", "finit_module(313) → EPERM")
    expect(486, er.AUDIT_ARCH_X86_64, "EPERM", "mount_setattr(486) → EPERM")

    print("\n=== deny KILL(绕过类)===")
    for nr in sorted(er.DENY_KILL):
        expect(nr, er.AUDIT_ARCH_X86_64, "KILL", f"syscall {nr} → KILL")

    print("\n=== 正常调用 ALLOW ===")
    for nr, name in [(59, "execve"), (257, "openat"), (0, "read"), (1, "write"),
                     (39, "getpid"), (45, "brk"), (231, "exit_group"), (28, "madvise")]:
        expect(nr, er.AUDIT_ARCH_X86_64, "ALLOW", f"{name}({nr}) → ALLOW")

    print("\nALL ASSERTIONS PASSED")


if __name__ == "__main__":
    main()
