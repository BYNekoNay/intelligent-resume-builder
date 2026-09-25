#!/usr/bin/env bash
# 智历 · 本机编排部署（非 Docker 直接上传）
#
# 流程：本机打包源码 -> scp 上传 -> 调用服务器侧执行器完成构建/同步/重启/健康检查。
# 构建发生在服务器（Ubuntu + openjdk-17 + maven），本机只需要 tar / ssh / scp。
# 服务器侧逻辑见 scripts/deploy-direct.remote.sh，随包上传，便于人工复核。
#
# 运行环境：Git Bash / WSL / Linux / macOS
#
# 用法：
#   bash scripts/deploy-direct.sh
#   SERVER=1.2.3.4 SSH_USER=root SSH_KEY=~/.ssh/other bash scripts/deploy-direct.sh
#
# 默认值对应当前落地的测试环境（101.35.239.218）。注意该主机的登录用户是 ubuntu 而非 root，
# 且 80/443 被同机另一个项目（educational-administration，Docker + Caddy）占用，
# 因此智历的公网入口是 8088 端口。详见 docs/DEPLOYMENT_DIRECT.md。
#
# 服务器目录约定：
#   /opt/intelligent-resume/
#     src/              源码（构建用）
#     app/api/          jar + .env + pdf-output/
#     app/web/          nginx 静态根（保留 web.bak 供回滚）
#     app/pdf-service/  源码 + node_modules + .env
#     chromium-cache/   puppeteer 的 Chromium（与登录用户无关，见 remote.sh 注释）
#     secrets.env       JWT_SECRET / PDF_SERVICE_TOKEN / MYSQL_PASSWORD（600）
#     live-ai.env       BAILIAN_*（600，仅首次需要）

set -euo pipefail

SERVER="${SERVER:-101.35.239.218}"
SSH_USER="${SSH_USER:-ubuntu}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
REMOTE_ROOT="${REMOTE_ROOT:-/opt/intelligent-resume}"
PUBLIC_PORT="${PUBLIC_PORT:-8088}"
REMOTE="$SSH_USER@$SERVER"

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSH_OPTS=(-i "$SSH_KEY" -o BatchMode=yes -o StrictHostKeyChecking=no)

step() { printf '\n=== %s ===\n' "$1"; }

for d in server web pdf-service; do
  [ -d "$REPO_ROOT/$d" ] || { echo "源码目录缺失：$REPO_ROOT/$d" >&2; exit 1; }
done
[ -f "$SSH_KEY" ] || { echo "SSH 私钥不存在：$SSH_KEY" >&2; exit 1; }

step "1/5 本机打包源码（排除依赖与构建产物）"
# Git Bash 下 TMPDIR 可能是 Windows 路径（含盘符或反斜杠），与本 shell 的 cd 拼接会得到
# 畸形路径并触发安全删除拦截。出现这种情况时统一退回 /tmp。
TMP_BASE="${TMPDIR:-/tmp}"
case "$TMP_BASE" in
  *\\*|*:*) TMP_BASE=/tmp ;;
esac
TARBALL="$TMP_BASE/resume-src.tar.gz"
rm -f "$TARBALL" 2>/dev/null || true
cd "$REPO_ROOT"
tar czf "$TARBALL" \
  --exclude='*/node_modules' --exclude='*/target' --exclude='*/dist' \
  --exclude='*/.env' --exclude='*.env.local' --exclude='*.env.live-ai' \
  server web pdf-service
echo "源码包：$TARBALL（$(du -h "$TARBALL" | cut -f1)）"

# 安全检查：确认没有把依赖或环境文件打进去
if tar tzf "$TARBALL" | grep -qE 'node_modules|/target/|/dist/|\.env$'; then
  echo "源码包混入了不应上传的内容：" >&2
  tar tzf "$TARBALL" | grep -E 'node_modules|/target/|/dist/|\.env$' | head -20 >&2
  exit 1
fi
echo "源码包内容校验通过（无 node_modules / target / dist / .env）"

step "2/5 上传源码包与远端执行器"
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "mkdir -p $REMOTE_ROOT/src $REMOTE_ROOT/app $REMOTE_ROOT/logs $REMOTE_ROOT/backups"
scp "${SSH_OPTS[@]}" "$TARBALL" "$REMOTE:$REMOTE_ROOT/resume-src.tar.gz"
# 上传前剥离 CR：即使本机 git 把工作区文件转成了 CRLF，Linux 端 bash 也不会
# 因 `$'\r': command not found` 而执行失败（.gitattributes 已声明 *.sh eol=lf，此处是二次保险）
sed 's/\r$//' "$REPO_ROOT/scripts/deploy-direct.remote.sh" > "$TMP_BASE/deploy-direct.remote.sh"
scp "${SSH_OPTS[@]}" "$TMP_BASE/deploy-direct.remote.sh" "$REMOTE:$REMOTE_ROOT/deploy-direct.remote.sh"
echo "上传完成"

step "3/5 解压源码"
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "cd $REMOTE_ROOT/src && rm -rf server web pdf-service && tar xzf ../resume-src.tar.gz \
   && chmod +x $REMOTE_ROOT/deploy-direct.remote.sh && du -sh ."
echo "解压完成"

step "4/5 服务器侧构建与发布（首次约 3-5 分钟，含 Chromium 下载）"
ssh "${SSH_OPTS[@]}" "$REMOTE" "bash $REMOTE_ROOT/deploy-direct.remote.sh"

step "5/5 公网入口自检"
# ⚠ 必须带端口：本机 80/443 属于同机另一个项目（Docker + Caddy），
# 打 127.0.0.1:80 会打到对方站点并返回 200，形成"部署成功"的假阳性。
ssh "${SSH_OPTS[@]}" "$REMOTE" \
  "curl -s -o /dev/null -w 'nginx(127.0.0.1:$PUBLIC_PORT) HTTP -> %{http_code}\n' http://127.0.0.1:$PUBLIC_PORT/ ; \
   systemctl is-active nginx mysql intelligent-resume-api intelligent-resume-pdf"

printf '\n部署流程已结束。\n'
printf '浏览器访问：http://%s:%s/\n' "$SERVER" "$PUBLIC_PORT"
printf '查看日志：ssh %s "journalctl -u intelligent-resume-api -f"\n' "$REMOTE"
