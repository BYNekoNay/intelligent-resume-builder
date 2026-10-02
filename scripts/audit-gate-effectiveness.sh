#!/usr/bin/env bash
# ============================================================================
# 门禁有效性抽查（gate effectiveness audit）
#
# 动机：**永远返回「通过」的检查最危险**。本项目已多次遇到「门禁看着像样、
# 实际不承重」的情况（注释污染导致误报、环境依赖导致从未真正执行、判据与被判对象
# 不一致）。因此新增或修改门禁后，应当用**已知坏样本**验证它真的会红。
#
# 做法：对表里每个门禁注入一个量身坏样本 → 只跑该门禁 → 断言它**失败** → 还原并核对 md5。
#
# 关键稳健性设计（踩过坑）：
#   1. **注入前断言锚点存在**：否则「锚点写错 ⇒ 注入没生效 ⇒ 门禁没红」会被误判成
#      「门禁是假绿」。锚点缺失一律报 INJECT-FAILED，不计入门禁的问题。
#   2. **还原后核对 md5**：确认抽查没有留下任何改动。
#   3. 逐个门禁单独运行：既快，也能避免一次注入连带触发别的门禁造成误读。
#
# 用法：
#   bash scripts/audit-gate-effectiveness.sh
#   GATE_AUDIT_RUNNER='mvn -q -Dtest={class} test' bash scripts/audit-gate-effectiveness.sh
#   GATE_AUDIT_ONLY='AlertRuleContractTest,ShutdownContractTest' bash scripts/audit-gate-effectiveness.sh   # 只跑指定行
#
# 退出码：0＝全部抽查行都按预期变红；1＝存在「没红的门禁」或「注入失败」（需人工看）。
# ============================================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT"

RUNNER="${GATE_AUDIT_RUNNER:-mvn -q -Dtest={class} test}"
RUN_DIR="${GATE_AUDIT_RUN_DIR:-$ROOT/server}"

