#!/usr/bin/env bash
# 智历 · 部署后探针（post-deploy probe）
#
# 目的：验证「仓库里声明的配置」在**真实运行环境确实生效** —— 而不只是"服务活着"。
#
# 为什么需要它（第六十一批实测教训）：
#   CI 双绿 ≠ 真实环境生效。当时两条 nginx 配置改动（`client_max_body_size` 5m→6m、
#   静态资源 gzip）在仓库里早已改好、CI 全绿，却因为部署链路不含 `deploy/` 而**从未生效**：
#     ① 5.5MB 上传被 nginx 以 HTML 错误页拦掉（用户拿不到统一信封）；
#     ② 静态资源无 gzip，第三十五批声称的 3.5× 压缩收益实际为 0。
#   单测 / e2e / 静态契约门禁**全都看不见**这类问题，只能实测。本脚本把当时手工排查用的探针固化。
#
# 两类断言：
#   A. 行为断言   —— 从 HTTP 可观察的事实反推配置是否生效（gzip / 体积 / 安全头 / 收敛），
#                    以及**在服务器上**直接看监听地址（服务是否只绑回环）；
#   B. 配置一致性 —— 把**服务器生效的 nginx 配置**与仓库 `deploy/nginx/host.conf`、
#                    以及**生效的 systemd 单元/定时器**与仓库 `deploy/systemd/*.service|*.timer` 归一化后逐行对比，
#                    能通用地发现任何"配置改了但没部署"的漂移（不限于已知的几条）。
#
# 用法：
#   bash scripts/probe-deployment.sh                      # 默认打当前测试环境
#   bash scripts/probe-deployment.sh http://host:port     # 指定入口
#   SSH_HOST= bash scripts/probe-deployment.sh            # 跳过配置一致性对比（无 SSH 时）
#
# 退出码：0 = 全部通过；1 = 有失败项（部署已完成，但复核未通过，需人工确认后再算交付完成）

set -uo pipefail

BASE="${1:-http://101.35.239.218:8088}"
# SSH_HOST 刻意默认为空：作者本机的 ssh 别名（community-server）不能写死进通用脚本——
# 换机器跑探针时若别名不存在，第 5~11 节会静默 SKIP，24+ 项门禁实际只判 11 项。
# 由 scripts/deploy-direct.sh 显式传入；手工运行想比配置时用 SSH_HOST=user@host。
SSH_HOST="${SSH_HOST:-}"
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
SSH_OPTS=(-o ConnectTimeout=15 -o BatchMode=yes)
[ -n "${SSH_KEY:-}" ] && [ -f "$SSH_KEY" ] && SSH_OPTS+=(-i "$SSH_KEY" -o IdentitiesOnly=yes)
REMOTE_ROOT="${REMOTE_ROOT:-/opt/intelligent-resume}"
NGINX_SITE="${NGINX_SITE:-/etc/nginx/sites-available/intelligent-resume}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 期望项数（汇总时强校验：PASS+FAIL 不等于期望值 → 探针自身失效 → 判红并退出）。
# 治理"注释掉一行 pass/fail 导致总数悄悄缩水"的失效形态——一个不再完整运行的门禁
# 比没有门禁更危险，因为它给人"查过了"的错觉。
# 计数构成（SSH 模式，新增/删减检查项时必须同步改这两个数）：
#   固定节 0~4：11 项
#   第 5 节 nginx 配置：1 项
#   第 6 节 systemd 单元：deploy/systemd/ 下 5 个 .service + 2 个 .timer = 7 项
#   第 7 节 监听范围：3 项；第 8 节 模型链：1 项
#   第 9 节 备份：6 项（9.1 脚本自检 / 9.2 timer / 9.3 抓取 up / 9.4 最近一次成功 / 9.5 份数新鲜度+权限）
#   第 10 节 告警评估：2 项（规则加载 / critical firing=0）
#   第 11 节 alertcheck：2 项（timer enabled / 主动重跑一次检查）
#   合计 11+1+7+3+1+6+2+2 = 33 项
EXPECTED_ITEMS_SSH=33
# 无 SSH 模式只有固定节 0~4
EXPECTED_ITEMS_NOSSH=11

