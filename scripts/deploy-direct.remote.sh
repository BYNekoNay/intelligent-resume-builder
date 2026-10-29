#!/usr/bin/env bash
# 智历 · 服务器侧部署执行器（非 Docker 直接部署）
#
# 由 scripts/deploy-direct.sh 打包上传到 /opt/intelligent-resume/ 后调用。
# 职责：构建 -> 同步产物 -> 重启服务 -> 健康检查。
# 约定：源码位于 $ROOT/src，运行产物位于 $ROOT/app，密钥位于 $ROOT/secrets.env（600）。
#
# 手动执行：
#   bash /opt/intelligent-resume/deploy-direct.remote.sh

set -euo pipefail

ROOT=/opt/intelligent-resume
SRC=$ROOT/src
APP=$ROOT/app
JAR_NAME=intelligent-resume-server-0.5.0.jar

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

log "1/8 后端构建（Maven，跳过测试）"
cd "$SRC"
mvn -f server/pom.xml -B -DskipTests clean package
test -f "$SRC/server/target/$JAR_NAME"

log "2/8 前端构建（npm ci + build）"
cd "$SRC/web"
npm ci --no-audit --no-fund
npm run build
test -f "$SRC/web/dist/index.html"

log "3/8 PDF 服务依赖（Chromium 由预置脚本提供 + 真实启动自检）"
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

log "4/8 同步产物到运行目录"
install -d "$APP/api" "$APP/api/pdf-output"

# 覆盖前保留上一版 jar 供快速回滚（与 web.bak / pdf-service.bak 同款）。
# 此前 jar 是全链路唯一没有 .bak 的产物：cp 直接覆盖，部署后发现问题只能完整重跑。
if [ -f "$APP/api/$JAR_NAME" ]; then
  cp -a "$APP/api/$JAR_NAME" "$APP/api/$JAR_NAME.bak"
  echo "已保留上一版 jar：$APP/api/$JAR_NAME.bak"
fi
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

# nginx 站点配置同步（幂等）。
# 此前这一步**只存在于部署手册的手工流程**里，仓库中的 nginx 配置改动不会随部署生效。
# 实测代价：第三十五批给 host.conf 加的静态资源 gzip 从未在真实环境生效 —— 服务器仍是
# nginx 自带的 `gzip on;`（只压 text/html），`curl -I -H 'Accept-Encoding: gzip' /assets/*.js`
# 看不到 Content-Encoding，第三十五批声称的 3.5× 压缩收益实际为 0。见第六十一批报告。
# 约定：仓库 deploy/nginx/ 是唯一来源。先落位安全头片段（host.conf 的 include 依赖它，
# 缺了会让 nginx -t 失败），再覆盖站点文件；最后**先校验后 reload** —— 校验不过则中止，
# 正在运行的 nginx 不受影响（配置坏了也不会把站点搞崩）。
if [ -d "$SRC/deploy/nginx" ]; then
  $SUDO install -d /etc/nginx/snippets
  $SUDO cp "$SRC/deploy/nginx/security-headers.conf" /etc/nginx/snippets/security-headers.conf
  $SUDO cp "$SRC/deploy/nginx/host.conf" /etc/nginx/sites-available/intelligent-resume
  $SUDO ln -sfn /etc/nginx/sites-available/intelligent-resume /etc/nginx/sites-enabled/intelligent-resume
  if $SUDO nginx -t >/dev/null 2>&1; then
    $SUDO systemctl reload nginx
    echo "nginx 站点配置已同步（含安全头片段）并 reload"
  else
    echo "错误：nginx -t 校验失败 —— 站点文件已覆盖但**未** reload，正在运行的 nginx 不受影响；请人工检查" >&2
    $SUDO nginx -t || true
    exit 1
  fi
else
  echo "警告：源码包缺少 deploy/nginx/，跳过 nginx 配置同步（旧配置继续生效）" >&2
