#!/usr/bin/env bash
#
# release-notes.sh —— 提取「最新 tag → 指定 ref(默认 HEAD)」之间的 commit message,生成 release note 草稿
#
# 用法:
#   scripts/release-notes.sh                     # 最新 tag..HEAD,按提交前缀分组
#   scripts/release-notes.sh v0.2.0              # 指定起始 tag
#   scripts/release-notes.sh v0.2.0 origin/main  # 指定起始 tag 与终点 ref
#   scripts/release-notes.sh --list              # 平铺列表,不分组
#   scripts/release-notes.sh --merges            # 包含 merge commit(默认排除)
#   scripts/release-notes.sh --match 'v*'        # 仅把匹配 v* 的 tag 视作候选"最新 tag"
#
# 输出为 Markdown,重定向即可保存:scripts/release-notes.sh > RELEASE_NOTES.md
# 注:「最新 tag」取 git describe --tags --abbrev=0,即从 HEAD 回溯可直达的最近 tag。

set -euo pipefail

MODE="group"        # group=按提交前缀分组;list=平铺
INCLUDE_MERGES=0    # 默认排除 merge commit
TAG_MATCH=()        # --match glob(透传 git describe)
FROM_TAG=""         # 起始 tag;留空则自动取最新 tag
TO_REF="HEAD"       # 终点 ref

usage() {
  cat <<'EOF'
用法: release-notes.sh [选项] [起始tag] [终点ref]

提取「最新 tag → 终点 ref(默认 HEAD)」的 commit message,输出 Markdown release note 草稿。
分组模式会把 "前缀: 描述" 风格的提交按前缀归类(如 前端/worker/feat),无前缀或前缀过长的归入「其他」。

选项:
  -l, --list           平铺输出全部提交,不分组
  -m, --merges         包含 merge commit(默认排除)
      --match <glob>   仅在匹配 glob 的 tag(如 'v*')中取最新
  -h, --help           显示本帮助

示例:
  scripts/release-notes.sh                    # 最新 tag..HEAD,分组
  scripts/release-notes.sh --list v0.1.2      # v0.1.2..HEAD,平铺
  scripts/release-notes.sh --match 'v*' v0.2.0 main
EOF
}

die() { echo "错误: $*" >&2; exit 1; }

# ---------- 解析参数 ----------
while [[ $# -gt 0 ]]; do
  case "$1" in
    -l|--list)   MODE="list"; shift ;;
    -m|--merges) INCLUDE_MERGES=1; shift ;;
    --match)     [[ $# -ge 2 ]] || die "--match 需要一个 glob 参数"
                 TAG_MATCH=(--match "$2"); shift 2 ;;
    -h|--help)   usage; exit 0 ;;
    -*)          usage >&2; die "未知选项: $1" ;;
    *)           if [[ -z "$FROM_TAG" ]]; then FROM_TAG="$1"; else TO_REF="$1"; fi; shift ;;
  esac
done

# ---------- 前置检查 ----------
git rev-parse --is-inside-work-tree >/dev/null 2>&1 || die "当前目录不在 git 仓库内"
if [[ -n "$FROM_TAG" ]]; then
  git rev-parse -q --verify "${FROM_TAG}^{commit}" >/dev/null || die "起始 tag 不存在: $FROM_TAG"
fi
git rev-parse -q --verify "${TO_REF}^{commit}" >/dev/null || die "终点 ref 不存在: $TO_REF"

# ---------- 确定起始 tag ----------
if [[ -z "$FROM_TAG" ]]; then
  FROM_TAG=$(git describe --tags --abbrev=0 ${TAG_MATCH[@]+"${TAG_MATCH[@]}"} 2>/dev/null || true)
fi

if [[ -n "$FROM_TAG" ]]; then
  RANGE="${FROM_TAG}..${TO_REF}"
  FROM_HINT="${FROM_TAG}"
else
  RANGE="${TO_REF}"   # 仓库尚无任何 tag:退化为全部历史
  FROM_HINT="仓库起点"
fi

MERGE_ARGS=()
if [[ "$INCLUDE_MERGES" -eq 0 ]]; then MERGE_ARGS=(--no-merges); fi

COUNT=$(git rev-list --count "${MERGE_ARGS[@]+"${MERGE_ARGS[@]}"}" "$RANGE")
if [[ "$COUNT" -eq 0 ]]; then
  echo "没有新提交:${FROM_HINT} 与 ${TO_REF} 之间没有差异。" >&2
  exit 0
fi

mapfile -t LOG < <(git log --pretty=format:'%s%x09%h' "${MERGE_ARGS[@]+"${MERGE_ARGS[@]}"}" "$RANGE")

# ---------- 输出 ----------
TO_HASH=$(git rev-parse --short "$TO_REF")
echo "# Release Notes(${FROM_HINT} → ${TO_REF}@${TO_HASH})"
echo
echo "> 共 ${COUNT} 条提交,生成于 $(date '+%Y-%m-%d %H:%M:%S')。"

if [[ "$MODE" == "list" ]]; then
  echo
  for line in "${LOG[@]}"; do
    subject="${line%$'\t'*}"
    hash="${line##*$'\t'}"
    echo "- ${subject}(${hash})"
  done
  exit 0
fi

# 分组模式:按首个冒号(半角/全角)前的短前缀归类
declare -A GROUP_ITEMS=()
ORDER=()
for line in "${LOG[@]}"; do
  subject="${line%$'\t'*}"
  hash="${line##*$'\t'}"
  prefix=""
  rest="$subject"
  if [[ "$subject" == *[:：]* ]]; then
    candidate="${subject%%[:：]*}"
    if [[ -n "$candidate" && "${#candidate}" -le 12 && "$candidate" != *" "* ]]; then
      prefix="$candidate"
      rest="${subject#*[:：]}"
      rest="${rest# }"
    fi
  fi
  if [[ -z "$prefix" ]]; then prefix="其他"; fi
  if [[ -z "${GROUP_ITEMS[$prefix]+x}" ]]; then
    ORDER+=("$prefix")
    GROUP_ITEMS[$prefix]=""
  fi
  GROUP_ITEMS[$prefix]+="- ${rest}(${hash})"$'\n'
done

echo
for g in "${ORDER[@]}"; do
  echo "## ${g}"
  echo
  printf '%s' "${GROUP_ITEMS[$g]}"
  echo
done