PASS=0
FAIL=0
pass() { printf '  [PASS] %s\n' "$1"; PASS=$((PASS + 1)); }
fail() { printf '  [FAIL] %s\n' "$1"; FAIL=$((FAIL + 1)); }
expect_eq() { if [ "$2" = "$3" ]; then pass "$1（$2）"; else fail "$1：期望 $3，实际 $2"; fi; }
expect_has() { if printf '%s' "$2" | grep -qi -- "$3"; then pass "$1"; else fail "$1：响应里没有「$3」"; fi; }

# 本机有全局代理时，探公网必须绕过；否则拿到的是代理造成的假象。
curlq() { curl -s --noproxy '*' -m 25 "$@"; }
sshq() { ssh "${SSH_OPTS[@]}" "$SSH_HOST" "$@"; }

# 归一化：去注释、压缩空白、去空行、去 CR —— 只比较语义内容。
# （CR 也要去：本机工作区可能被 git 转成 CRLF，直接 diff 会得到整文件差异的假象。）
normalize() { sed -e 's/\r$//' -e 's/#.*$//' -e 's/[[:space:]]\+/ /g' -e 's/^ //' -e 's/ $//' -e '/^$/d'; }

printf '部署后探针 → %s\n' "$BASE"
printf '（配置一致性对比：%s）\n\n' "${SSH_HOST:-已跳过}"

