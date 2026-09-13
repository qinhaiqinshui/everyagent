#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Every Agent 磁盘布局迁移脚本(旧 data/ 布局 → 新 workspaces/ 布局)。

手动执行一次,幂等、可重试;不随 worker 启动自动跑。

旧布局(相对系统目录 ~/.everyagent,即 EVERYAGENT_HOME):
    data/workspaces.json          工作区注册表(无 id)
    data/workspace-default.json   默认工作区纠正根覆盖(已废弃)
    data/tasks/<taskId>/          任务平铺
    data/sandbox/                 沙箱持久状态
    data/keys/git-credential.key  git 凭证全局密钥
    workspace/                    默认工作区根(旧名)
    wsl/distro/                   WSL 托管发行版 rootfs

新布局:
    defaultworkspace/                     默认工作区根(改名)
    workspaces/workspaces.json            唯一注册表(条目含 id,默认工作区也在册)
    workspaces/<workspaceId>/tasks/<taskId>/
    sandbox/                              沙箱持久状态
    sandbox/distro/                       WSL 托管发行版 rootfs
    <workspaceRoot>/.everyagent/.git-credential.key   git 凭证每工作区密钥

用法:
    python3 scripts/migrate-workspaces.py [--home <EVERYAGENT_HOME>]
    不传 --home 时默认 $EVERYAGENT_HOME 或 ~/.everyagent。

