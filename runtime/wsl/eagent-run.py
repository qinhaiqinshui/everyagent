#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""eagent-run — 发行版侧启动器(wsl-bwrap 沙箱 / wsl-direct 直连,见文档 §4.2/§4.4/§4.8)。

两种模式(载荷 \"mode\" 区分):
  * wsl-bwrap(默认):worker(JVM, Windows) → wsl.exe -d <发行版> -e python3 eagent-run.py
    → stdin 读 JSON 载荷 → setsid+登记 pgid → setrlimit → exec bwrap(挂载命名空间)
    → 子进程(bash -c <命令>)在沙箱内运行。命令经 stdin 传递不经 argv,绕开 wsl.exe
    引号/编码坑;stdout/stderr 原样回传。
  * wsl-direct(载荷 \"mode\": \"direct\"):wsl.exe -d <发行版> -u root -e python3 eagent-run.py
    → trusted 阶段:幂等挂载工作区(mount -t drvfs)→ 网络 deny 时 unshare -n
    → 装 seccomp 静态过滤器(deny mount 族 + 防绕过)→ no_new_privs
    → setsid+登记 pgid → setrlimit → exec bash -lc <AI 命令>(继承 seccomp)。
    AI 在发行版内拥有 root 完整权限(可装软件/改配置/删系统文件),但 mount/umount/
    pivot_root/init_module 等被内核 EPERM 硬拒 —— 宿主盘 automount 关闭 + 手动挂载
    工作区,root 也无法主动挂载宿主,实现「发行版可丢弃、宿主不可达」的硬隔离。

seccomp 提权拦截(仅 wsl-bwrap 载荷 \"seccomp\": true):见 docs/seccomp-privilege-interception.md。
  fork 出 supervisor(父)+ sandbox(子);父进程装 seccomp 用户通知过滤器(RET_USER_NOTIF
  命中 execve/execveat),子进程 exec bwrap 继承过滤器。每次 execve 内核先通知父进程:
  非 setuid → 自动放行(快路径);setuid(sudo/su 等)→ 经 stdout 控制帧请求 worker 授权,
  读 stdin 控制帧结果 → 拒绝 EPERM;授权(reroot=true)同样 EPERM 终止沙箱内该次 exec,
  但随发 priv-reroot 帧 + exit 75 —— 授权语义(§6A.2)= worker 将以 WSL root 在发行版内
  重跑原始命令(沙箱内真实提权物理不可行:NNP 必置 + userns 不映射 0 + 基座只读)。
  通信帧(supervisor → worker,按行 JSON):out/err(base64)/priv-ask/priv-reroot/exit;
  worker → supervisor:priv-ans(ok/reroot)。启用 seccomp 时 stdout 全部帧化,stderr 原样。

安全不变量:本脚本运行在沙箱<b>外</b>(发行版内、非降权),载荷只信任 worker;
wsl-bwrap 模式工作区与授权根经 --bind 白名单挂入;wsl-direct 模式工作区经 drvfs
手动挂载,且 root 的 mount 被 seccomp 硬拒。
"""

import base64
import ctypes
import ctypes.util
import errno
import json
import os
import resource
import select
import signal
import stat
import subprocess
import sys

RUN_DIR = "/run/eagent"

# 发行版基础层:只读白名单(merged-usr 布局下 /bin 等是 symlink,ro-bind-try 兼容两种)
RO_BASE = ["/usr", "/etc", "/opt", "/var", "/bin", "/sbin", "/lib", "/lib64", "/libx32"]


# ---------------------------------------------------------------------------
# seccomp 用户通知(仅 Linux ≥ 5.0;x86_64;失败时降级为无拦截,不阻断命令)
# ---------------------------------------------------------------------------

class _SockFilter(ctypes.Structure):
    _fields_ = [("code", ctypes.c_ushort),
                ("jt", ctypes.c_ubyte),
                ("jf", ctypes.c_ubyte),
                ("k", ctypes.c_uint)]


class _SockFprog(ctypes.Structure):
    _fields_ = [("len", ctypes.c_ushort),
                ("filter", ctypes.POINTER(_SockFilter))]


class _SeccompData(ctypes.Structure):
    _fields_ = [("nr", ctypes.c_int),
                ("arch", ctypes.c_uint),
                ("instruction_pointer", ctypes.c_uint64),
                ("args", ctypes.c_uint64 * 6)]


class _SeccompNotif(ctypes.Structure):
    _fields_ = [("id", ctypes.c_uint64),
                ("pid", ctypes.c_uint32),
                ("flags", ctypes.c_uint32),
                ("data", _SeccompData)]


class _SeccompNotifResp(ctypes.Structure):
    _fields_ = [("id", ctypes.c_uint64),
                ("val", ctypes.c_int64),
                ("error", ctypes.c_int32),
                ("flags", ctypes.c_uint32)]


class _Iovec(ctypes.Structure):
    _fields_ = [("iov_base", ctypes.c_void_p),
                ("iov_len", ctypes.c_size_t)]


# 常量(x86_64)
SECCOMP_SET_MODE_FILTER = 1
SECCOMP_FILTER_FLAG_NEW_LISTENER = 1 << 3
# uapi/linux/seccomp.h 原文:#define SECCOMP_USER_NOTIF_FLAG_CONTINUE (1UL << 0) —— 值就是 1。
# 写成 1<<1 或 1<<31 都会被内核判「未知标志位」→ SEND 返回 EINVAL,子进程冻结在 execve。
SECCOMP_USER_NOTIF_FLAG_CONTINUE = 1 << 0
SECCOMP_RET_USER_NOTIF = 0x7FC00000
SECCOMP_RET_ALLOW = 0x7FFF0000
AUDIT_ARCH_X86_64 = 0xC000003E
SYS_EXECVE = 59
SYS_EXECVEAT = 322
SYS_SECCOMP = 317
PR_SET_NO_NEW_PRIVS = 38
S_ISUID = 0o4000
S_ISGID = 0o2000

# BPF:LD W ABS 4 → JEQ ARCH(错则 ALLOW)→ LD W ABS 0 → JEQ 59 → NOTIF → JEQ 322 → NOTIF → ALLOW
_BPF_INSNS = [
    _SockFilter(0x20, 0, 0, 4),                      # LD W ABS 4  (arch)
    _SockFilter(0x15, 0, 5, AUDIT_ARCH_X86_64),      # JEQ arch, false → +5 (ALLOW)
    _SockFilter(0x20, 0, 0, 0),                      # LD W ABS 0  (nr)
    _SockFilter(0x15, 0, 1, SYS_EXECVE),             # JEQ execve, false → +1 (execveat)
    _SockFilter(0x06, 0, 0, SECCOMP_RET_USER_NOTIF), # RET NOTIF
    _SockFilter(0x15, 0, 1, SYS_EXECVEAT),           # JEQ execveat, false → +1 (ALLOW)
    _SockFilter(0x06, 0, 0, SECCOMP_RET_USER_NOTIF), # RET NOTIF
    _SockFilter(0x06, 0, 0, SECCOMP_RET_ALLOW),      # RET ALLOW
]


def _libc():
    name = ctypes.util.find_library("c")
    if not name:
        return None
    try:
        return ctypes.CDLL(name, use_errno=True)
    except OSError:
        return None


def _kernel_continue_supported():
    """SECCOMP_USER_NOTIF_FLAG_CONTINUE 需 Linux ≥ 5.5。

    5.0–5.4 有 USER_NOTIF(RECV 可用)但 seccomp_notify_send 拒绝一切非零 flags
    (老内核是 `if (resp.flags) return -EINVAL`)——「放行真实 syscall」无法表达,
    逐事件授权设计在这类内核上不可用,必须走结构性禁提权降级,否则子进程冻结在 execve。
    """
    try:
        rel = os.uname().release.split("-")[0].split(".")
        return (int(rel[0]), int(rel[1])) >= (5, 5)
    except (ValueError, IndexError, OSError):
        return False


def _install_seccomp_listener():
    """装 seccomp 用户通知过滤器,返回监听 fd;失败(旧内核/权限不足)返回 None。"""
    libc = _libc()
    if libc is None:
        return None
    if not _kernel_continue_supported():
        sys.stderr.write("eagent-run: 内核 %s 无 CONTINUE(<5.5),seccomp 降级为结构性禁提权\n"
                         % os.uname().release)
        return None
    try:
        # 非特权进程安装 seccomp 过滤器需 no_new_privs(不再继承特权——但 setuid 目标
        # 的提权由 worker 授权后由内核放行,与 no_new_privs 语义一致,见设计文档 §6)
        if libc.prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0:
            return None
        arr = (_SockFilter * len(_BPF_INSNS))(*_BPF_INSNS)
        prog = _SockFprog(len(_BPF_INSNS), arr)
        fd = libc.syscall(SYS_SECCOMP, SECCOMP_SET_MODE_FILTER,
                          SECCOMP_FILTER_FLAG_NEW_LISTENER, ctypes.byref(prog))
        if fd < 0:
            return None
        return fd
    except (AttributeError, OSError):
        return None


def _ioc(dir_bits, typ, nr, size):
    """_IOC(dir,type,nr,size) = dir<<30 | size<<16 | type<<8 | nr(linux/ioctl.h 编码)。"""
    return (dir_bits << 30) | (size << 16) | (ord(typ) << 8) | nr


def _recv_notif(libc, fd):
    n = _SeccompNotif()
    # SECCOMP_IOCTL_NOTIF_RECV = _IOWR('!', 0, sizeof(seccomp_notif)) = 0xC0502100(80B)。
    # 勿改回旧式 `(2|1)<<30 | '!'<<8 | sizeof` ——sizeof 未左移 16 位会拼出错误请求号,
    # ioctl 永远失败 → 子进程永久阻塞在 execve(等一个发不出的放行)→ 整条链路 hang。
    ioctl_recv = _ioc(3, '!', 0, ctypes.sizeof(_SeccompNotif))
    if libc.ioctl(fd, ioctl_recv, ctypes.byref(n)) != 0:
        e = ctypes.get_errno()
        sys.stderr.write("eagent-run: NOTIF_RECV 失败 errno=%d req=0x%08x\n" % (e, ioctl_recv))
        return None
    return n


def _send_notif_resp(libc, fd, nid, error):
    """SEND 响应;返回 0 = 成功,非 0 = errno(ENOENT=通知已失效,可忽略)。

    放行必须置 SECCOMP_USER_NOTIF_FLAG_CONTINUE(1<<0,uapi 固定值):内核语义是
    「真正执行该 syscall」。不带此 flag 的 (error=0, val=0) 会被内核当作「跳过
    syscall、注入返回值 0」——execve 假成功、镜像未换,execvp 继续遍历 PATH 直至
    抛出 stale ENOENT(127)。拒绝路径走 error=EPERM,flags=0。
    error 必须取负号:内核 syscall_set_return_value 实现为 ax = error ?: val,
    libc 只把 -4095..-1 区间的返回值当错误——传正 errno(+1)会被当作「成功返回
    1」,bash 报 strerror(0)="Success"(假成功的另一种形态)。
    若 SEND 因 EINVAL 被拒(<5.5 内核不支持任何 flags,版本门禁漏网时),自动回退
    flags=0 重发——宁可退化为注入返回值,也不让子进程冻结在 execve。
    """
    r = _SeccompNotifResp()
    r.id = nid
    r.val = 0
    r.error = -abs(error) if error else 0
    r.flags = 0 if error else SECCOMP_USER_NOTIF_FLAG_CONTINUE
    # SECCOMP_IOCTL_NOTIF_SEND = _IOWR('!', 1, sizeof(seccomp_notif_resp)) = 0xC0182101(24B)
    ioctl_send = _ioc(3, '!', 1, ctypes.sizeof(_SeccompNotifResp))
    rc = libc.ioctl(fd, ioctl_send, ctypes.byref(r))
    if rc != 0 and not error and r.flags:
        e = ctypes.get_errno()
        if e == errno.EINVAL:  # 老内核拒绝 flags(无 CONTINUE):回退注入返回值 0
            sys.stderr.write("eagent-run: SEND EINVAL(内核无 CONTINUE),回退 flags=0\n")
            r.flags = 0
            rc = libc.ioctl(fd, ioctl_send, ctypes.byref(r))
    if rc != 0:
        return ctypes.get_errno()
    return 0


def _read_child_cstring(libc, pid, addr, limit=4096):
    """经 process_vm_readv 读子进程地址空间里的 NUL 结尾字符串(execve filename)。"""
    if addr == 0 or libc is None:
        return None
    buf = ctypes.create_string_buffer(limit)
    local = _Iovec(ctypes.cast(buf, ctypes.c_void_p), limit)
    remote = _Iovec(ctypes.c_void_p(addr), limit)
    n = libc.process_vm_readv(pid, ctypes.byref(local), 1, ctypes.byref(remote), 1, 0)
    if n <= 0:
        return None
    end = buf.raw.find(b"\0")
    s = buf.raw if end < 0 else buf.raw[:end]
    try:
        return s.decode("utf-8", "replace")
    except Exception:
        return None


def _is_setuid(path):
    try:
        st = os.stat(path)
    except OSError:
        return False
    return bool(st.st_mode & (S_ISUID | S_ISGID))


def _write_frame(frame):
    """把控制帧写 stdout(帧协议;未启用 seccomp 时 stdout 为原始输出,不会调用)。"""
    sys.stdout.write(json.dumps(frame, ensure_ascii=False) + "\n")
    sys.stdout.flush()


def seccomp_main(payload):
    """seccomp 提权拦截主流程:fork supervisor + sandbox,内核事件级拦截 setuid exec。"""
    libc = _libc()
    notif_fd = _install_seccomp_listener() if libc is not None else None
    bwrap_argv = build_bwrap_argv(payload)
    if notif_fd is None:
        # seccomp 不可用:退化为「结构性禁提权」——新建 user namespace 且不映射 uid 0,
        # 使沙箱内 setuid(0) 失败(EPERM),而非静默放行真实 root。安全性不回退。
        sys.stderr.write("eagent-run: seccomp 不可用(旧内核/权限),退化为结构性禁提权(--unshare-user)\n")
        bwrap_argv = bwrap_argv[:1] + ["--unshare-user"] + bwrap_argv[1:]
    run_id = payload.get("runId") or "r-unknown"

    # 子进程 stdout 捕获管道(supervisor 帧化回传;stderr 原样走 fd2)
    out_r, out_w = os.pipe()

    pid = os.fork()
    if pid == 0:
        # ---- sandbox 子进程:setrlimit → exec bwrap(继承 seccomp 过滤器)----
        try:
            os.close(out_r)
            os.dup2(out_w, sys.stdout.fileno())
            os.close(out_w)
        except OSError:
            pass
        try:
            if os.getpgrp() != os.getpid():
                os.setsid()
        except OSError:
            pass
        try:
            os.makedirs(RUN_DIR, exist_ok=True)
            with open(os.path.join(RUN_DIR, run_id + ".pgid"), "w") as f:
                f.write(str(os.getpgrp()))
        except OSError:
            pass
        set_limits(payload.get("limits") or {})
        try:
            os.execvp("bwrap", bwrap_argv)
        except OSError as e:
            sys.stderr.write("eagent-run: exec bwrap 失败: %s\n" % e)
            os._exit(127)

    # ---- supervisor(父):读子进程 stdout + seccomp 通知 + 等待退出 ----
    os.close(out_w)
    code = 1
    seccomp_on = notif_fd is not None
    try:
        _write_frame({"mk": "start", "seccomp": seccomp_on})
        out_eof = False      # out_r 写端全关:EOF 管道让 select 永续可读 → 摘出监听防 busy-spin
        notif_stall = 0      # notif_fd 可读却 RECV 失败:暂停监听计数,本轮末尾递减,到 0 下轮恢复
        while True:
            watch = [] if out_eof else [out_r]  # EOF 后摘出 out_r,否则 select 永续可读空转
            if seccomp_on and notif_stall == 0:
                watch.append(notif_fd)
            try:
                readables, _, _ = select.select(watch, [], [], 0.5)
            except InterruptedError:
                continue  # EINTR(如 SIGCHLD):重选,不吞掉整个主循环
            if notif_fd in readables:
                n = _recv_notif(libc, notif_fd)
                if n is None:
                    # 可读却取不到通知(ENOENT/EINTR 等):本轮暂停监听(notif_stall=1),
                    # 循环末尾递减归零后下轮自动恢复,避免假可读 busy-spin
                    notif_stall = 1
                    sys.stderr.write("eagent-run: seccomp notif RECV 失败,暂停监听一个周期\n")
                else:
                    name = None
                    if n.data.nr == SYS_EXECVE:
                        name = _read_child_cstring(libc, n.pid, n.data.args[0])
                    elif n.data.nr == SYS_EXECVEAT:
                        name = _read_child_cstring(libc, n.pid, n.data.args[1])
                    if name is None and n.data.nr in (SYS_EXECVE, SYS_EXECVEAT):
                        sys.stderr.write("eagent-run: notif pid=%d nr=%d 路径读取失败"
                                         "(process_vm_readv) → 按非 setuid 放行\n" % (n.pid, n.data.nr))
                    if name and _is_setuid(name):
                        _write_frame({"mk": "priv-ask", "id": n.id, "pid": n.pid,
                                      "syscall": "execve" if n.data.nr == SYS_EXECVE else "execveat",
                                      "path": name})
                        ans = sys.stdin.readline()
                        ok = False
                        reroot = False
                        try:
                            a = json.loads(ans) if ans else None
                            ok = bool(a and a.get("ok"))
                            reroot = ok and bool(a.get("reroot"))
                        except Exception:
                            ok = False
                        if reroot:
                            # 授权语义(§6A.2):沙箱内不提权(物理不可行),收掉沙箱进程组
                            # + priv-reroot 帧(exit 75)→ worker 以 WSL root 重跑原始命令。
                            # kill 必须先于 SEND:SEND EPERM 会先唤醒 bash,让它回用户态打印
                            # "Operation not permitted"(过程噪声);SIGKILL 前置则 bash 冻结
                            # 在内核等待通知,致命信号直接打断等待、不再回用户态,一行不打。
                            # SEND 仅为把通知收尾(进程已死,REPLIED 无副作用)。
                            try:
                                os.killpg(pid, signal.SIGKILL)
                            except OSError:
                                try:
                                    os.kill(pid, signal.SIGKILL)
                                except OSError:
                                    pass
                            _send_notif_resp(libc, notif_fd, n.id, errno.EPERM)
                            _write_frame({"mk": "priv-reroot", "id": n.id, "path": name})
                            code = 75  # EX_TEMPFAIL 专用:「已转 root 重跑」
                            break
                        err = _send_notif_resp(libc, notif_fd, n.id, 0 if ok else errno.EPERM)
                        if err not in (0, errno.ENOENT):
                            sys.stderr.write("eagent-run: seccomp SEND(priv) 失败 id=%d errno=%d\n"
                                             % (n.id, err))
                    else:
                        # 非 setuid(或读不到路径):快路径自动放行,不打断;静默(每条 exec
                        # 都会走这里,打日志只会污染 stderr 回传给模型),仅 SEND 失败才报
                        err = _send_notif_resp(libc, notif_fd, n.id, 0)
                        if err not in (0, errno.ENOENT):
                            sys.stderr.write("eagent-run: seccomp SEND(allow) 失败 id=%d errno=%d\n"
                                             % (n.id, err))
            if out_r in readables:
                try:
                    data = os.read(out_r, 65536)
                except OSError:
                    data = b""
                if data:
                    _write_frame({"mk": "out", "d": base64.b64encode(data).decode("ascii")})
                else:
                    # 写端全关(子进程 exec 失败/退出):摘出 out_r,防 EOF 永续可读空转;
                    # 收尾改由 waitpid 轮询(select 0.5s 超时兜底,CPU≈0)
                    out_eof = True
            # 暂停监听计数递减:本轮已跳过 notif_fd,归零后下轮恢复
            if notif_stall > 0:
                notif_stall -= 1
            # 子进程退出判定(非阻塞)
            done, status = os.waitpid(pid, os.WNOHANG)
            if done == pid:
                if os.WIFEXITED(status):
                    code = os.WEXITSTATUS(status)
                elif os.WIFSIGNALED(status):
                    code = 128 + os.WTERMSIG(status)
                else:
                    code = 1
                # 清空 stdout 残余(未 EOF 时才可能仍有数据)
                if not out_eof:
                    try:
                        while True:
                            data = os.read(out_r, 65536)
                            if not data:
                                break
                            _write_frame({"mk": "out", "d": base64.b64encode(data).decode("ascii")})
                    except OSError:
                        pass
                break
    except (OSError, KeyboardInterrupt):
        # 尽力收尾:仍回传退出码
        try:
            done, status = os.waitpid(pid, os.WNOHANG)
            if done == pid and os.WIFEXITED(status):
                code = os.WEXITSTATUS(status)
        except OSError:
            pass
    finally:
        try:
            os.close(out_r)
        except OSError:
            pass
        if notif_fd is not None:
            try:
                os.close(notif_fd)
            except OSError:
                pass
    _write_frame({"mk": "exit", "code": code})
    return code


# ---------------------------------------------------------------------------
# wsl-direct 模式:root 完整权限 + seccomp deny mount(硬隔离)
# ---------------------------------------------------------------------------

# 常量(x86_64)。SECCOMP_MODE_FILTER 是 prctl(PR_SET_SECCOMP) 的第二个参数(值 2);
# 而 seccomp() 系统调用的第一个参数 SECCOMP_SET_MODE_FILTER 值才是 1。
SECCOMP_MODE_FILTER = 2
PR_SET_SECCOMP = 22
SECCOMP_RET_ERRNO = 0x00050000
SECCOMP_RET_KILL_PROCESS = 0x80000000
CLONE_NEWNET = 0x40000000

# 挂载/内核加载类 syscall:deny 返回 EPERM(让 AI 看到明确 "Operation not permitted",
# 而非整进程被杀)。覆盖旧 mount/umount 与 Linux 5.2+ 新 mount API,以及 swap/kexec/
# 模块加载/换根/setns —— root 也无法经任何路径把宿主盘挂进来。
DENY_EPERM = {
    165,   # mount
    166,   # umount2 (x86_64 上 umount 与 umount2 同号)
    155,   # pivot_root
    167,   # swapon
    168,   # swapoff
    175,   # init_module
    176,   # delete_module
    313,   # finit_module
    428,   # open_tree
    429,   # move_mount
    430,   # fsopen
    431,   # fsconfig
    432,   # fsmount
    433,   # fspick
    486,   # mount_setattr
    246,   # kexec_load
    320,   # kexec_file_load
    272,   # unshare(网络命名空间由 trusted 阶段处理,AI 命令内无需再建)
    308,   # setns
}

# 绕过/探测类 syscall:deny 直接 KILL_PROCESS(不可见、不可捕捉,防 AI 试图解除/旁路 seccomp)
DENY_KILL = {
    317,   # seccomp(装/改过滤器,解除隔离)
    321,   # bpf(可能用于加载恶意 eBPF)
    101,   # ptrace(attach 其他进程)
}


def _shq(s):
    """bash 单引号包裹:内部单引号按 '\\'' 转义,返回带首尾单引号的字面量。"""
    return "'" + s.replace("'", "'\\''") + "'"


def _deny_mount_insns():
    """deny-mount 过滤器的 BPF 指令列表(与 seccomp_data 布局对应:偏移 0=nr, 4=arch)。

    指令流:
      LD W ABS 4                (arch)
      JEQ X86_64, jt=1, jf=0    (相等→跳过紧跟 KILL 落到 LD nr;不等→落到 KILL)
      RET KILL_PROCESS          (非 x86_64)
      LD W ABS 0                (nr)
      [对每个 DENY_EPERM: JEQ nr, jt=0, jf=1 → RET EPERM]
      [对每个 DENY_KILL: JEQ nr, jt=0, jf=1 → RET KILL_PROCESS]
      RET ALLOW
    """
    insns = []
    insns.append(_SockFilter(0x20, 0, 0, 4))
    insns.append(_SockFilter(0x15, 1, 0, AUDIT_ARCH_X86_64))
    insns.append(_SockFilter(0x06, 0, 0, SECCOMP_RET_KILL_PROCESS))
    insns.append(_SockFilter(0x20, 0, 0, 0))
    for nr in sorted(DENY_EPERM):
        insns.append(_SockFilter(0x15, 0, 1, nr))
        insns.append(_SockFilter(0x06, 0, 0, SECCOMP_RET_ERRNO | errno.EPERM))
    for nr in sorted(DENY_KILL):
        insns.append(_SockFilter(0x15, 0, 1, nr))
        insns.append(_SockFilter(0x06, 0, 0, SECCOMP_RET_KILL_PROCESS))
    insns.append(_SockFilter(0x06, 0, 0, SECCOMP_RET_ALLOW))
    return insns


def _run(cmd):
    """执行命令(参数列表,无 shell 注入);返回退出码,失败返回 -1。"""
    try:
        return subprocess.call(cmd, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except OSError:
        return -1


def _install_seccomp_deny_mount():
    """装静态 seccomp 过滤器:deny mount 族(EPERM)+ 防绕过(KILL)+ no_new_privs。

    返回 True = 装成功;False = 失败。失败必须 fail-closed(调用方终止命令),
    否则 root 可在无过滤器下任意 mount 宿主盘,硬隔离即失效。
    """
    libc = _libc()
    if libc is None:
        return False
    insns = _deny_mount_insns()
    arr = (_SockFilter * len(insns))(*insns)
    prog = _SockFprog(len(insns), arr)
    try:
        if libc.prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0:
            return False
        if libc.prctl(PR_SET_SECCOMP, SECCOMP_MODE_FILTER, ctypes.byref(prog), 0, 0) != 0:
            return False
        return True
    except (AttributeError, OSError):
        return False


def _ensure_mount(workspaces):
    """trusted 阶段幂等挂载工作区:已挂载跳过,未挂载 mkdir + mount -t drvfs。

    必须在装 seccomp 前执行(装了 seccomp 后 mount 被 EPERM)。
    单个工作区挂载失败只打印提示,不阻断其余——尽力而为。
    """
    for ws in workspaces or []:
        src = ws.get("src")
        dest = ws.get("dest")
        if not src or not dest:
            continue
        try:
            os.makedirs(dest, exist_ok=True)
        except OSError as e:
            sys.stderr.write("[sandbox] mkdir 挂载点失败 %s: %s\n" % (dest, e))
            continue
        if _run(["findmnt", "-n", dest]) == 0:
            continue  # 已挂载
        if _run(["mount", "-t", "drvfs", src, dest]) != 0:
            sys.stderr.write("[sandbox] 挂载失败 %s -> %s\n" % (src, dest))


def direct_main(payload):
    """wsl-direct 直连主流程:trusted 阶段(挂载/网络/seccomp)→ exec bash -lc <AI 命令>。"""
    run_id = payload.get("runId") or "d-unknown"
    sweep_stale()

    # ① trusted:幂等挂载工作区(seccomp 前,此时 mount 可用)
    _ensure_mount(payload.get("workspaces"))

    # ② trusted:网络 deny → unshare -n(seccomp 前,新建无 eth0 的 netns)
    if payload.get("network", "open") == "deny":
        libc = _libc()
        if libc is not None:
            try:
                libc.unshare(CLONE_NEWNET)
            except (AttributeError, OSError) as e:
                sys.stderr.write("[sandbox] 网络隔离 unshare 失败,继续: %s\n" % e)

    # ③ seccomp deny mount + no_new_privs:失败 fail-closed(硬隔离前提,绝不裸跑 root)
    if not _install_seccomp_deny_mount():
        sys.stderr.write("eagent-run: seccomp deny-mount 过滤器安装失败,拒绝执行(fail-closed)\n")
        return 1

    # ④ 会话/pgid 登记(供 worker 超时 pkill -g 收割;seccomp 不拦 setsid/write)
    if os.getpgrp() != os.getpid():
        try:
            os.setsid()
        except OSError:
            pass
    try:
        os.makedirs(RUN_DIR, exist_ok=True)
        with open(os.path.join(RUN_DIR, run_id + ".pgid"), "w") as f:
            f.write(str(os.getpgrp()))
    except OSError:
        pass

    # ⑤ 资源上限
    set_limits(payload.get("limits") or {})

    # ⑥ exec bash -lc(继承 seccomp + no_new_privs + netns)
    cwd = payload.get("cwd") or "/"
    command = payload.get("command") or "true"
    argv = ["bash", "-lc", "cd " + _shq(cwd) + " && " + command]
    try:
        os.execvp("bash", argv)
    except OSError as e:
        sys.stderr.write("eagent-run: exec bash 失败: %s\n" % e)
        return 127
    return 1  # 不可达


# ---------------------------------------------------------------------------
# 原启动器逻辑
# ---------------------------------------------------------------------------


def sweep_stale():
    """清理死会话遗留的 pgid 登记文件(进程组已不存在则删除;正常/被杀路径之外的兜底)。"""
    try:
        names = os.listdir(RUN_DIR)
    except OSError:
        return
    for name in names:
        if not name.endswith(".pgid"):
            continue
        path = os.path.join(RUN_DIR, name)
        try:
            with open(path) as f:
                pgid = int(f.read().strip())
            os.killpg(pgid, 0)  # 存活则保留
        except (ProcessLookupError, ValueError):
            try:
                os.unlink(path)
            except OSError:
                pass
        except OSError:
            pass  # PermissionError 等:保留,宁漏删不误删


def set_limits(lim):
    """载荷 limits → setrlimit;0/缺省 = 不限。RLIMIT_NPROC/AS 粗粒度,细化走 cgroup(Phase 2)。"""

    def soft(res, value):
        if value and value > 0:
            try:
                resource.setrlimit(res, (value, value))
            except (ValueError, OSError):
                pass  # 超 hard limit 等:放弃该项,不阻断执行

    soft(resource.RLIMIT_NPROC, lim.get("nproc", 0))
    soft(resource.RLIMIT_AS, lim.get("asMb", 0) * 1024 * 1024)
    soft(resource.RLIMIT_CPU, lim.get("cpuSec", 0))
    soft(resource.RLIMIT_FSIZE, lim.get("fsizeMb", 0) * 1024 * 1024)


def build_bwrap_argv(p):
    args = ["bwrap", "--die-with-parent"]
    if p.get("privileged"):
        # 提权:新建 user namespace 并在其内以 root(uid/gid 0)运行。
        # 宿主侧仍是发行版普通用户,不授予宿主 root;需内核允许非特权 userns(与 bwrap 同前置)。
        args += ["--unshare-user", "--uid", "0", "--gid", "0"]
    for d in RO_BASE:
        args += ["--ro-bind-try", d, d]
    args += ["--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp", "--tmpfs", "/run"]
    # resolv.conf 特例:/etc 整目录 ro-bind 挂入后,其内 resolv.conf 在发行版里常是
    # 悬空 symlink(→ /mnt/wsl/resolv.conf 或 /run/systemd/resolve/... ,沙箱内终点
    # 不存在)。bwrap 文件 bind 会沿 symlink 解析到不存在的终点 → "Can't create file
    # at /etc/resolv.conf"。把 dest 改写为 realpath 终点,并 --dir 递归补父目录
    # (bwrap 根是 tmpfs,mkdir_p 任意路径均成功;终点必然可建:指向 ro 基座内文件的
    # symlink 不悬空、不触发本分支)。
    binds = p.get("binds", [])
    try:
        _resolv_real = os.path.realpath("/etc/resolv.conf")
    except OSError:
        _resolv_real = "/etc/resolv.conf"
    if _resolv_real != "/etc/resolv.conf":
        args += ["--dir", os.path.dirname(_resolv_real)]
        binds = [dict(b, dest=_resolv_real) if b.get("dest") == "/etc/resolv.conf"
                 else b for b in binds]
    # 宿主绑定:工作区(规范 + 原生双挂载)与授权根,一律经 worker 校验后由载荷给出
    for b in binds:
        args += ["--bind", b["src"], b["dest"]]
    # 只读岛:可写区内的 ro 子路径(如需保护的 .git),后挂载遮蔽先挂载
    for r in p.get("roIslands", []):
        args += ["--ro-bind", r["src"], r["dest"]]
    if p.get("network", "deny") == "deny":
        args += ["--unshare-net"]  # 新 netns,仅 down 的 lo:一切 socket 失败(含 DNS/回环)
    if p.get("cwd"):
        args += ["--chdir", p["cwd"]]
    args += ["--clearenv"]  # 环境白名单重建:发行版环境零继承(防泄漏宿主 env)
    for k, v in p.get("env", {}).items():
        args += ["--setenv", str(k), str(v)]
    argv = p["argv"]
    args += ["--"] + [str(a) for a in argv]
    return args


def main():
    try:
        # 只读首行(载荷);seccomp 模式下 stdin 剩余内容留给 priv-ans 控制帧
        payload = json.loads(sys.stdin.readline())
    except Exception as e:  # noqa: BLE001 - 载荷损坏:报错退出,别让异常栈进 stdout
        sys.stderr.write("eagent-run: bad payload: %s\n" % e)
        return 2

    if payload.get("mode") == "direct":
        return direct_main(payload)

    if payload.get("seccomp"):
        return seccomp_main(payload)

    run_id = payload.get("runId") or "r-unknown"
    sweep_stale()

    # 新会话(独立 pgid)→ 登记 → exec bwrap:pgid 覆盖 bwrap 及全部后代。
    # setsid(2) 仅在进程已是进程组组长时返回 EPERM;而 wsl.exe -e 恰好把命令作为
    # 新进程组组长拉起(PID == PGID),此时建不了新会话是预期行为——跳过即可。
    if os.getpgrp() != os.getpid():
        try:
            os.setsid()
        except OSError:
            pass  # 已是组长/无法建会话:不阻断,pgid 登记与 pkill -g 收割仍有效
    try:
        os.makedirs(RUN_DIR, exist_ok=True)
        with open(os.path.join(RUN_DIR, run_id + ".pgid"), "w") as f:
            f.write(str(os.getpgrp()))
    except OSError:
        pass  # 登记失败不阻断:仅损失显式击杀路径,die-with-parent 仍在

    set_limits(payload.get("limits") or {})

    try:
        os.execvp("bwrap", build_bwrap_argv(payload))
    except OSError as e:
        sys.stderr.write("eagent-run: exec bwrap 失败: %s\n" % e)
        return 127


if __name__ == "__main__":
    sys.exit(main())
