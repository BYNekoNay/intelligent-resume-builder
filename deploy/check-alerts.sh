#!/usr/bin/env bash
# 智历 · 告警轮询检查（alertcheck.timer 的执行体；直连部署的告警出口）
#
# 为什么不上 alertmanager：宿主机 3.6G 且同机另一项目已吃 935MiB、swap 已吃 979M，
# alertmanager 常驻 40~80MB + 独立投递通道配置，收益与代价不成比例（docs/DEPLOYMENT_DIRECT.md 内存账）。
# 轮询的代价是最多一个 timer 周期（5 分钟）的延迟——对测试环境足够。
#
# 行为：
#   查 127.0.0.1:9090/api/v1/alerts；
#   · 有 critical firing  → 退出码 2（systemd 记 failed，journald / systemctl --failed / 探针可见）
#   · 只有 warning firing → 退出码 0，日志留痕（warning 允许晾着，critical 不允许）
#   · prometheus 不可达   → 退出码 1（监控自身坏了也算异常，但与"有告警"区分开）
#
# 每次运行都会写一行状态到 STATE_FILE（时间戳 + 退出码 + 摘要），供 --watchdog 与部署探针判读。
# 状态文件写失败**不得**影响退出码：它是观测手段，不是业务结果。
#
# 可选外呼：设置 ALERT_WEBHOOK_URL 后，critical 时 POST 一份不含 PII 的 JSON 摘要。
# 未配置时**绝不发外网**——测试环境的纪律：出口必须显式配置才存在。
#
# 用法：
#   check-alerts.sh               # 正常轮询（timer 触发）
#   check-alerts.sh --watchdog    # 监控自死检测：距上次成功超过 WATCHDOG_MAX_AGE_SEC 则退 2
#
# 为什么需要 --watchdog：轮询脚本自己挂掉时，"没有告警"与"没人检查"在外部看来完全一样。
# systemd 只会把失败的那次记成 failed，而下一次成功又会把它洗白——于是故障窗口只有 5 分钟宽，
# 人不在场就等于不存在。watchdog 用一个独立 timer 检查"最近一次成功距今多久"，把这个洞补上。

set -uo pipefail

PROM_URL="${PROM_URL:-http://127.0.0.1:9090}"
WEBHOOK="${ALERT_WEBHOOK_URL:-}"
# 状态文件放 /var/lib 而不是 /opt/intelligent-resume/logs：后者属部署用户（ubuntu），
# 而本脚本以 prometheus 用户运行 —— 首版放 logs/ 时写入 Permission denied（2026-10-29 实测，
# 由 watchdog 首跑抓到）。目录由部署脚本创建并 chown 给 prometheus。
STATE_FILE="${ALERTCHECK_STATE_FILE:-/var/lib/intelligent-resume/alertcheck/state}"
# 3 个 timer 周期（每 5 分钟一次）：允许偶发失败，不允许「连续三次都没跑成」
WATCHDOG_MAX_AGE_SEC="${WATCHDOG_MAX_AGE_SEC:-900}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

# 写状态：格式 `<epoch> <exit-code> <summary>`，最后一行有效。
# 失败时只打日志、绝不改变退出码（状态文件是观测手段）。
write_state() {
  local code="$1" summary="$2"
  mkdir -p "$(dirname "$STATE_FILE")" 2>/dev/null || true
  printf '%s %s %s\n' "$(date +%s)" "$code" "$summary" >> "$STATE_FILE" 2>/dev/null \
    || log "警告：状态文件写入失败（$STATE_FILE）——不影响本次退出码"
}

