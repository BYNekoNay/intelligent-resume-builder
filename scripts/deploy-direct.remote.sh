#!/usr/bin/env bash
# 智历 · 服务器侧部署执行器（非 Docker 直接部署）
#
# 由 scripts/Deploy-DirectUpload.ps1 上传到 /opt/intelligent-resume/ 后调用。
# 职责：构建 -> 同步产物 -> 重启服务 -> 健康检查。
# 约定：源码位于 $ROOT/src，运行产物位于 $ROOT/app，密钥位于 $ROOT/secrets.env（600）。
#
# 手动执行：
#   bash /opt/intelligent-resume/deploy-direct.remote.sh

set -euo pipefail

ROOT=/opt/intelligent-resume
SRC=$ROOT/src
APP=$ROOT/app
JAR_NAME=intelligent-resume-server-0.1.0-SNAPSHOT.jar

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export PATH="$JAVA_HOME/bin:$PATH"

log() { printf '\n=== %s ===\n' "$1"; }

if [ ! -d "$SRC/server" ]; then
  echo "源码目录缺失：$SRC/server。请先由本机上传源码包。" >&2
  exit 1
fi

log "1/7 后端构建（Maven，跳过测试）"
cd "$SRC"
mvn -f server/pom.xml -B -DskipTests clean package
test -f "$SRC/server/target/$JAR_NAME"

log "2/7 前端构建（npm ci + build）"
cd "$SRC/web"
npm ci --no-audit --no-fund
npm run build
test -f "$SRC/web/dist/index.html"

log "3/7 PDF 服务依赖（含 Linux Chromium 下载）"
cd "$SRC/pdf-service"
npm ci --no-audit --no-fund

log "4/7 同步产物到运行目录"
install -d "$APP/api" "$APP/api/pdf-output"

cp "$SRC/server/target/$JAR_NAME" "$APP/api/$JAR_NAME"

# 前端产物原子替换，保留上一版便于回滚
rm -rf "$APP/web.new"
cp -a "$SRC/web/dist" "$APP/web.new"
if [ -d "$APP/web" ]; then
  rm -rf "$APP/web.bak"
  mv "$APP/web" "$APP/web.bak"
fi
mv "$APP/web.new" "$APP/web"

# PDF 服务同步（保留服务器侧 .env，不回传、不被覆盖）
if [ -d "$APP/pdf-service" ]; then
  rm -rf "$APP/pdf-service.bak"
  cp -a "$APP/pdf-service" "$APP/pdf-service.bak"
fi
install -d "$APP/pdf-service"
tar -C "$SRC/pdf-service" --exclude='./.env' -cf - . | tar -C "$APP/pdf-service" -xf -

log "5/7 重启服务"
systemctl restart intelligent-resume-pdf
sleep 5
systemctl restart intelligent-resume-api

log "6/7 等待 API 就绪（最长 120s）"
ready=0
for i in $(seq 1 40); do
  if curl -fsS http://127.0.0.1:8080/actuator/health/readiness >/dev/null 2>&1; then
    echo "API readiness OK（第 ${i} 次探测）"
    ready=1
    break
  fi
  sleep 3
done
if [ "$ready" -ne 1 ]; then
  echo "API 未在 120s 内就绪，最后 40 行日志：" >&2
  journalctl -u intelligent-resume-api -n 40 --no-pager >&2 || true
  exit 1
fi

log "7/7 健康检查"
for unit in intelligent-resume-api intelligent-resume-pdf nginx mysql; do
  printf '%-28s %s\n' "$unit" "$(systemctl is-active "$unit" || true)"
done
printf '%-28s ' 'API readiness:'; curl -fsS http://127.0.0.1:8080/actuator/health/readiness; echo
printf '%-28s ' 'PDF health:';    curl -fsS http://127.0.0.1:3001/health; echo
printf '%-28s ' 'nginx -> API:';  curl -fsS http://127.0.0.1/api/system/health; echo

echo
echo "部署完成。"
