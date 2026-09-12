#!/usr/bin/env bash
# 磁盘布局迁移命令(手动执行一次,幂等可重试):旧 data/ 布局 → 新 workspaces/ 布局。
# 用法:./scripts/migrate-workspaces.sh [--home <EVERYAGENT_HOME>]
#   不传 --home 时默认 $EVERYAGENT_HOME 或 ~/.everyagent。
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

echo "[migrate] 仓库根: ${REPO_ROOT}"
cd "${REPO_ROOT}"

# 先安装 contract/worker 到本地仓库(供 exec:java 解析 classpath),跳过测试与打包。
mvn -q -pl every-agent-worker -am install -DskipTests -Dmaven.test.skip=true

# 执行迁移工具(仅 worker 模块,避免 -am 让父模块也跑 exec)。
mvn -q -pl every-agent-worker exec:java \
  -Dexec.mainClass=dev.everyagent.worker.migrate.LegacyLayoutMigrator \
  -Dexec.args="$*"

echo "[migrate] 完成。"