# Git Bash 下 mktemp 可能返回含盘符的 Windows 路径，与本 shell 拼接后 rm 会触发安全删除拦截
# （deploy-direct.sh 里对 TMPDIR 有同类处理）。非 POSIX 绝对路径时统一退回 /tmp。
TMP="$(mktemp -d 2>/dev/null || true)"
case "${TMP:-}" in
  /*) ;;
  *) TMP="/tmp/probe-deployment.$$" ;;
esac
mkdir -p "$TMP"
trap 'rm -rf "$TMP" 2>/dev/null || true' EXIT

# ---------- 0. 入口可达 ----------
printf '=== 0. 入口 ===\n'
root_code="$(curlq -o /dev/null -w '%{http_code}' "$BASE/")"
expect_eq "首页可访问" "$root_code" "200"

health="$(curlq "$BASE/api/system/health")"
printf '%s' "$health" | grep -q '"status":"UP"' && pass "health 报 UP" || fail "health 未报 UP：$(printf '%s' "$health" | head -c 120)"

# ---------- 1. 安全响应头（片段是否真的 include 进去了）----------
printf '\n=== 1. 安全响应头 ===\n'
headers="$(curlq -I "$BASE/" | tr -d '\r')"
expect_has "X-Content-Type-Options: nosniff" "$headers" "^X-Content-Type-Options: nosniff"
expect_has "X-Frame-Options: DENY" "$headers" "^X-Frame-Options: DENY"
expect_has "Referrer-Policy" "$headers" "^Referrer-Policy:"
expect_has "Content-Security-Policy-Report-Only" "$headers" "^Content-Security-Policy-Report-Only:"

# ---------- 2. health 收敛（匿名不得暴露 checks）----------
printf '\n=== 2. health 信息收敛 ===\n'
if printf '%s' "$health" | grep -q '"checks"'; then
  fail "匿名 health 暴露了 checks 明细（应收敛为 service/status）"
else
  pass "匿名 health 只回 service/status"
fi
detail_code="$(curlq -o /dev/null -w '%{http_code}' "$BASE/api/system/health/detail")"
expect_eq "health/detail 未认证" "$detail_code" "401"

# ---------- 3. 静态资源 gzip（第三十五批那条失效的守门项）----------
printf '\n=== 3. 静态资源压缩 ===\n'
asset="$(curlq "$BASE/" | grep -oE '/assets/[A-Za-z0-9._-]+\.js' | head -1)"
if [ -z "$asset" ]; then
  fail "首页里找不到 /assets/*.js（产物结构可能已变化，本项无法判定）"
else
  asset_headers="$(curlq -I -H 'Accept-Encoding: gzip' "$BASE$asset" | tr -d '\r')"
  expect_has "静态资源 $asset 返回 gzip" "$asset_headers" "^Content-Encoding: gzip"
  expect_has "静态资源 $asset 长缓存" "$asset_headers" "immutable"
fi

# ---------- 4. nginx 放行应用级体积上限（第二十九批那条失效的守门项）----------
# 用**未认证**请求即可判定：nginx 若先按 client_max_body_size 拦截，会返回自带 HTML 413
# （压根不到应用）；若已放行，请求会到应用并因未登录返回统一信封 401。
printf '\n=== 4. 请求体积边界 ===\n'
big="$TMP/big55.bin"
dd if=/dev/zero of="$big" bs=1000 count=5500 >/dev/null 2>&1
big_code="$(curlq -o "$TMP/big.out" -w '%{http_code}' -X POST -F "file=@$big" "$BASE/api/resume-imports/parse")"
if [ "$big_code" = "413" ] && grep -qi '<html' "$TMP/big.out"; then
  fail "5.5MB 上传被 nginx 拦截（HTML 413）—— client_max_body_size 未生效或小于 6m"
else
  expect_eq "5.5MB 上传已穿过 nginx（未登录 → 应用层拒绝）" "$big_code" "401"
fi

# ---------- 5. 生效配置 == 仓库配置（通用漂移检测）----------
printf '\n=== 5. 配置一致性（生效 nginx 配置 vs 仓库 host.conf）===\n'
if [ -z "${SSH_HOST:-}" ]; then
  printf '  [SKIP] 未提供 SSH_HOST，跳过\n'
else
  # 归一化：去注释、压缩空白、去空行、去 CR —— 只比较语义内容
  if sshq "sudo cat $NGINX_SITE" > "$TMP/remote.conf" 2>"$TMP/ssh.err"; then
    normalize < "$TMP/remote.conf" > "$TMP/remote.norm"
    normalize < "$REPO_ROOT/deploy/nginx/host.conf" > "$TMP/local.norm"
    if diff -q "$TMP/remote.norm" "$TMP/local.norm" >/dev/null 2>&1; then
      pass "服务器生效配置与仓库 deploy/nginx/host.conf 一致"
    else
      fail "服务器生效配置与仓库不一致（配置改了但没随部署生效）—— 差异："
      diff "$TMP/remote.norm" "$TMP/local.norm" | sed 's/^/         /' | head -20
    fi
  else
    fail "读取服务器配置失败：$(head -c 120 "$TMP/ssh.err" 2>/dev/null)"
  fi
fi

# ---------- 6. systemd 单元一致（同 B 类：抓"配置改了但没部署"）----------
# 第六十五批：此前部署链路只脚本化了 nginx，systemd 单元改动**不会**生效 ——
# 第四十批的 PDF_SERVICE_HOST 从未落地（服务器单元停在 2026-09-25）。与第六十一批同源，
# 故把这条也纳入通用漂移检测（不限于已知的那一行）。
printf '\n=== 6. systemd 单元一致性（生效单元 vs 仓库 deploy/systemd/）===\n'
if [ -z "${SSH_HOST:-}" ]; then
  printf '  [SKIP] 未提供 SSH_HOST，跳过\n'
else
  for unit_src in "$REPO_ROOT"/deploy/systemd/*.service "$REPO_ROOT"/deploy/systemd/*.timer; do
    [ -f "$unit_src" ] || continue
    unit_name="$(basename "$unit_src")"
    if sshq "sudo cat /etc/systemd/system/$unit_name" > "$TMP/unit.remote" 2>/dev/null; then
      normalize < "$unit_src" > "$TMP/unit.local.norm"
      normalize < "$TMP/unit.remote" > "$TMP/unit.remote.norm"
      if diff -q "$TMP/unit.remote.norm" "$TMP/unit.local.norm" >/dev/null 2>&1; then
        pass "$unit_name 生效内容与仓库一致"
      else
        fail "$unit_name 生效内容与仓库不一致（单元改了但没随部署生效）—— 差异："
        diff "$TMP/unit.remote.norm" "$TMP/unit.local.norm" | sed 's/^/         /' | head -20
      fi
    else
      fail "读取 /etc/systemd/system/$unit_name 失败（单元可能未安装）"
    fi
  done
fi

# ---------- 7. 监听范围（A 类行为断言：服务只绑回环，不对外暴露）----------
# API 的 `SERVER_ADDRESS` 与 PDF 的 `PDF_SERVICE_HOST` 都只写在 systemd 单元里，若未生效
# 进程会按默认绑**所有接口**。该暴露面此前只由云侧安全组兜底，而安全组规则在服务器内部
# 看不见 —— 所以必须在服务器上直接看监听地址，而不是从外部探测（外部通不通取决于安全组）。
printf '\n=== 7. 监听范围 ===\n'
if [ -z "${SSH_HOST:-}" ]; then
  printf '  [SKIP] 未提供 SSH_HOST，跳过\n'
else
  listeners="$(sshq "sudo ss -lntp" 2>/dev/null || true)"
  check_loopback_only() {
    label="$1"
    port="$2"
    addrs="$(printf '%s\n' "$listeners" | awk -v p=":${port}\$" '$4 ~ p {print $4}')"
    if [ -z "$addrs" ]; then
      fail "$label：未找到监听 :$port 的进程（服务未启动？）"
      return
    fi
    bad="$(printf '%s\n' "$addrs" | grep -Ev '^(127\.0\.0\.1|\[::1\]|\[::ffff:127\.0\.0\.1\]):' || true)"
    if [ -z "$bad" ]; then
      pass "$label 仅绑回环（$(printf '%s' "$addrs" | tr '\n' ' ')）"
    else
      fail "$label 监听在非回环地址：$bad —— 绑定参数未生效（该端口对同网段/公网可见，仅靠安全组兜底）"
    fi
  }
  check_loopback_only "API 8080" 8080
  check_loopback_only "PDF 3001" 3001
  check_loopback_only "Prometheus 9090" 9090
fi

# ---------- 8. 应用实际加载的配置 vs 磁盘上的 .env（抓「改了配置没重启」）----------
# 第六十七批：执行 E1（移除 kimi-k3）时才发现「哪个 .env 才生效」此前从未被验证过 ——
# 生效的是 app/api/.env（application.yml 用 spring.config.import 读它），同机的 live-ai.env
# 运行期**不被读取**，两者当时已经漂移。这类「改了文件但进程没变」与 nginx/systemd 的
# 「改了仓库但环境没变」同源，只是方向相反：**文件对了，运行时是旧的**。
printf '\n=== 8. 应用加载的模型链 vs app/api/.env ===\n'
if [ -z "${SSH_HOST:-}" ]; then
  printf '  [SKIP] 未提供 SSH_HOST，跳过\n'
else
  env_chain="$(sshq "sudo grep -m1 '^BAILIAN_MODEL_CHAIN=' $REMOTE_ROOT/app/api/.env" 2>/dev/null \
    | cut -d= -f2- | tr -d ' \r')"
  loaded_chain="$(sshq "sudo journalctl -u intelligent-resume-api --since '7 days ago' --no-pager" 2>/dev/null \
    | grep -o 'modelChain=\[[^]]*\]' | tail -1 \
    | sed -e 's/^modelChain=\[//' -e 's/\]$//' -e 's/, /,/g' -e 's/[[:space:]]//g')"
  if [ -z "$env_chain" ]; then
    fail "读不到 $REMOTE_ROOT/app/api/.env 的 BAILIAN_MODEL_CHAIN（该文件是运行期唯一生效来源）"
  elif [ -z "$loaded_chain" ]; then
    fail "取不到应用启动日志里的 modelChain=[...]（无法判定运行时链路）"
  elif [ "$env_chain" = "$loaded_chain" ]; then
    pass "应用加载的链与 app/api/.env 一致（$(printf '%s' "$env_chain" | tr ',' ' ' | wc -w) 个模型）"
  else
    fail "应用加载的链与磁盘 .env 不一致 —— 极可能「改了 .env 但没重启」：磁盘=$env_chain，进程=$loaded_chain"
  fi
fi

# ---------- 9. 备份与监控（批次 2 兑现）----------
# 这一节存在的理由：本批之前，"每日备份 + 保留 14 天"只写在文档里 —— scripts/ 与 deploy/ 下
# 没有任何备份脚本，也没有 timer；监控则抓的是 compose 服务名 `api:8080`，直连部署永远解析不到。
# 三项都验证**运行时事实**（脚本能连库/定时器真启用/目标真被抓到），不看配置文件是否存在。
printf '\n=== 9. 备份与监控 ===\n'
if [ -z "${SSH_HOST:-}" ]; then
  printf '  [SKIP] 未提供 SSH_HOST，跳过\n'
else
  # 9.1 备份脚本自带前置自检（读凭据 + 连库 + 表数下限），能过说明"备份真的能跑"
  if sshq "sudo test -x $REMOTE_ROOT/bin/backup-database.sh" 2>/dev/null; then
    if backup_check="$(sshq "sudo $REMOTE_ROOT/bin/backup-database.sh --check" 2>&1)"; then
      pass "备份脚本自检通过：$(printf '%s' "$backup_check" | tail -1)"
    else
      fail "备份脚本 --check 失败：$(printf '%s' "$backup_check" | tail -3 | tr '\n' ' ')"
    fi
  else
    fail "备份脚本未安装（$REMOTE_ROOT/bin/backup-database.sh 不存在或不可执行）"
  fi

  # 9.2 定时器必须 enabled（只放 unit 文件不 enable，重启后就不会跑）
  timer_state="$(sshq "sudo systemctl is-enabled intelligent-resume-backup.timer" 2>/dev/null | tr -d '\r')"
  if [ "$timer_state" = "enabled" ]; then
    next_run="$(sshq "systemctl list-timers intelligent-resume-backup.timer --no-pager" 2>/dev/null \
      | sed -n '2p' | awk '{print $1, $2, $3}')"
    pass "备份定时器已 enabled（下次触发：${next_run:-未知}）"
  else
    fail "备份定时器未 enabled（当前：${timer_state:-unknown}）—— 备份永远不会自动执行"
  fi

  # 9.3 prometheus 抓取目标必须为 up（配置文件存在 ≠ 真的在抓）
  # ⚠ 必须重试：部署会重启 API，而抓取间隔是 30s —— 重启后立刻单次查询必然拿到 up=0。
  # 2026-10-26 实测踩到：部署后探针报 FAIL，一分钟后再查就是 up=1。
  # 一个会随机红的门禁很快就会被人无视，所以这里等到 60s。
  prom_resp=""
  prom_ok=0
  for _attempt in 1 2 3 4 5 6 7 8 9 10; do
    prom_resp="$(sshq "curl -fsS 'http://127.0.0.1:9090/api/v1/query?query=up%7Bjob%3D%22intelligent-resume-api%22%7D'" 2>/dev/null)"
    if printf '%s' "$prom_resp" | grep -q '"value":\[[^]]*"1"\]'; then
      prom_ok=1
      break
    fi
    sleep 6
  done
  if [ "$prom_ok" -eq 1 ]; then
    pass "prometheus 抓取目标 up（job=intelligent-resume-api，第 ${_attempt} 次查询）"
  else
    fail "prometheus 60s 内仍未抓到 API（目标 down 或 prometheus 未运行）：$(printf '%s' "$prom_resp" | head -c 160)"
  fi

  # 9.4 最近一次备份真的成功过（现在只验"能跑"不够——定时器可能一直失败而上一项仍绿）
  backup_state="$(sshq "sudo systemctl show intelligent-resume-backup.service -p Result -p InactiveExitTimestamp" 2>/dev/null | tr -d '\r')"
  backup_result="$(printf '%s\n' "$backup_state" | sed -n 's/^Result=//p')"
  backup_ts="$(printf '%s\n' "$backup_state" | sed -n 's/^InactiveExitTimestamp=//p')"
  backup_age_hours=""
  if [ -n "$backup_ts" ]; then
    backup_epoch="$(sshq "date -d '$backup_ts' +%s" 2>/dev/null | tr -d '\r')"
    if [ -n "$backup_epoch" ] && [ "$backup_epoch" -gt 0 ] 2>/dev/null; then
      backup_age_hours=$(( ($(date +%s) - backup_epoch) / 3600 ))
    fi
  fi
  if [ "$backup_result" = "success" ] && [ -n "$backup_age_hours" ] && [ "$backup_age_hours" -le 36 ]; then
    pass "最近一次备份成功（${backup_age_hours}h 前；每日 03:30 触发 ⇒ 36h 内必有成功一次）"
  else
    fail "备份最近一次运行异常：Result=${backup_result:-unknown}，时间=${backup_ts:-unknown}（${backup_age_hours:-?}h 前）—— 备份静默失败正是 9.4 要抓的形态"
  fi

  # 9.5 备份目录事实：份数 ≥ 保底 3、最新一份 < 36h、目录 700 / 文件 600
  backup_facts="$(sshq "sudo bash -c 'ls -la /var/backups/intelligent-resume/ 2>/dev/null; stat -c %a /var/backups/intelligent-resume 2>/dev/null'" 2>/dev/null)"
  backup_count="$(printf '%s\n' "$backup_facts" | grep -c 'intelligent_resume-.*\.sql\.gz' || true)"
  backup_newest_age="$(sshq "sudo bash -c 'newest=\$(ls -t /var/backups/intelligent-resume/intelligent_resume-*.sql.gz 2>/dev/null | head -1); [ -n \"\$newest\" ] && echo \$(( (\$(date +%s) - \$(stat -c %Y \"\$newest\")) / 3600 )) || echo -1'" 2>/dev/null | tr -d '\r')"
  backup_dir_perm="$(printf '%s\n' "$backup_facts" | tail -1 | tr -d '\r')"
  backup_file_perm="$(printf '%s\n' "$backup_facts" | grep 'intelligent_resume-.*\.sql\.gz' | head -1 | awk '{print $1}' | cut -c1-10)"
  if [ "${backup_count:-0}" -ge 3 ] && [ "${backup_newest_age:--1}" -ge 0 ] && [ "$backup_newest_age" -le 36 ]; then
    pass "备份份数与新鲜度达标（${backup_count} 份，最新 ${backup_newest_age}h 前，保底 3）"
  else
    fail "备份份数/新鲜度不达标：共 ${backup_count:-0} 份（需 ≥3），最新 ${backup_newest_age:-?}h 前（需 ≤36）"
  fi
  if [ "$backup_dir_perm" = "700" ] && printf '%s' "$backup_file_perm" | grep -q '^-rw-\{5\}\|rw-------'; then
    pass "备份目录 700 / 文件 600（权限收紧生效）"
  else
    fail "备份权限异常：目录=${backup_dir_perm:-?}（需 700），文件=${backup_file_perm:-?}（需 600）—— PII 泄露面"
  fi
fi

# ---------- 10. 告警规则真的在评估（P3 兑现：rule_files 之前从未生效）----------
# 与 9.3 同理必须重试：部署会重启 prometheus，重启后 /api/v1/rules 短暂为空或 health=unknown。
printf '\n=== 10. 告警规则评估 ===\n'
if [ -z "${SSH_HOST:-}" ]; then
  printf '  [SKIP] 未提供 SSH_HOST，跳过\n'
else
  rules_resp=""
  rules_ok=0
  for _attempt in 1 2 3 4 5 6 7 8 9 10; do
    rules_resp="$(sshq "curl -fsS 'http://127.0.0.1:9090/api/v1/rules'" 2>/dev/null)"
    if printf '%s' "$rules_resp" | grep -q '"health":"ok"' \
      && [ "$(printf '%s' "$rules_resp" | grep -o '"name":"[A-Za-z]*"' | wc -l)" -ge 15 ]; then
      rules_ok=1
      break
    fi
    sleep 6
  done
  rule_count="$(printf '%s' "$rules_resp" | grep -o '"name":"[A-Za-z]*"' | wc -l | tr -d ' ')"
  if [ "$rules_ok" -eq 1 ]; then
    pass "告警规则已加载且评估正常（${rule_count} 条，health=ok，第 ${_attempt} 次查询）"
  else
    fail "告警规则未生效：/api/v1/rules 返回 ${rule_count:-0} 条或 health≠ok —— rule_files 未加载或 prometheus 未重启"
  fi

  firing_resp="$(sshq "curl -fsS 'http://127.0.0.1:9090/api/v1/alerts'" 2>/dev/null)"
  crit_firing="$(printf '%s' "$firing_resp" | python3 -c "import json,sys
try:
  alerts=json.load(sys.stdin).get('data',{}).get('alerts',[])
  print(sum(1 for a in alerts if a.get('state')=='firing' and a.get('labels',{}).get('severity')=='critical'))
except Exception:
  print(-1)" 2>/dev/null)"
  if [ "${crit_firing:--1}" -eq 0 ]; then
    pass "critical firing = 0（静默环境）"
  else
    fail "存在 critical firing = ${crit_firing:-解析失败} —— 检查 intelligent-resume-alertcheck 日志与上游服务"
  fi
fi

# ---------- 11. 告警出口（alertcheck.timer）真的在跑 ----------
printf '\n=== 11. 告警出口 ===\n'
if [ -z "${SSH_HOST:-}" ]; then
  printf '  [SKIP] 未提供 SSH_HOST，跳过\n'
else
  ac_timer="$(sshq "sudo systemctl is-enabled intelligent-resume-alertcheck.timer" 2>/dev/null | tr -d '\r')"
  if [ "$ac_timer" = "enabled" ]; then
    ac_next="$(sshq "systemctl list-timers intelligent-resume-alertcheck.timer --no-pager" 2>/dev/null | sed -n '2p' | awk '{print $1, $2, $3}')"
    pass "alertcheck 定时器已 enabled（下次触发：${ac_next:-未知}）"
  else
    fail "alertcheck 定时器未 enabled（当前：${ac_timer:-unknown}）—— 告警出口不存在，规则评估了也没人看"
  fi

  # 主动重跑一次而不是看"上次的 Result"：部署过程中 alertcheck 首跑会撞上 prometheus
  # 重启窗口（timer enable 早于 prometheus restart），Result 会留下一次无害的 exit-code——
  # 拿它做判据是随机红的。主动重跑既消除竞态，又真正验证出口路径此刻可用。
  ac_rc=0
  sshq "sudo systemctl start intelligent-resume-alertcheck.service" >/dev/null 2>&1 || ac_rc=$?
  if [ "$ac_rc" -eq 0 ]; then
    pass "alertcheck 主动重跑通过（退出码 0，无 critical firing）"
  elif [ "$ac_rc" -eq 2 ]; then
    fail "alertcheck 退出码 2 —— 此刻存在 critical firing（journalctl -u intelligent-resume-alertcheck 查看详情）"
  else
    fail "alertcheck 主动重跑失败（退出码 ${ac_rc}）—— 出口路径异常（prometheus 不可达或脚本错误）"
  fi
fi

# ---------- 汇总 ----------
printf '\n%s\n' "----------------------------------------------------------------------"
TOTAL=$((PASS + FAIL))
if [ -n "${SSH_HOST:-}" ]; then EXPECTED_ITEMS=$EXPECTED_ITEMS_SSH; else EXPECTED_ITEMS=$EXPECTED_ITEMS_NOSSH; fi
printf '探针合计 %d 项：通过 %d，失败 %d（期望 %d 项）\n' "$TOTAL" "$PASS" "$FAIL" "$EXPECTED_ITEMS"
if [ "$TOTAL" -ne "$EXPECTED_ITEMS" ]; then
  printf '⚠ 实际执行项数 %d ≠ 期望 %d —— 探针自身失效（检查项被注释/跳过？），本轮结果不可信。\n' "$TOTAL" "$EXPECTED_ITEMS"
  printf '  修正方法：增删检查项后同步更新脚本头部 EXPECTED_ITEMS_SSH / EXPECTED_ITEMS_NOSSH。\n'
  exit 1
fi
if [ "$FAIL" -gt 0 ]; then
  printf '⚠ 有失败项：部署虽已完成，但复核未通过 —— 不得据此判定"部署成功"。\n'
  exit 1
fi
printf '全部通过。\n'
