#!/usr/bin/env bash
# 智历 · 数据库备份（服务器侧；由 systemd timer 每日触发，也可手工执行）
#
# 为什么要有它（批次 2 兑现）：`docs`/`DEPLOYMENT_DIRECT.md` 一直承诺"每日备份 + 保留 14 天"，
# 但 `scripts/` 与 `deploy/` 下**没有任何备份脚本**，也没有 timer —— 承诺从未落地。
# 服务商快照不等于可恢复的数据库备份：这台机器还与另一个项目共用，误删/误升级都可能发生。
#
# 用法：
#   backup-database.sh           # 备份一次（正常由 timer 触发）
#   backup-database.sh --check   # 只做前置自检（连库、表数、磁盘、工具），不落文件
#
# 退出码：0 = 成功；1 = 失败（systemd 会记 failed，可由 `systemctl --failed` 发现）
#
# 安全边界：
#   · 凭据只从 app/api/.env 读，落进 600 的临时 defaults 文件，**不出现在 ps 的命令行里**；
#   · 只删除本目录下 `${PREFIX}-*.sql.gz` 且超过保留期的文件，绝不做递归删除；
#   · 校验不过的备份会被删掉并返回非零 —— 宁可没有备份，也不要留一个坏备份当"成功"。

set -euo pipefail

# 备份里是完整业务数据（含简历 PII）。默认 umask 022 会让文件变 644、目录变 755 ——
# 同机其他项目的进程/用户就能读到整库内容。这里统一收到 077，落盘即 600。
umask 077

ROOT="${ROOT:-/opt/intelligent-resume}"
ENV_FILE="${ENV_FILE:-$ROOT/app/api/.env}"
BACKUP_DIR="${BACKUP_DIR:-/var/backups/intelligent-resume}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"
PREFIX="intelligent_resume"
CHECK_ONLY=0
[ "${1:-}" = "--check" ] && CHECK_ONLY=1

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

# ---------- 1. 读配置 ----------
[ -r "$ENV_FILE" ] || die "读不到 $ENV_FILE（运行期唯一生效的配置来源）"

read_env() {
  awk -F= -v k="$1" '$1==k { sub(/^[^=]*=/, ""); print; exit }' "$ENV_FILE" \
    | tr -d '\r' | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//"
}

DB_URL="$(read_env SPRING_DATASOURCE_URL)"
DB_USER="$(read_env SPRING_DATASOURCE_USERNAME)"
DB_PASS="$(read_env SPRING_DATASOURCE_PASSWORD)"

[ -n "$DB_URL" ] || die "app/api/.env 缺少 SPRING_DATASOURCE_URL"
[ -n "$DB_USER" ] || die "app/api/.env 缺少 SPRING_DATASOURCE_USERNAME"
[ -n "$DB_PASS" ] || die "app/api/.env 缺少 SPRING_DATASOURCE_PASSWORD"

# jdbc:mysql://host:port/db?params —— 这里刻意不引入 sed 的贪婪匹配，
# 逐段剥离；去掉端口时 port 会等于 host，需要单独判断。
REST="${DB_URL#jdbc:mysql://}"
HOSTPORT="${REST%%/*}"
DBPART="${REST#*/}"
DB_NAME="${DBPART%%\?*}"
DB_HOST="${HOSTPORT%%:*}"
if [ "$HOSTPORT" = "$DB_HOST" ]; then
  DB_PORT=3306
else
  DB_PORT="${HOSTPORT##*:}"
fi
[ -n "$DB_NAME" ] || die "无法从 SPRING_DATASOURCE_URL 解析库名：$DB_URL"

# ---------- 2. 前置自检 ----------
command -v mysqldump >/dev/null 2>&1 || die "缺少 mysqldump"
command -v mysql >/dev/null 2>&1 || die "缺少 mysql 客户端"
command -v gzip >/dev/null 2>&1 || die "缺少 gzip"

