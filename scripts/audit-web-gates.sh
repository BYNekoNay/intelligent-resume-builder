#!/usr/bin/env bash
# ============================================================================
# 前端 build 链门禁有效性抽查（audit-web-gates）
#
# 与 scripts/audit-gate-effectiveness.sh 同一方法论（坏样本必红 / 好样本必绿），
# 但对象是 **web 侧两个门禁脚本**：check-i18n.mjs 与 check-draft-fields.mjs ——
# 后端 contract 包已双向实测承重（§2.64/2.65/2.67），此前前端这两个门禁从未被抽查。
#
# 稳健性设计（与后端版一致，踩过的坑不再踩）：
#   1. 注入前断言锚点存在且唯一（INJECT-FAILED 不算门禁问题，先修坏样本）；
#   2. 还原后核对 md5；
#   3. 「红」必须同时满足退出码非 0 **且** 输出含 FAILED 字样——只看退出码会把
#      脚本自己崩溃当成「门禁承重」；「绿」必须退出码 0 且输出含 passed。
#
# 用法：
#   bash scripts/audit-web-gates.sh                  # 全部行
#   GATE_AUDIT_ONLY='i18n-keys-mismatch' bash ...    # 只跑指定行（按行首标签）
#   GATE_AUDIT_MODE=good bash ...                    # 好样本方向（注释不得误报）
#
# 退出码：0 = 全部行符合预期；1 = 有没红/误报/注入失败/运行失败（需人工看）。
# ============================================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

# Git Bash (msys2) 调 native python.exe 会把 / 开头的 argv 当路径转换（见后端版头部）
export MSYS2_ARG_CONV_EXCL='*'

RUN_DIR="${GATE_AUDIT_RUN_DIR:-$ROOT/web}"

# ---------------------------------------------------------------------------
# 坏样本表：gate <TAB> file <TAB> old <TAB> new
#   gate = i18n | draft（决定跑哪个门禁脚本）；new 为空 ⇒ 删除该片段；
#   new/old 中的字面 \n 会在注入时转换为真实换行。
# ---------------------------------------------------------------------------
BAD_TABLE=$(cat <<'TABLE_EOF'
i18n-keys-mismatch	web/src/i18n/index.ts	      conflict: 'The content has been updated or its state changed. Please refresh and try again.',
i18n-duplicate-key	web/src/i18n/index.ts	      conflict: '内容已更新或状态已变化，请刷新后重试。',	      conflict: '内容已更新或状态已变化，请刷新后重试。',\n      conflict: '内容已更新或状态已变化，请刷新后重试。',
i18n-hardcoded-template	web/src/views/HomeView.vue	<div class="home-workspace">	<div class="home-workspace">\n      <span>幽灵审计文本</span>
i18n-hardcoded-runtime	web/src/api/export.ts	import { apiClient, type ApiResponse } from './client'	import { apiClient, type ApiResponse } from './client'\nconst auditProbe = () => { auditMessage.value = '幽灵审计文本' }
i18n-static-key-missing	web/src/views/HomeView.vue	<div class="home-workspace">	<div class="home-workspace">\n      <span>{{ t('ghost.audit.key') }}</span>
i18n-dynamic-key-ghost	web/src/utils/errorCodes.ts	  40001: 'errors.validation',	  40001: 'errors.validation',\n  99999: 'errors.ghostAudit',
draft-field-label-missing	web/src/components/DraftContentFields.vue	  name: 'draftFields.name',
draft-i18n-key-missing	web/src/i18n/index.ts	      name: '姓名', title: '职位名称', position: '职位', role: '角色',	      title: '职位名称', position: '职位', role: '角色',
draft-enum-english	web/src/i18n/index.ts	      valueExpert: '精通',	      valueExpert: 'Expert',
TABLE_EOF
)

