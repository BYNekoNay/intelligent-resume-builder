#!/usr/bin/env bash
# 预置 puppeteer 所需的 Chromium，绕开其安装脚本在国内直连 Google 源的龟速下载。
#
# 为什么需要这个脚本（全部为实测结论，非推测）：
#   1) puppeteer 25 的安装脚本**只认 PUPPETEER_* 环境变量**，源码中不存在
#      `PUPPETEER_DOWNLOAD_BASE_URL` 这个键（grep node_modules/@puppeteer/browsers 得到：
#      PUPPETEER_CACHE_DIR / EXECUTABLE_PATH / REVISIONS / SKIP_DOWNLOAD / TMP_DIR / LOGLEVEL 等）。
#      因此写 `~/.npmrc` 的 puppeteer_download_base_url 或 export PUPPETEER_DOWNLOAD_BASE_URL
#      **都是无效操作** —— 已两次实测确认连接目标仍是 Google（142.250.x.x）。
#   2) 国内直连其默认源（storage.googleapis.com）持续速度约 95KB/s，且要下两个包
#      （chrome 178MB + chrome-headless-shell 114MB），合计约 292MB，接近 1 小时。
#   3) 缓存目录布局是 `<cache>/<browser>/<platform>-<version>/<archive>/<binary>`，
#      注意 buildId 目录带 **平台前缀**（如 `linux-150.0.7871.24`）。按旧约定放成
#      `chrome/150.0.7871.24/` 会被 puppeteer 无视，表现为 "Could not find Chrome"。
#
# 做法：先 `PUPPETEER_SKIP_DOWNLOAD=true npm ci`，再执行本脚本：
#   问 puppeteer 自己期望的可执行文件路径 → 从镜像并行分块下载该版本 → 解压到该路径。
#   问路径而不是硬编码，使脚本在 puppeteer 升级后自动适配，不会静默失效。
#
# 幂等：目标文件已存在即跳过。可重复执行。
# 用法：bash scripts/preseed-chromium.sh          （在 pdf-service 目录下执行）
#       bash scripts/preseed-chromium.sh <pdf-service 目录>
# 可用环境变量：CHROMIUM_DOWNLOAD_BASE_URL（默认 npmmirror）、CHROMIUM_CHUNKS（默认 8）

set -euo pipefail

TARGET_DIR="${1:-$(pwd)}"
cd "$TARGET_DIR"

BASE="${CHROMIUM_DOWNLOAD_BASE_URL:-https://cdn.npmmirror.com/binaries/chrome-for-testing}"
CHUNKS="${CHROMIUM_CHUNKS:-8}"
CACHE="${PUPPETEER_CACHE_DIR:-$HOME/.cache/puppeteer}"
MIR="$(mktemp -d)"

cleanup() { rm -rf "$MIR"; }
trap cleanup EXIT

if [ ! -d node_modules/puppeteer ]; then
  echo "错误：未找到 node_modules/puppeteer。请先执行 PUPPETEER_SKIP_DOWNLOAD=true npm ci" >&2
  exit 1
fi
command -v unzip >/dev/null 2>&1 || { echo "错误：缺少 unzip，请先安装" >&2; exit 1; }

# 问 puppeteer 自己期望的可执行文件在哪 —— 唯一权威来源，避免硬编码版本与目录布局
EXPECTED="$(PUPPETEER_SKIP_DOWNLOAD=true node --input-type=module -e '
import puppeteer from "puppeteer";
process.stdout.write(await puppeteer.executablePath());
')"

if [ -z "$EXPECTED" ]; then
  echo "错误：无法解析 puppeteer 期望的可执行文件路径" >&2
  exit 1
fi

if [ -x "$EXPECTED" ]; then
  echo "Chromium 已预置，跳过下载：$EXPECTED"
  exit 0
fi

# 从期望路径反推版本与压缩包名：
#   $CACHE/chrome/linux-150.0.7871.24/chrome-linux64/chrome
#   -> buildIdDir=linux-150.0.7871.24  archiveDir=chrome-linux64  version=150.0.7871.24
ARCHIVE_DIR="$(basename "$(dirname "$EXPECTED")")"
BUILD_DIR="$(dirname "$(dirname "$EXPECTED")")"
BUILD_ID="$(basename "$BUILD_DIR")"
VERSION="${BUILD_ID#linux-}"
PLATFORM="linux64"
URL="$BASE/$VERSION/$PLATFORM/$ARCHIVE_DIR.zip"

echo "期望版本 : $VERSION"
echo "目标路径 : $EXPECTED"
echo "下载源   : $URL"
echo

TOTAL="$(curl -sI --max-time 30 "$URL" | tr -d '\r' | awk 'tolower($1)=="content-length:"{print $2}' | tail -1)"
if [ -z "${TOTAL:-}" ]; then
  echo "错误：无法获取 Content-Length（源不可达或该版本不存在）：$URL" >&2
  exit 1
fi
echo "包大小   : $((TOTAL / 1024 / 1024))MB，分 $CHUNKS 片并行下载"

# 单连接持续速度实测仅约 255KB/s；分片并行后实测可达 1.5-1.8MB/s
CHUNK=$(( (TOTAL + CHUNKS - 1) / CHUNKS ))
ZIP="$MIR/$ARCHIVE_DIR.zip"
T0=$(date +%s)
for i in $(seq 0 $((CHUNKS - 1))); do
  START=$((i * CHUNK))
  END=$((START + CHUNK - 1))
  [ "$END" -ge "$TOTAL" ] && END=$((TOTAL - 1))
  [ "$START" -gt "$END" ] && continue
  curl -s --max-time 900 --retry 3 -r "$START-$END" -o "$ZIP.part.$i" "$URL" &
done
wait

for i in $(seq 0 $((CHUNKS - 1))); do
  [ -f "$ZIP.part.$i" ] && cat "$ZIP.part.$i"
done > "$ZIP"
rm -f "$ZIP".part.*

ACTUAL="$(stat -c %s "$ZIP" 2>/dev/null || echo 0)"
T1=$(date +%s)
if [ "$ACTUAL" != "$TOTAL" ]; then
  echo "错误：分片拼接后大小不符（期望 $TOTAL，实得 $ACTUAL）" >&2
  exit 1
fi
echo "下载完成 : $(du -h "$ZIP" | cut -f1)，用时 $((T1 - T0))s，平均 $((ACTUAL / (T1 - T0 + 1) / 1024))KB/s"

mkdir -p "$BUILD_DIR"
unzip -q -o "$ZIP" -d "$BUILD_DIR"

if [ ! -x "$EXPECTED" ]; then
  chmod +x "$EXPECTED" 2>/dev/null || true
fi
if [ ! -f "$EXPECTED" ]; then
  echo "错误：解压后仍未在期望路径找到可执行文件：$EXPECTED" >&2
  echo "实际解压内容：" >&2
  find "$BUILD_DIR" -maxdepth 2 >&2
  exit 1
fi

echo "预置完成 : $EXPECTED"