DEFAULTS="$(mktemp)"
CLEANUP_TARGET=""
cleanup() {
  rm -f "$DEFAULTS"
  # 校验失败的半成品必须删掉，否则会被后续排查当成"有一份备份"
  [ -n "$CLEANUP_TARGET" ] && [ -f "$CLEANUP_TARGET" ] && rm -f "$CLEANUP_TARGET"
  return 0
}
trap cleanup EXIT
chmod 600 "$DEFAULTS"
# 用 printf 而不是 heredoc：密码里的 $ / ` 会被 shell 展开，heredoc 无法安全承载
{
  printf '[client]\n'
  printf 'user=%s\n' "$DB_USER"
  printf 'password=%s\n' "$DB_PASS"
  printf 'host=%s\n' "$DB_HOST"
  printf 'port=%s\n' "$DB_PORT"
} > "$DEFAULTS"

TABLE_COUNT="$(mysql --defaults-extra-file="$DEFAULTS" -N -B \
  -e "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='${DB_NAME}' AND table_type='BASE TABLE'" 2>/dev/null)" \
  || die "连不上数据库 ${DB_HOST}:${DB_PORT}/${DB_NAME}（凭据或网络问题）"
[ "${TABLE_COUNT:-0}" -ge 20 ] || die "库 ${DB_NAME} 只有 ${TABLE_COUNT:-0} 张表，低于预期（V1~V37 至少 20 张）—— 疑似连错库"

if [ "$CHECK_ONLY" -eq 1 ]; then
  log "自检通过：${DB_HOST}:${DB_PORT}/${DB_NAME}，${TABLE_COUNT} 张表，保留 ${RETENTION_DAYS} 天，目录 ${BACKUP_DIR}"
  exit 0
fi

# ---------- 3. 备份 ----------
mkdir -p "$BACKUP_DIR"
# 目录也必须收紧：mkdir 对**已存在**的目录不改权限，历史目录可能还是 755
#（755 虽读不到 600 的文件，但会暴露快照的文件名与生成时间）。每次跑都纠正一次。
chmod 700 "$BACKUP_DIR" 2>/dev/null || true
# 防手滑：备份目录必须是绝对路径、且不是 / 或 /var
case "$BACKUP_DIR" in
  /|/var|/usr|/etc) die "拒绝在 $BACKUP_DIR 下操作" ;;
esac
[ "${BACKUP_DIR#/}" != "$BACKUP_DIR" ] || die "BACKUP_DIR 必须是绝对路径：$BACKUP_DIR"

STAMP="$(date '+%Y%m%d-%H%M%S')"
TARGET="$BACKUP_DIR/${PREFIX}-${STAMP}.sql.gz"
CLEANUP_TARGET="$TARGET"

log "开始备份 ${DB_NAME} -> ${TARGET}"
mysqldump --defaults-extra-file="$DEFAULTS" \
  --single-transaction --quick --routines --triggers --no-tablespaces \
  --default-character-set=utf8mb4 \
  "$DB_NAME" | gzip -9 > "$TARGET"

# ---------- 4. 校验（不过就当失败，不留下坏文件）----------
chmod 600 "$TARGET" 2>/dev/null || true
gzip -t "$TARGET" || die "备份文件 gzip 校验失败"
SIZE="$(stat -c %s "$TARGET")"
[ "$SIZE" -gt 1024 ] || die "备份文件只有 ${SIZE} 字节，明显不完整"

DUMP_TABLES="$(gzip -dc "$TARGET" | grep -c '^CREATE TABLE' || true)"
if [ "$DUMP_TABLES" -ne "$TABLE_COUNT" ]; then
  die "备份内 CREATE TABLE 数量 ${DUMP_TABLES} 与库中 ${TABLE_COUNT} 不一致 —— dump 可能被截断"
fi

CLEANUP_TARGET=""
log "备份完成：${TARGET}（$(du -h "$TARGET" | cut -f1)，${DUMP_TABLES} 张表）"

# ---------- 5. 保留期清理 ----------
DELETED="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name "${PREFIX}-*.sql.gz" \
  -mtime "+${RETENTION_DAYS}" -print -delete | wc -l)"
log "保留期清理：删除 ${DELETED} 份超过 ${RETENTION_DAYS} 天的备份"

REMAIN="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name "${PREFIX}-*.sql.gz" | wc -l)"
log "当前共 ${REMAIN} 份备份"