# ---------- --watchdog：检查「监控自身是否还在工作」 ----------
if [ "${1:-}" = "--watchdog" ]; then
  if [ ! -r "$STATE_FILE" ]; then
    log "CRITICAL: 状态文件不存在（$STATE_FILE）—— alertcheck 从未成功运行过，或状态目录不可写"
    exit 2
  fi
  last="$(tail -1 "$STATE_FILE" 2>/dev/null)"
  last_epoch="$(printf '%s' "$last" | awk '{print $1}')"
  last_code="$(printf '%s' "$last" | awk '{print $2}')"
  case "$last_epoch" in
    ''|*[!0-9]*)
      log "CRITICAL: 状态文件无法解析（末行：$last）"
      exit 2
      ;;
  esac

  # 以「最近一次**成功**」为基准，而不是「最近一次运行」：
  # prometheus 持续不可达时每次都写状态，用最近一次运行会把「一直在失败」误判为健康。
  last_ok_epoch="$(awk '$2 == 0 { ts = $1 } END { if (ts != "") print ts }' "$STATE_FILE" 2>/dev/null)"
  if [ -z "$last_ok_epoch" ]; then
    log "CRITICAL: 状态文件里没有一次成功记录（末次退出码 ${last_code}）—— 告警出口从未真正工作过"
    exit 2
  fi

  now="$(date +%s)"
  age=$(( now - last_ok_epoch ))
  if [ "$age" -gt "$WATCHDOG_MAX_AGE_SEC" ]; then
    log "CRITICAL: 告警出口已 ${age}s 未成功（阈值 ${WATCHDOG_MAX_AGE_SEC}s，末次退出码 ${last_code}）" \
        "—— 这段时间内的监控结果不可信"
    exit 2
  fi
  log "告警出口正常（最近一次成功 ${age}s 前，阈值 ${WATCHDOG_MAX_AGE_SEC}s）"
  exit 0
fi

# 本机有全局代理时，探回环也必须绕过（与探针同一条纪律）
alerts_json="$(curl -s --noproxy '*' -m 20 "$PROM_URL/api/v1/alerts" 2>/dev/null)"
if [ -z "$alerts_json" ]; then
  log "ERROR: 查询 prometheus 失败（$PROM_URL 不可达或响应为空）——监控自身异常"
  write_state 1 "prometheus-unreachable"
  exit 1
fi

# 用 python3 解析（服务器无 jq；python3 在 Ubuntu 上必有）。
# 只认 state=firing 的告警；pending 不算（for 窗口内，抖动）。
SUMMARY="$(python3 - "$alerts_json" <<'PYEOF'
import json, sys

try:
    data = json.loads(sys.argv[1])
except Exception as exc:
    print(f"PARSE_ERROR {exc}")
    sys.exit(0)

alerts = data.get("data", {}).get("alerts", [])
critical, warning = [], []
for a in alerts:
    if a.get("state") != "firing":
        continue
    labels = a.get("labels", {})
    name = labels.get("alertname", "?")
    severity = labels.get("severity", "?")
    if severity == "critical":
        critical.append(name)
    elif severity == "warning":
        warning.append(name)

print(f"{len(critical)}|{len(warning)}|{','.join(sorted(critical))}|{','.join(sorted(warning))}")
PYEOF
)"

case "$SUMMARY" in
  PARSE_ERROR*)
    log "ERROR: prometheus 响应解析失败：${SUMMARY#PARSE_ERROR }"
    write_state 1 "prometheus-parse-error"
    exit 1
    ;;
esac

# python 输出顺序：critical数 | warning数 | critical名单 | warning名单
IFS='|' read -r NC NCW CRIT WARN <<< "$SUMMARY"

if [ "$NC" -gt 0 ]; then
  log "CRITICAL firing x${NC}：${CRIT}（另有 warning x${NCW}：${WARN}）"
  if [ -n "$WEBHOOK" ]; then
    # 外呼失败不影响退出码：外呼只是增强通道，journald 才是本出口的事实记录。
    # payload 只含告警名与计数：不含指标标签里的任何业务值，也不含 traceId/URL/用户名。
    curl -s --noproxy '*' -m 10 -H 'Content-Type: application/json' \
      -d "{\"text\": \"[智历] critical 告警 x${NC}: ${CRIT}\"}" \
      "$WEBHOOK" >/dev/null 2>&1 \
      || log "警告：webhook 投递失败（不影响本脚本退出码）"
  fi
  write_state 2 "critical:${CRIT}"
  exit 2
fi

if [ "$NCW" -gt 0 ]; then
  log "warning firing x${NCW}：${WARN}（不升级，仅留痕）"
  write_state 0 "warning-only:${WARN}"
else
  log "无 firing 告警"
  write_state 0 "clean"
fi
exit 0