# 好样本表：注释里的字样不得触发门禁（误报方向）。
GOOD_TABLE=$(cat <<'GOOD_EOF'
i18n-comment-template	web/src/views/HomeView.vue	<div class="home-workspace">	<div class="home-workspace">\n      <!-- 注释样例：t('ghost.comment.key') 与 保存失败 文本均在注释中 -->
i18n-comment-registry	web/src/utils/errorCodes.ts	  40001: 'errors.validation',	  40001: 'errors.validation',\n  // 99999: 'errors.ghostComment',
draft-comment-key	web/src/components/DraftContentFields.vue	const FIELD_LABEL_KEYS: Record<string, string> = {	const FIELD_LABEL_KEYS: Record<string, string> = {\n  // ghostAuditField: 'draftFields.ghostAudit',
GOOD_EOF
)

pass=0
suspect=0
inject_failed=0
declare -a REPORT=()

md5_of() { md5sum "$1" | cut -d' ' -f1; }

inject() { # file old new -> 0 ok / 1 anchor missing / 2 not unique
  python3 - "$1" "$2" "$3" <<'PY'
import io, sys
path, old, new = sys.argv[1], sys.argv[2], sys.argv[3]
old = old.replace('\\n', '\n')
new = new.replace('\\n', '\n')
s = io.open(path, encoding='utf-8', newline='').read()
n = s.count(old)
if n == 0:
    print('ANCHOR-NOT-FOUND'); sys.exit(1)
if n > 1:
    print('ANCHOR-NOT-UNIQUE(%d)' % n); sys.exit(2)
io.open(path, 'w', encoding='utf-8', newline='').write(s.replace(old, new, 1))
print('INJECTED')
PY
}

run_gate() { # gate -> 写 /tmp/web-gate-run.log，返回退出码
  local gate="$1" script
  if [[ "$gate" == i18n-* ]]; then script="node scripts/check-i18n.mjs"; else script="node scripts/check-draft-fields.mjs"; fi
  ( cd "$RUN_DIR" && eval "$script" ) > /tmp/web-gate-run.log 2>&1
}

MODE="${GATE_AUDIT_MODE:-bad}"
if [ "$MODE" = "good" ]; then TABLE="$GOOD_TABLE"; EXPECT=green; else TABLE="$BAD_TABLE"; EXPECT=red; fi
printf '\n===== 前端门禁抽查 · %s（%s 行）=====\n' \
  "$([ "$MODE" = good ] && echo '好样本必须通过' || echo '坏样本必须变红')" \
  "$(printf '%s\n' "$TABLE" | grep -c .)"
printf '%-26s %-10s %s\n' '抽查行' '结果' '说明'
printf -- '--------------------------------------------------------------------\n'

while IFS=$'\t' read -r gate file old new; do
  [ -z "${gate:-}" ] && continue
  if [ -n "${GATE_AUDIT_ONLY:-}" ] && [[ ",$GATE_AUDIT_ONLY," != *",$gate,"* ]]; then
    continue
  fi
  before="$(md5_of "$file")"
  backup="$(mktemp)"
  cp "$file" "$backup"

  out="$(inject "$file" "$old" "$new" 2>&1)" || true
  if [ "$out" != "INJECTED" ]; then
    inject_failed=$((inject_failed + 1))
    REPORT+=("$gate|INJECT-FAILED|$out（门禁本身未受检：坏样本没打进去）")
    printf '%-26s %-10s %s\n' "$gate" '注入失败' "$out"
    cp "$backup" "$file"; rm -f "$backup"
    continue
  fi

  run_gate "$gate"
  exit_code=$?

  # 区分「断言失败」与「脚本崩溃/语法错误」：红 = 退出码非 0 且输出含 FAILED 字样
  assert_failed=0
  if grep -aqE "guard FAILED|门禁未通过" /tmp/web-gate-run.log; then
    assert_failed=1
  fi
  green_ok=0
  if [ "$exit_code" -eq 0 ] && grep -aq "guard passed" /tmp/web-gate-run.log; then
    green_ok=1
  fi

  cp "$backup" "$file"; rm -f "$backup"
  after="$(md5_of "$file")"
  if [ "$before" != "$after" ]; then
    REPORT+=("$gate|RESTORE-FAILED|文件未还原，需人工处理：$file")
    printf '%-26s %-10s %s\n' "$gate" '还原失败' "$file"
    continue
  fi

  if [ "$exit_code" -ne 0 ] && [ "$assert_failed" -eq 0 ]; then
    inject_failed=$((inject_failed + 1))
    REPORT+=("$gate|RUN-FAILED|门禁没有跑到断言（崩溃/语法错误）—— 本行无结论，需人工看 /tmp/web-gate-run.log")
    printf '%-26s %-10s %s\n' "$gate" '运行失败' '没跑到断言，无结论'
    continue
  fi

  if [ "$EXPECT" = red ]; then
    if [ "$exit_code" -ne 0 ] && [ "$assert_failed" -eq 1 ]; then
      pass=$((pass + 1))
      REPORT+=("$gate|RED-AS-EXPECTED|注入坏样本后确实失败（门禁承重）")
      printf '%-26s %-10s %s\n' "$gate" '红(符合预期)' '门禁承重'
    else
      suspect=$((suspect + 1))
      REPORT+=("$gate|NOT-RED|注入坏样本后**仍然通过** —— 需人工判断：门禁假绿，还是坏样本没落在判据上")
      printf '%-26s %-10s %s\n' "$gate" '**没红**' '需人工判断（见报告）'
    fi
  else
    if [ "$green_ok" -eq 1 ]; then
      pass=$((pass + 1))
      REPORT+=("$gate|GREEN-AS-EXPECTED|注释里的字样未触发门禁（无注释误报）")
      printf '%-26s %-10s %s\n' "$gate" '绿(符合预期)' '注释未误报'
    else
      suspect=$((suspect + 1))
      REPORT+=("$gate|FALSE-POSITIVE|注释里的字样**触发了门禁** —— 误报缺陷（判据把注释当作了命中）")
      printf '%-26s %-10s %s\n' "$gate" '**误报**' '注释触发门禁（缺陷）'
    fi
  fi
done < <(printf '%s\n' "$TABLE")

printf -- '--------------------------------------------------------------------\n'
printf '承重 %d 个 / 异常 %d 个 / 注入或运行失败 %d 个\n' "$pass" "$suspect" "$inject_failed"

printf '\n===== 明细 =====\n'
for line in "${REPORT[@]}"; do printf '%s\n' "$line"; done

if [ "$suspect" -ne 0 ] || [ "$inject_failed" -ne 0 ]; then
  printf '\n⚠ 存在需要处理的项 —— 不得据此判定「门禁全部有效」。\n'
  exit 1
fi
printf '\n%s\n' "$([ "$MODE" = good ] && echo '全部抽查行都按预期保持绿（无注释误报）。' || echo '全部抽查行都按预期变红。')"