# ---------------------------------------------------------------------------
# 抽查表：gateClass <TAB> file <TAB> old <TAB> new
#   new 为空 ⇒ 删除该片段
# 每行的坏样本都指向「该门禁自己声称要守的东西」，不是随便改一个字符。
# ---------------------------------------------------------------------------
TABLE=$(cat <<'TABLE_EOF'
StaticAssetDeliveryContractTest	web/nginx.conf	gzip on;	gzip off;
UploadPathContractTest	web/nginx.conf	client_max_body_size 6m;	client_max_body_size 1m;
DeployProxyClientIpContractTest	deploy/nginx/host.conf	proxy_set_header X-Forwarded-For $remote_addr;	proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
ComposeResourceBoundsContractTest	deploy/docker-compose.prod.yml	x-logging: &service-logging	x-logging2: &service-logging
EnvExampleContractTest	server/.env.example	SERVER_PORT=8080	
ErrorCodeContractTest	web/src/utils/errorCodes.ts	50002: 'errors.aiFailure',	
AlertRuleContractTest	monitoring/prometheus/rules/intelligent-resume-alerts.yml	sum(rate(resume_ai_provider_calls_total{outcome="success"}[10m]))	sum(rate(resume_ai_provider_calls_totals{outcome="success"}[10m]))
SpringBootTestProfileContractTest	server/src/test/java/com/intelligentresume/ai/confirmation/controller/ConfirmationControllerIT.java	@ActiveProfiles("test")	
ShutdownContractTest	server/src/main/resources/application.yml	timeout-per-shutdown-phase: ${SHUTDOWN_TIMEOUT:20s}	timeout-per-shutdown-phase: ${SHUTDOWN_TIMEOUT:5s}
RetentionPolicyContractTest	docs/07-测试计划与验收说明书.md	AI 任务原始输入和结果 90 天后删除非必要内容	AI 任务原始输入和结果 30 天后删除非必要内容
PdfServiceBindScopeContractTest	deploy/systemd/intelligent-resume-api.service	Environment=SERVER_ADDRESS=127.0.0.1	Environment=SERVER_ADDRESS=0.0.0.0
PdfOutputBoundContractTest	server/src/main/java/com/intelligentresume/export/service/PdfServiceClient.java	if (pdfBytes.length > maxOutputBytes) {	if (false) {
TemplateCodeContractTest	web/src/api/export.ts	'academic'	'academic2'
SecurityHeaderDeliveryContractTest	deploy/nginx/edge.conf	include snippets/security-headers.conf;	
ApiDocCoverageGateTest	docs/05-接口设计说明书.md	`POST /api/auth/register`	`POST /api/auth/registr`
ConfigFallbackContractTest	server/src/main/resources/application.yml	render-timeout-seconds: ${PDF_RENDER_TIMEOUT_S:50}	render-timeout-seconds: ${PDF_RENDER_TIMEOUT_S:30}
DtoFieldContractTest	web/src/api/ai.ts	  errorMessage: string | null
	
ExportStreamingContractTest	server/src/main/java/com/intelligentresume/auth/controller/AuthController.java	getOutputStream()	getOutputStreamXX()
FrontendApiContractTest	web/src/api/export.ts	'/api/exports/pdf'	'/api/exports/pdf2'
FrontendEnumContractTest	web/src/api/ai.ts	| 'CANCELLED'	
PdfDeadlineContractTest	server/src/main/resources/application.yml	render-timeout-seconds: ${PDF_RENDER_TIMEOUT_S:50}	render-timeout-seconds: ${PDF_RENDER_TIMEOUT_S:5000}
ResponseCompressionContractTest	server/src/main/resources/application.yml	min-response-size: 2048	min-response-size: 1
SchemaDocContractTest	docs/04-数据库设计说明书.md	### 3.4 resume_version	### 3.4 resume_version_x
TABLE_EOF
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
s = io.open(path, encoding='utf-8').read()
n = s.count(old)
if n == 0:
    print('ANCHOR-NOT-FOUND'); sys.exit(1)
if n > 1:
    print('ANCHOR-NOT-UNIQUE(%d)' % n); sys.exit(2)
io.open(path, 'w', encoding='utf-8', newline='').write(s.replace(old, new, 1))
print('INJECTED')
PY
}

printf '\n===== 门禁有效性抽查（%s 行）=====\n' "$(printf '%s\n' "$TABLE" | grep -c .)"
printf '%-38s %-10s %s\n' '门禁' '结果' '说明'
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
    printf '%-38s %-10s %s\n' "$gate" '注入失败' "$out"
    cp "$backup" "$file"; rm -f "$backup"
    continue
  fi

  # 只跑该门禁；预期**失败**
  ( cd "$RUN_DIR" && eval "${RUNNER//\{class\}/$gate}" ) >/tmp/gate-audit-run.log 2>&1
  exit_code=$?

  cp "$backup" "$file"; rm -f "$backup"
  after="$(md5_of "$file")"
  if [ "$before" != "$after" ]; then
    REPORT+=("$gate|RESTORE-FAILED|文件未还原，需人工处理：$file")
    printf '%-38s %-10s %s\n' "$gate" '还原失败' "$file"
    continue
  fi

  if [ "$exit_code" -ne 0 ]; then
    pass=$((pass + 1))
    REPORT+=("$gate|RED-AS-EXPECTED|注入坏样本后确实失败（门禁承重）")
    printf '%-38s %-10s %s\n' "$gate" '红(符合预期)' '门禁承重'
  else
    suspect=$((suspect + 1))
    REPORT+=("$gate|NOT-RED|注入坏样本后**仍然通过** —— 需人工判断：门禁是假绿，还是坏样本没落在它的判据上")
    printf '%-38s %-10s %s\n' "$gate" '**没红**' '需人工判断（见报告）'
  fi
done < <(printf '%s\n' "$TABLE")

printf -- '--------------------------------------------------------------------\n'
printf '承重 %d 个 / 没红 %d 个 / 注入失败 %d 个\n' "$pass" "$suspect" "$inject_failed"

printf '\n===== 明细 =====\n'
for line in "${REPORT[@]}"; do printf '%s\n' "$line"; done

if [ "$suspect" -ne 0 ] || [ "$inject_failed" -ne 0 ]; then
  printf '\n⚠ 存在需要处理的项（没红／注入失败）—— 不得据此判定「门禁全部有效」。\n'
  exit 1
fi
printf '\n全部抽查行都按预期变红。\n'