注意:WSL 托管发行版 rootfs 由 WSL 注册表绑定,不能搬目录——Windows 下本脚本
会先 `wsl.exe --unregister eagent`,删除旧 wsl/,重启 worker 时由 autoImport
自动重建到 sandbox/distro。
"""

import json
import os
import random
import secrets
import shutil
import string
import subprocess
import sys
import time

DEFAULT_WORKSPACE_ID = "defaultworkspace"
REGISTRY_NAME = "workspaces.json"
DEFAULT_OVERRIDE_NAME = "workspace-default.json"
# 旧全局密钥文件名(旧 GitCredentialStore 落 data/keys/git-credential.key,不带点)
OLD_GIT_KEY_NAME = "git-credential.key"
# 新工作区密钥文件名(与密文同级,带点隐藏文件 .git-credential.key)
GIT_KEY_NAME = ".git-credential.key"
GIT_CREDENTIALS_NAME = ".git-credentials.enc"

# w_ 短 id 生成(与 Java ShortIds 同形:w_ + 3 位随机盐 + base36 序号)
_B36_DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"
_ID_SALT = "".join(secrets.choice(string.ascii_lowercase + string.digits) for _ in range(3))
_id_counter = 0


def _base36(n):
    if n <= 0:
        return "0"
    out = []
    while n:
        n, r = divmod(n, 36)
        out.append(_B36_DIGITS[r])
    return "".join(reversed(out))


def next_wid():
    """生成下一个 w_ 短 id(进程内不重复)。"""
    global _id_counter
    _id_counter += 1
    return "w_" + _ID_SALT + _base36(_id_counter)


def norm(p):
    """绝对 + 规范化路径(字符串)。"""
    return os.path.normpath(os.path.abspath(p))


def resolve_home(args):
    for i, a in enumerate(args):
        if a == "--home" and i + 1 < len(args):
            return norm(args[i + 1])
    env = os.environ.get("EVERYAGENT_HOME")
    if env and env.strip():
        return norm(env)
    return norm(os.path.join(os.path.expanduser("~"), ".everyagent"))


def rename_default_workspace(home):
    old = os.path.join(home, "workspace")
    new = os.path.join(home, "defaultworkspace")
    if os.path.isdir(old):
        if os.path.exists(new):
            print("[migrate] 旧默认工作区 workspace/ 与 defaultworkspace/ 同时存在,保留 defaultworkspace/")
        else:
            shutil.move(old, new)
            print("[migrate] 默认工作区改名: workspace → defaultworkspace")


def read_default_override(data_dir):
    f = os.path.join(data_dir, DEFAULT_OVERRIDE_NAME)
    if not os.path.isfile(f):
        return None
    try:
        with open(f, encoding="utf-8") as fh:
            root = json.load(fh).get("root", "")
        return root if root else None
    except Exception as e:
        print(f"[migrate] 读取 {DEFAULT_OVERRIDE_NAME} 失败,忽略: {e}")
        return None


def read_external_roots(n):
    arr = n.get("externalRoots")
    if not isinstance(arr, list):
        return []
    return [s for s in arr if isinstance(s, str) and s]


def load_old_registry(home):
    data_dir = os.path.join(home, "data")
    f = os.path.join(data_dir, REGISTRY_NAME)
    if not os.path.isfile(f):
        return []
    initial = norm(os.path.join(home, "defaultworkspace"))
    old_initial = norm(os.path.join(home, "workspace"))
    override_root = read_default_override(data_dir)
    try:
        with open(f, encoding="utf-8") as fh:
            arr = json.load(fh)
    except Exception as e:
        print("[migrate] workspaces.json 读取失败,忽略注册表:", e)
        return []
    entries = []
    if not isinstance(arr, list):
        return entries
    for n in arr:
        if not isinstance(n, dict):
            continue
        root = n.get("root", "")
        if not root:
            continue
        key = norm(root)
        wid = n.get("id")
        wid = wid if isinstance(wid, str) and wid else ""
        external = read_external_roots(n)
        if not wid:
            is_default = key == initial or key == old_initial
            wid = DEFAULT_WORKSPACE_ID if is_default else next_wid()
            if is_default:
                root = override_root if override_root else os.path.join(home, "defaultworkspace")
        entries.append({
            "id": wid,
            "root": root,
            "addedTs": n.get("addedTs", int(time.time() * 1000)),
            "externalRoots": external,
        })
    return entries


def id_of_root(entries, root):
    if not root:
        return None
    key = norm(root)
    for e in entries:
        if norm(e["root"]) == key:
            return e["id"]
    return None


def migrate_tasks(home, entries):
    data_tasks = os.path.join(home, "data", "tasks")
    if not os.path.isdir(data_tasks):
        return
    moved = 0
    for name in os.listdir(data_tasks):
        task_dir = os.path.join(data_tasks, name)
        if not os.path.isdir(task_dir):
            continue
        meta = os.path.join(task_dir, "meta.json")
        workspace_root = None
        if os.path.isfile(meta):
            try:
                with open(meta, encoding="utf-8") as fh:
                    workspace_root = json.load(fh).get("workspace") or None
            except Exception as e:
                print("[migrate] meta 读取失败(兜底归默认工作区):", meta, e)
        wsid = id_of_root(entries, workspace_root)
        if wsid is None:
            wsid = DEFAULT_WORKSPACE_ID  # 无法归属:兜底默认工作区
        dest = os.path.join(home, "workspaces", wsid, "tasks", name)
        if os.path.exists(dest):
            continue  # 已迁移过(幂等)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        shutil.move(task_dir, dest)
        moved += 1
    if moved:
        print(f"[migrate] 迁移任务目录 {moved} 个")


def write_new_registry(home, entries):
    if not entries:
        return
    ws_dir = os.path.join(home, "workspaces")
    os.makedirs(ws_dir, exist_ok=True)
    f = os.path.join(ws_dir, REGISTRY_NAME)
    tmp = f + ".tmp"
    arr = []
    for e in entries:
        o = {"id": e["id"], "root": e["root"], "addedTs": e["addedTs"]}
        if e["externalRoots"]:
            o["externalRoots"] = e["externalRoots"]
        arr.append(o)
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(arr, fh, ensure_ascii=False)
    os.replace(tmp, f)  # 原子替换(Windows os.replace 亦原子)
    print(f"[migrate] 已写新注册表 workspaces/workspaces.json({len(entries)} 项)")


def move_dir_if_absent(src, dest, label):
    data_dir = os.path.dirname(src)
    if not os.path.exists(src):
        return
    if os.path.exists(dest):
        print(f"[migrate] {label} 目标已存在,跳过: {dest}")
        return
    os.makedirs(data_dir, exist_ok=True)
    shutil.move(src, dest)
    print(f"[migrate] 迁移 {label}: {src} -> {dest}")


def copy_git_key_per_workspace(home, entries):
    old_key = os.path.join(home, "data", "keys", OLD_GIT_KEY_NAME)
    if not os.path.isfile(old_key):
        return
    with open(old_key, "rb") as fh:
        key_bytes = fh.read()
    copied = 0
    for e in entries:
        ws_root = e["root"]
        enc = os.path.join(ws_root, ".everyagent", GIT_CREDENTIALS_NAME)
        key = os.path.join(ws_root, ".everyagent", GIT_KEY_NAME)
        if os.path.isfile(enc) and not os.path.isfile(key):
            os.makedirs(os.path.dirname(key), exist_ok=True)
            with open(key, "wb") as fh:
                fh.write(key_bytes)
            copied += 1
    if copied:
        print(f"[migrate] 旧全局 git 密钥复制到 {copied} 个工作区 .everyagent/")


def delete_recursively(p):
    if os.path.isdir(p) and not os.path.islink(p):
        for name in os.listdir(p):
            delete_recursively(os.path.join(p, name))
        os.rmdir(p)
    elif os.path.exists(p) or os.path.islink(p):
        os.remove(p)


def delete_quietly(p):
    if not (os.path.exists(p) or os.path.islink(p)):
        return
    try:
        delete_recursively(p)
    except OSError as e:
        print(f"[migrate] 删除失败(可手动清理): {p} - {e}")


def clean_legacy_data(home):
    data_dir = os.path.join(home, "data")
    delete_quietly(os.path.join(data_dir, "tasks"))
    delete_quietly(os.path.join(data_dir, "sandbox"))
    delete_quietly(os.path.join(data_dir, "keys"))
    delete_quietly(os.path.join(data_dir, REGISTRY_NAME))
    delete_quietly(os.path.join(data_dir, DEFAULT_OVERRIDE_NAME))
    try:
        if os.path.exists(data_dir):
            os.rmdir(data_dir)  # 只剩空目录时删除;非空抛 OSError 保留
        print("[migrate] 旧 data/ 已清理(若为空则删除目录本身)")
    except OSError:
        print("[migrate] data/ 非空未删除(请手动确认清理)")


def clean_legacy_wsl(home):
    wsl_dir = os.path.join(home, "wsl")
    if not os.path.exists(wsl_dir):
        return
    # WSL 托管发行版 rootfs 由 WSL 注册表绑定,不能搬目录;unregister 后删目录,重启 autoImport 重建。
    unregistered = False
    if os.name == "nt":
        try:
            r = subprocess.run(["wsl.exe", "--unregister", "eagent"],
                               capture_output=True, text=True, timeout=120)
            unregistered = r.returncode == 0
        except (OSError, subprocess.SubprocessError) as e:
            print("[migrate] wsl.exe 不可用,跳过 unregister:", e)
    if unregistered:
        print("[migrate] 已 wsl --unregister eagent(重启 worker 自动导入到 sandbox/distro)")
        delete_quietly(wsl_dir)
    else:
        print("[migrate] 旧 wsl/ 目录存在:请确认已 wsl --unregister eagent 后删除,"
              "或重启 worker 由 autoImport 重建到 sandbox/distro")


def run(home):
    print("[migrate] 系统目录:", home)

    # 1) 默认工作区改名 workspace → defaultworkspace。
    rename_default_workspace(home)

    # 2) 载入旧注册表并分配 id。
    entries = load_old_registry(home)
    old_tasks = os.path.join(home, "data", "tasks")

    # 3) 迁移任务目录(按 meta.workspace 映射到 workspaceId)。
    migrate_tasks(home, entries)

    # 4) 写新注册表(含 id;默认工作区纠正根并入 defaultworkspace 条目)。
    write_new_registry(home, entries)

    # 5) 沙箱持久状态 data/sandbox → sandbox。
    move_dir_if_absent(os.path.join(home, "data", "sandbox"),
                       os.path.join(home, "sandbox"), "沙箱持久状态")

    # 6) git 凭证密钥:旧全局密钥复制给已有密文的工作区。
    copy_git_key_per_workspace(home, entries)

    # 7) 清理旧 data 与 wsl。
    clean_legacy_data(home)
    clean_legacy_wsl(home)

    # 不设整体短路:任一步骤中断后重跑仍按顺序补齐(每步自身幂等),
    # 避免「新注册表已写、后续清理未完成」时被跳过。
    if not entries and not os.path.isdir(old_tasks):
        print("[migrate] 无旧注册表且无旧任务目录(可能已迁移),跳过")
    else:
        print("[migrate] 迁移完成。重启 worker 生效(托管发行版将由 autoImport 重建到 sandbox/distro)")


def main():
    if len(sys.argv) > 1 and sys.argv[1] in ("-h", "--help"):
        print(__doc__)
        return 0
    home = resolve_home(sys.argv[1:])
    run(home)
    return 0


if __name__ == "__main__":
    sys.exit(main())