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

# Chromium 缓存目录**必须与运行期一致且与登录用户无关**。
# 坑：部署用户（ubuntu，HOME=/home/ubuntu）与服务运行用户（pdf 单元以 root 运行，HOME=/root）
# 不是同一个身份，若用 puppeteer 默认的 ~/.cache/puppeteer，构建期下好的浏览器在运行期找不到，
# 表现为「部署全绿、导出 PDF 才报 Could not find Chrome」。故固定在应用目录之外、部署不覆盖的位置。
PUPPETEER_CACHE_DIR="${PUPPETEER_CACHE_DIR:-/opt/intelligent-resume/chromium-cache}"
export PUPPETEER_CACHE_DIR

# 公网/本机入口端口。**不能假设是 80**：本机 80/443 属于同机另一个项目（Docker + Caddy），
# 若自检打 127.0.0.1:80 会打到对方的站点上，得到"看起来正常"的响应，形成假阳性。
PUBLIC_PORT="${PUBLIC_PORT:-8088}"

log() { printf '\n=== %s ===\n' "$1"; }

# 兼容两种部署用户：以 root 登录时直接执行，以普通用户（如 ubuntu）登录时经 sudo。
# 背景：本环境登录用户是 ubuntu，而 systemctl restart / journalctl 需要特权，
# 直接调用会得到 "Access denied as the requested operation requires interactive authentication"
# —— 且失败发生在第 5 步，前面构建全部成功，很容易误判成"部署已完成"。
if [ "$(id -u)" -eq 0 ]; then
  SUDO=""
else
  SUDO="sudo -n"
  if ! $SUDO true 2>/dev/null; then
    echo "错误：当前用户 $(id -un) 非 root 且无免密 sudo，无法重启服务。" >&2
    echo "      请用 root 登录部署，或为该用户配置免密 sudo。" >&2
    exit 1
  fi
fi

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

log "3/7 PDF 服务依赖（Chromium 由预置脚本提供 + 真实启动自检）"
cd "$SRC/pdf-service"
# 为什么先跳过 puppeteer 自带的浏览器下载（实测结论，非推测）：
#   puppeteer 25 的安装脚本只认 PUPPETEER_* 环境变量，源码里**不存在** PUPPETEER_DOWNLOAD_BASE_URL，
#   所以写 ~/.npmrc 或 export 该变量都是无效操作；其默认源 storage.googleapis.com 在国内直连
#   持续速度仅约 95KB/s，且要下 chrome(178MB) + chrome-headless-shell(114MB)，接近 1 小时。
#   改由 scripts/preseed-chromium.sh 问 puppeteer 自己要哪个版本，再从镜像并行分块拉取（实测约 3 分钟）。
PUPPETEER_SKIP_DOWNLOAD=true npm ci --no-audit --no-fund
bash scripts/preseed-chromium.sh

# 真实启动一次 Chromium：比路径比对更能证明「导出 PDF 真的可用」，失败即中止部署
node --input-type=module -e '
import puppeteer from "puppeteer";
const browser = await puppeteer.launch({ headless: true, args: ["--no-sandbox", "--disable-setuid-sandbox"] });
const version = await browser.version();
const page = await browser.newPage();
await page.setContent("<h1>chromium self check</h1>");
const pdf = await page.pdf({ format: "A4" });
await browser.close();
if (pdf.length < 1000) throw new Error("PDF 输出异常，字节数=" + pdf.length);
console.log("Chromium 自检通过:", version, "| PDF", pdf.length, "字节");
'

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

# 补齐运行期配置：让服务进程用与构建期同一个 Chromium 缓存目录（幂等，不覆盖既有配置）
if [ -f "$APP/pdf-service/.env" ]; then
  grep -q '^PUPPETEER_CACHE_DIR=' "$APP/pdf-service/.env" || \
    echo "PUPPETEER_CACHE_DIR=$PUPPETEER_CACHE_DIR" >> "$APP/pdf-service/.env"
else
  echo "警告：$APP/pdf-service/.env 不存在，PDF 服务将无法启动（需先手工创建）" >&2
fi

log "5/7 重启服务"
$SUDO systemctl restart intelligent-resume-pdf
sleep 5
$SUDO systemctl restart intelligent-resume-api

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
  $SUDO journalctl -u intelligent-resume-api -n 40 --no-pager >&2 || true
  exit 1
fi

log "7/7 健康检查"
for unit in intelligent-resume-api intelligent-resume-pdf nginx mysql; do
  printf '%-28s %s\n' "$unit" "$($SUDO systemctl is-active "$unit" || true)"
done
printf '%-28s ' 'API readiness:'; curl -fsS http://127.0.0.1:8080/actuator/health/readiness; echo
printf '%-28s ' 'PDF health:';    curl -fsS http://127.0.0.1:3001/health; echo
printf '%-28s ' "nginx:${PUBLIC_PORT} -> API:"; curl -fsS "http://127.0.0.1:${PUBLIC_PORT}/api/system/health"; echo

echo
echo "部署完成。"
echo
echo "服务器侧跑测试（可选）。注意测试资产随包落在源码树，故必须从 \$SRC 而非 app/ 运行："
echo "  cd $SRC/pdf-service && PUPPETEER_SKIP_DOWNLOAD=true npm test"
echo "  （web E2E 另需 Playwright 浏览器依赖，本机未预装；"
echo "    后端 mvn test 在 3.6G 内存的宿主机上有 OOM 风险，建议仍在本机执行）"
