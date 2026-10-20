#!/usr/bin/env bash
# Invoke-PersistenceGate.sh —— 持久化门禁的本地入口（Windows Git Bash / macOS / Linux 通用）
#
# 流程：本机打包 server/ 源码 -> scp 上传 -> 在云服务器执行 persistence-gate.remote.sh -> 透传退出码。
# 构建与测试都发生在服务器（已有 JDK 17 + Maven 3.9 + .m2 缓存），本机只需要 tar / ssh / scp。
#
# 为什么是本机入口 + 远端执行器，而不是像 Invoke-MySql57MigrationGate.ps1 那样单文件：
#   ps1 假定「本机 3306 已有 5.7」，而生产口径是 8.4、存量口径需要 5.7 容器，
#   两者都只在云服务器上成立。本地脚本只做搬运，逻辑集中在远端执行器里，便于人工复核。
#
# 用法：
#   bash scripts/Invoke-PersistenceGate.sh                 # 8.4 + 5.7 都跑
#   GATE=mysql84 bash scripts/Invoke-PersistenceGate.sh     # 只跑 8.4（生产口径，约 2 分钟）
#   GATE=mysql57 bash scripts/Invoke-PersistenceGate.sh     # 只跑 5.7（首次含拉镜像 3-6 分钟）
#
# 退出码（与远端执行器一致）：
#   0 绿 / 1 红 / 3 未测量（镜像拉不到、容器起不来、内存不足等）/ 75 并发占用

set -euo pipefail

SERVER="${SERVER:-101.35.239.218}"
SSH_USER="${SSH_USER:-ubuntu}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
REMOTE_ROOT="${REMOTE_ROOT:-/opt/intelligent-resume}"
GATE="${GATE:-both}"
REMOTE="$SSH_USER@$SERVER"
GATE_DIR="$REMOTE_ROOT/persistence-gate"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSH_OPTS=(-i "$SSH_KEY" -o BatchMode=yes -o StrictHostKeyChecking=no -o ConnectTimeout=15)

step() { printf '\n=== %s ===\n' "$1"; }

[ -d "$REPO_ROOT/server" ] || { echo "源码目录缺失：$REPO_ROOT/server" >&2; exit 3; }
[ -f "$SSH_KEY" ] || { echo "SSH 私钥不存在：$SSH_KEY" >&2; exit 3; }

step "1/3 本机打包（只传 server/ 与远端执行器，排除构建产物）"
# Git Bash 下 TMPDIR 可能是 Windows 路径（含盘符或反斜杠），拼接后会得到畸形路径。
TMP_BASE="${TMPDIR:-/tmp}"
case "$TMP_BASE" in
  *\\*|*:*) TMP_BASE=/tmp ;;
esac
TARBALL="$TMP_BASE/persistence-gate.tar.gz"
rm -f "$TARBALL" 2>/dev/null || true

cd "$REPO_ROOT"
tar czf "$TARBALL" \
  --exclude='*/target' --exclude='*/node_modules' --exclude='*/dist' \
  --exclude='*/.env' --exclude='*.env.local' \
  server scripts/persistence-gate.remote.sh

# 打包内容自检：混入 target/ 会把几百 MB 传上去，混入 .env 会泄露配置
if tar tzf "$TARBALL" | grep -qE 'node_modules|/target/|\.env$'; then
  echo "打包内容异常（混入 target/node_modules/.env）：" >&2
  tar tzf "$TARBALL" | grep -E 'node_modules|/target/|\.env$' | head -10 >&2
  exit 3
fi
echo "源码包：$TARBALL（$(du -h "$TARBALL" | cut -f1)）"

step "2/3 上传并解包到 $GATE_DIR"
ssh "${SSH_OPTS[@]}" "$REMOTE" "mkdir -p '$GATE_DIR/logs'"
scp "${SSH_OPTS[@]}" "$TARBALL" "$REMOTE:$GATE_DIR/pkg.tgz" >/dev/null
# 解包不删除 server/target：保留它可让热跑走增量编译（约 30s 而不是 90s）
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "tar xzf '$GATE_DIR/pkg.tgz' -C '$GATE_DIR' && rm -f '$GATE_DIR/pkg.tgz'"

# CRLF 二次保险：本仓踩过 Windows 换行污染脚本的坑（.gitattributes 已声明 *.sh eol=lf）
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "sed -i 's/\r$//' '$GATE_DIR/scripts/persistence-gate.remote.sh' && chmod +x '$GATE_DIR/scripts/persistence-gate.remote.sh'"

step "3/3 在服务器执行门禁（GATE=$GATE）"
set +e
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "GATE='$GATE' SRC_DIR='$GATE_DIR/server' LOG_DIR='$GATE_DIR/logs' bash '$GATE_DIR/scripts/persistence-gate.remote.sh'"
rc=$?
set -e

case "$rc" in
  0)  echo; echo "门禁通过（退出码 0）" ;;
  1)  echo; echo "门禁失败（退出码 1）—— 见上方 GATE-RED 文案与服务器 $GATE_DIR/logs/" >&2 ;;
  3)  echo; echo "门禁未执行（退出码 3）—— 结论未知，这不是通过。见上方 GATE-ENV 处置建议。" >&2 ;;
  75) echo; echo "门禁未执行（退出码 75）—— 另一次门禁正在运行，稍后重试。" >&2 ;;
  *)  echo; echo "门禁异常退出（退出码 $rc）" >&2 ;;
esac
exit "$rc"
