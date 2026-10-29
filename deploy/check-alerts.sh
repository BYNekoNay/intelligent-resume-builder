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
# 可选外呼：设置 ALERT_WEBHOOK_URL 后，critical 时 POST 一份 JSON 摘要。
# 未配置时**绝不发外网**——测试环境的纪律：出口必须显式配置才存在。

set -uo pipefail

PROM_URL="${PROM_URL:-http://127.0.0.1:9090}"
WEBHOOK="${ALERT_WEBHOOK_URL:-}"

log() { printf '[%s] %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*"; }

# 本机有全局代理时，探回环也必须绕过（与探针同一条纪律）
alerts_json="$(curl -s --noproxy '*' -m 20 "$PROM_URL/api/v1/alerts" 2>/dev/null)"
if [ -z "$alerts_json" ]; then
  log "ERROR: 查询 prometheus 失败（$PROM_URL 不可达或响应为空）——监控自身异常"
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
    exit 1
    ;;
esac

IFS='|' read -r NC CRIT NW WARN <<< "$SUMMARY"

if [ "$NC" -gt 0 ]; then
  log "CRITICAL firing x${NC}：${CRIT}（另有 warning x${NW}：${WARN}）"
  if [ -n "$WEBHOOK" ]; then
    # 外呼失败不影响退出码：外呼只是增强通道，journald 才是本出口的事实记录
    curl -s --noproxy '*' -m 10 -H 'Content-Type: application/json' \
      -d "{\"text\": \"[智历] critical 告警 x${NC}: ${CRIT}\"}" \
      "$WEBHOOK" >/dev/null 2>&1 \
      || log "警告：webhook 投递失败（不影响本脚本退出码）"
  fi
  exit 2
fi

if [ "$NW" -gt 0 ]; then
  log "warning firing x${NW}：${WARN}（不升级，仅留痕）"
else
  log "无 firing 告警"
fi
exit 0
