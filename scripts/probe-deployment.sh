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
#                    以及**生效的 systemd 单元**与仓库 `deploy/systemd/*.service` 归一化后逐行对比，
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
SSH_HOST="${SSH_HOST:-community-server}"   # 可为 ssh config 别名，也可为 user@host
SSH_KEY="${SSH_KEY:-$HOME/.ssh/id_ed25519}"
SSH_OPTS=(-o ConnectTimeout=15 -o BatchMode=yes)
[ -n "${SSH_KEY:-}" ] && [ -f "$SSH_KEY" ] && SSH_OPTS+=(-i "$SSH_KEY" -o IdentitiesOnly=yes)
REMOTE_ROOT="${REMOTE_ROOT:-/opt/intelligent-resume}"
NGINX_SITE="${NGINX_SITE:-/etc/nginx/sites-available/intelligent-resume}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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
  for unit_src in "$REPO_ROOT"/deploy/systemd/*.service; do
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
fi

# ---------- 汇总 ----------
printf '\n%s\n' "----------------------------------------------------------------------"
printf '探针合计 %d 项：通过 %d，失败 %d\n' "$((PASS + FAIL))" "$PASS" "$FAIL"
if [ "$FAIL" -gt 0 ]; then
  printf '⚠ 有失败项：部署虽已完成，但复核未通过 —— 不得据此判定"部署成功"。\n'
  exit 1
fi
printf '全部通过。\n'