fi

# systemd 单元同步（幂等）。
# 与 nginx 同类问题、同一处链路洞：此前该步**只存在于部署手册的手工流程**里，
# 仓库 `deploy/systemd/*.service` 的改动不会随部署生效。实测代价：第四十批给 pdf 单元
# 加的 `Environment=PDF_SERVICE_HOST=127.0.0.1` **从未在真实环境落地** —— 服务器单元停留在
# 2026-09-25 的版本，PDF 服务至今监听 `*:3001`（该暴露面只剩云安全组兜底）。见第六十五批报告。
# 约定：仓库 `deploy/systemd/` 是唯一来源；**仅在内容变化时**安装 + `daemon-reload`，
# 避免每次部署都重启造成抖动。安装前剥离 CR（防工作区 CRLF 落进单元导致 systemd 解析异常）。
if [ -d "$SRC/deploy/systemd" ]; then
  UNITS_CHANGED=0
  for unit_src in "$SRC"/deploy/systemd/*.service "$SRC"/deploy/systemd/*.timer; do
    [ -f "$unit_src" ] || continue
    unit_name="$(basename "$unit_src")"
    unit_tmp="/tmp/$unit_name.$$"
    sed 's/\r$//' "$unit_src" > "$unit_tmp"
    if [ -f "/etc/systemd/system/$unit_name" ] && cmp -s "$unit_tmp" "/etc/systemd/system/$unit_name"; then
      rm -f "$unit_tmp"
      continue
    fi
    $SUDO install -m 644 "$unit_tmp" "/etc/systemd/system/$unit_name"
    rm -f "$unit_tmp"
    UNITS_CHANGED=1
    echo "systemd 单元已更新：$unit_name"
  done
  if [ "$UNITS_CHANGED" -eq 1 ]; then
    $SUDO systemctl daemon-reload
    echo "systemd daemon-reload 完成（单元有变化）"
  else
    echo "systemd 单元已是最新，无需变更"
  fi

  # ⚠ 只把 .timer 文件放进 /etc/systemd/system **不会让它生效**：必须 enable（否则重启后不跑）。
  # 这是本批修掉的第二个链路洞 —— 原同步逻辑只 glob *.service，`.timer` 连安装都不会发生，
  # 于是「每日备份」这类承诺永远停在文档里。enable 是幂等的，所以放在"仅变更时安装"之外。
  for timer_src in "$SRC"/deploy/systemd/*.timer; do
    [ -f "$timer_src" ] || continue
    timer_name="$(basename "$timer_src")"
    if $SUDO systemctl enable --now "$timer_name" >/dev/null 2>&1; then
      echo "systemd 定时器已启用：$timer_name"
    else
      echo "警告：$timer_name 启用失败（见 systemctl status $timer_name）" >&2
    fi
  done
else
  echo "警告：源码包缺少 deploy/systemd/，跳过单元同步（旧单元继续生效）" >&2
fi

# ---------- 运维资产：备份脚本与 prometheus 配置（仓库是唯一来源）----------
# 与 systemd 单元同源同问题：这些文件不随部署同步，改了也不会在真实环境生效。
# 约定：仅在内容变化时安装，避免无意义的抖动。
if [ -f "$SRC/deploy/backup-database.sh" ]; then
  $SUDO install -d -m 755 "$ROOT/bin" "$ROOT/monitoring"
  backup_tmp="/tmp/backup-database.sh.$$"
  sed 's/\r$//' "$SRC/deploy/backup-database.sh" > "$backup_tmp"
  if cmp -s "$backup_tmp" "$ROOT/bin/backup-database.sh" 2>/dev/null; then
    rm -f "$backup_tmp"
  else
    if [ -f "$ROOT/bin/backup-database.sh" ]; then
      $SUDO cp -a "$ROOT/bin/backup-database.sh" "$ROOT/bin/backup-database.sh.bak"
    fi
    $SUDO install -m 700 "$backup_tmp" "$ROOT/bin/backup-database.sh"
    rm -f "$backup_tmp"
    echo "备份脚本已更新：$ROOT/bin/backup-database.sh"
  fi
else
  echo "警告：源码包缺少 deploy/backup-database.sh，跳过备份脚本安装" >&2
fi

# 告警轮询检查脚本（alertcheck.timer 的执行体；不上 alertmanager 的替代出口）
if [ -f "$SRC/deploy/check-alerts.sh" ]; then
  $SUDO install -d -m 755 "$ROOT/bin"
  alerts_tmp="/tmp/check-alerts.sh.$$"
  sed 's/\r$//' "$SRC/deploy/check-alerts.sh" > "$alerts_tmp"
  if cmp -s "$alerts_tmp" "$ROOT/bin/check-alerts.sh" 2>/dev/null; then
    rm -f "$alerts_tmp"
  else
    $SUDO install -m 755 "$alerts_tmp" "$ROOT/bin/check-alerts.sh"
    rm -f "$alerts_tmp"
    echo "告警检查脚本已更新：$ROOT/bin/check-alerts.sh"
  fi
  # 状态目录：脚本以 prometheus 用户运行，而 $ROOT/logs 属部署用户（首版即因此 Permission denied）。
  # prometheus 用户可能尚未创建（apt 安装在第 5/8 步），属主条件性设置。
  $SUDO install -d -m 750 /var/lib/intelligent-resume/alertcheck
  if $SUDO id prometheus >/dev/null 2>&1; then
    $SUDO chown -R prometheus:prometheus /var/lib/intelligent-resume/alertcheck
  fi
else
  echo "警告：源码包缺少 deploy/check-alerts.sh，跳过告警检查脚本安装" >&2
fi

if [ -f "$SRC/deploy/prometheus/prometheus.yml" ]; then
  prom_tmp="/tmp/prometheus.yml.$$"
  sed 's/\r$//' "$SRC/deploy/prometheus/prometheus.yml" > "$prom_tmp"
  if cmp -s "$prom_tmp" "$ROOT/monitoring/prometheus.yml" 2>/dev/null; then
    rm -f "$prom_tmp"
  else
    if [ -f "$ROOT/monitoring/prometheus.yml" ]; then
      $SUDO cp -a "$ROOT/monitoring/prometheus.yml" "$ROOT/monitoring/prometheus.yml.bak"
    fi
    $SUDO install -m 644 "$prom_tmp" "$ROOT/monitoring/prometheus.yml"
    rm -f "$prom_tmp"
    echo "prometheus 配置已更新：$ROOT/monitoring/prometheus.yml"
  fi
else
  echo "警告：源码包缺少 deploy/prometheus/prometheus.yml，跳过" >&2
fi

# 告警规则目录同步（幂等）。
# 为什么必须随部署同步：规则写在仓库 monitoring/prometheus/rules/（唯一源，
# AlertRuleContractTest 钉住），此前 deploy/prometheus/prometheus.yml 没有 rule_files，
# 这 15 条规则在直连路线**从未被评估**。目标路径与仓库布局同构
# （$ROOT/monitoring/prometheus/rules/），探针与门禁可机械映射。
# 有变更才重启 prometheus；无变更时不抖动。
if [ -d "$SRC/monitoring/prometheus/rules" ]; then
  # prometheus 用户可能尚未创建（apt 安装在第 5/8 步）：属主只能条件性设置，
  # 否则首次部署在这里直接失败。文件 644 + 目录 755 已足够 prometheus 读取。
  $SUDO install -d -m 755 "$ROOT/monitoring/prometheus/rules"
  if $SUDO id prometheus >/dev/null 2>&1; then
    $SUDO chown prometheus:prometheus "$ROOT/monitoring/prometheus/rules"
  fi
  RULES_CHANGED=0
  for rule_src in "$SRC"/monitoring/prometheus/rules/*.yml; do
    [ -f "$rule_src" ] || continue
    rule_name="$(basename "$rule_src")"
    rule_tmp="/tmp/$rule_name.$$"
    sed 's/\r$//' "$rule_src" > "$rule_tmp"
    if [ -f "$ROOT/monitoring/prometheus/rules/$rule_name" ] && cmp -s "$rule_tmp" "$ROOT/monitoring/prometheus/rules/$rule_name"; then
      rm -f "$rule_tmp"
      continue
    fi
    $SUDO install -m 644 "$rule_tmp" "$ROOT/monitoring/prometheus/rules/$rule_name"
    rm -f "$rule_tmp"
    if $SUDO id prometheus >/dev/null 2>&1; then
      $SUDO chown prometheus:prometheus "$ROOT/monitoring/prometheus/rules/$rule_name"
    fi
    RULES_CHANGED=1
    echo "告警规则已更新：$rule_name"
  done
  # 配置或规则有任一变化时，prometheus 必须重启才能加载（第 5/8 步会统一重启，这里只留痕）
  if [ "$RULES_CHANGED" -eq 1 ]; then
    echo "告警规则目录有变更，待第 5/8 步重启 prometheus 后生效"
  fi
else
  echo "警告：源码包缺少 monitoring/prometheus/rules/，跳过告警规则同步（旧规则继续生效）" >&2
fi


log "5/8 监控与备份运行时（prometheus 安装 + 定时器启用）"
# 容量决定：**只上 prometheus 单进程**（不上 Grafana / alertmanager）。
# 宿主机 3.6G 且与另一个项目共用（swap 已吃 979M），全栈常驻 300~500M 风险太大。
if ! command -v prometheus >/dev/null 2>&1; then
  echo "未安装 prometheus，尝试通过发行版仓库安装…"
  DEBIAN_FRONTEND=noninteractive $SUDO apt-get update -qq >/dev/null 2>&1 || true
  DEBIAN_FRONTEND=noninteractive $SUDO apt-get install -y -qq prometheus >/dev/null 2>&1 || true
fi
if command -v prometheus >/dev/null 2>&1; then
  echo "prometheus: $(prometheus --version 2>&1 | head -1)"
  # 数据目录必须在**用户已存在**之后建（apt 包会创建 prometheus 用户）。
  # 属主不对时单元启动即 Permission denied，而"服务没起来"只有探针能发现。
  $SUDO install -d -m 750 -o prometheus -g prometheus /var/lib/intelligent-resume/prometheus
  # 发行版自带的 prometheus.service 会用自己的配置抢 127.0.0.1:9090，先停掉；
  # 否则我们的单元会和它抢端口，表现为"服务 active 但抓的不是我们的配置"。
  $SUDO systemctl disable --now prometheus.service >/dev/null 2>&1 || true
  if $SUDO systemctl is-active --quiet intelligent-resume-prometheus; then
    $SUDO systemctl restart intelligent-resume-prometheus
  else
    $SUDO systemctl enable --now intelligent-resume-prometheus
  fi
  echo "prometheus 单元已启动（127.0.0.1:9090，保留 7 天）"
else
  echo "警告：prometheus 安装失败，监控未生效（探针会把这一项判失败）" >&2
fi

$SUDO systemctl enable --now intelligent-resume-backup.timer >/dev/null 2>&1 \
  && echo "备份定时器已启用（每日 03:30，保留 14 天）" \
  || echo "警告：备份定时器启用失败" >&2

log "6/8 重启服务"
$SUDO systemctl restart intelligent-resume-pdf
sleep 5
$SUDO systemctl restart intelligent-resume-api

log "7/8 等待 API 就绪（最长 120s）"
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

log "8/8 健康检查"
for unit in intelligent-resume-api intelligent-resume-pdf intelligent-resume-prometheus nginx mysql; do
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
