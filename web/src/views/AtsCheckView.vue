<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  AlertTriangle, BrainCircuit, Check, CheckCircle2, Copy, FileSearch, ListChecks,
  PencilLine, Quote, RefreshCw, ScanText, SearchCheck, ShieldCheck, Sparkles, Target,
} from 'lucide-vue-next'
import {
  getAtsCheck, getAtsParserView, retryAtsAi, runAtsCheck,
  type AtsCheckResponse, type ParserViewResponse, type ParserViewWarning,
} from '@/api/ats'
import LoadingBlock from '@/components/ui/LoadingBlock.vue'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'
import { useTaskPolling, TASK_POLL_DEFAULT_INITIAL_DELAY_MS } from '@/composables/useTaskPolling'
import { useLocale } from '@/i18n'
import { mapAtsSection } from '@/resume/sectionRegistry'
import { parserSectionLabelKey, parserWarningLabelKey } from '@/utils/parserLabels'
import { resumeSourceLabelKey } from '@/utils/resumeSource'

const ATS_POLL_INTERVAL_MS = 1_500
// 窗口 400 × 1.5s ≈ 10 分钟，与 useTaskPolling 默认窗口对齐（后端 AI_CHAIN_TOTAL_BUDGET_S=600s
// 加串行排队余量）。旧值 200（300s）短于实测耗时 —— ATS 的 AI 分析实测需 338s（因排在生成任务之后）。
const ATS_POLL_MAX_ATTEMPTS = 400
const resumeVersionId = ref('')
const jobDescriptionId = ref('')
const result = ref<AtsCheckResponse | null>(null)
const error = ref('')
const loading = ref(false)
const checkingMode = ref<'AI' | 'RULES' | null>(null)
const retrying = ref(false)
const pollingTimedOut = ref(false)
const { start: startTaskPolling, stop: stopTaskPolling } = useTaskPolling<AtsCheckResponse>()

const { t } = useLocale()
const route = useRoute()
const router = useRouter()
const { resumes, jobs, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load, loadVersions } = useResumeJobOptions()
const analyzing = computed(() => result.value?.analysisStatus === 'ANALYZING' && !pollingTimedOut.value)
const showReport = computed(() => Boolean(result.value) && (!analyzing.value || pollingTimedOut.value))

// TC-4：降级原因码 → 前端文案。此前直接渲染服务端 fallback.message（仅中文），
// en-US 界面会显示中文；未识别的新码回退到通用文案，同样不透传服务端文本。
const fallbackMessages = computed<Record<string, string>>(() => ({
  AI_DISABLED: t('ats.fallbackAiDisabled'),
  CONSENT_REQUIRED: t('ats.fallbackConsentRequired'),
  QUOTA_EXCEEDED: t('ats.fallbackQuotaExceeded'),
  PROVIDER_TIMEOUT: t('ats.fallbackProviderTimeout'),
  PROVIDER_ERROR: t('ats.fallbackProviderError'),
  INVALID_RESPONSE: t('ats.fallbackInvalidResponse'),
  UNKNOWN: t('ats.fallbackUnknown'),
}))

function fallbackMessage(code: string) {
  return fallbackMessages.value[code] ?? t('ats.fallbackUnknown')
}

function stopPolling() {
  stopTaskPolling()
}

async function rememberResult(id: number) {
  await router.replace({ query: { ...route.query, result: String(id) } })
}

async function check(useAi: boolean) {
  if (!resumeVersionId.value || !jobDescriptionId.value) {
    error.value = t('ats.selectError')
    return
  }
  stopPolling()
  error.value = ''
  result.value = null
  pollingTimedOut.value = false
  loading.value = true
  checkingMode.value = useAi ? 'AI' : 'RULES'
  try {
    result.value = (await runAtsCheck(Number(resumeVersionId.value), Number(jobDescriptionId.value), useAi))
    await rememberResult(result.value.id)
    if (result.value.analysisStatus === 'ANALYZING') startPolling(result.value.id)
  } catch {
    error.value = t('ats.runError')
  } finally {
    loading.value = false
    checkingMode.value = null
  }
}

function startPolling(id: number) {
  stopPolling()
  // 统一走 useTaskPolling：1.5s 间隔 × 200 次 ≈ 5 分钟窗口，
  // 对齐后端 300s 推理 readTimeout；超时后保留本地规则报告并提示后台继续。
  startTaskPolling({
    taskId: id,
    fetchTask: async (taskId) => (await getAtsCheck(taskId)),
    intervalMs: ATS_POLL_INTERVAL_MS,
    maxAttempts: ATS_POLL_MAX_ATTEMPTS,
    initialDelayMs: TASK_POLL_DEFAULT_INITIAL_DELAY_MS,
    onTask: (next) => {
      if (next.analysisStatus !== 'ANALYZING'
        || result.value?.analysisStatus !== 'ANALYZING'
        || next.aiTaskId !== result.value?.aiTaskId) {
        result.value = next
      }
    },
    shouldStop: (next) => next.analysisStatus !== 'ANALYZING',
    onTimeout: () => {
      pollingTimedOut.value = true
    },
    onError: () => {
      stopTaskPolling()
      error.value = t('ats.restoreError')
    },
  })
}

async function retryAi() {
  if (!result.value) return
  stopPolling()
  error.value = ''
  retrying.value = true
  pollingTimedOut.value = false
  try {
    result.value = (await retryAtsAi(result.value.id))
    if (result.value.analysisStatus === 'ANALYZING') startPolling(result.value.id)
  } catch {
    error.value = t('ats.retryError')
  } finally {
    retrying.value = false
  }
}

async function authorizeAi() {
  await router.push({ path: '/ai-consent', query: { redirect: route.fullPath } })
}

function coverageLabel(status: 'MATCHED' | 'PARTIAL' | 'MISSING') {
  if (status === 'MATCHED') return t('ats.matched')
  if (status === 'PARTIAL') return t('ats.partial')
  return t('ats.missing')
}

function sourceLabel(current: AtsCheckResponse) {
  if (current.analysisSource === 'HYBRID') return t('ats.sourceHybrid')
  return current.analysisStatus === 'RULES_ONLY' ? t('ats.sourceRulesOnly') : t('ats.sourceRules')
}

function editorLocation(section: string, kind: 'evidence' | 'action', index: number) {
  if (!result.value || result.value.resumeId === null) return null
  const mappedSection = mapAtsSection(section)
  if (!mappedSection) return null
  return {
    path: `/resumes/${result.value.resumeId}/edit`,
    query: {
      section: mappedSection,
      atsResultId: String(result.value.id),
      sourceVersionId: String(result.value.resumeVersionId),
      atsItem: `${kind}:${index}`,
    },
  }
}

// ---------------------------------------------------------------- B4：关键词缺口

const gap = computed(() => result.value?.keywordGap ?? null)

/** 三档顺序固定为「缺失 → 仅同义词 → 直接命中」：用户先看要补的，再看已经有的。 */
const gapGroups = computed(() => {
  const current = gap.value
  if (!current) return []
  return [
    { key: 'missing', tone: 'missing', label: t('atsParser.keywordMissing'), items: current.missing },
    { key: 'partial', tone: 'partial', label: t('atsParser.keywordPartial'), items: current.partialMatched },
    { key: 'matched', tone: 'matched', label: t('atsParser.keywordMatched'), items: current.matched },
  ]
})

// ------------------------------------------------------------ B4：解析器视角

const parserView = ref<ParserViewResponse | null>(null)
const parserLoading = ref(false)
const parserError = ref('')
const copied = ref(false)
let copiedTimer: ReturnType<typeof setTimeout> | null = null

const parserItemTotal = computed(
  () => parserView.value?.sections.reduce((sum, section) => sum + section.itemCount, 0) ?? 0,
)

/**
 * 联系方式识别结果。邮箱要「有内容」**且**「格式可解析」才算识别成功——
 * 这正是 ATS 的真实判据：字段存在但格式怪，机器一样读不到。
 *
 * 链接是**可选**项：多数正常简历没有个人链接，把它标成红色缺失属于误报，
 * 会让用户去补一个并不影响 ATS 解析的东西。三档：ok / missing / optional。
 */
const contactChips = computed(() => {
  const contact = parserView.value?.contact
  if (!contact) return []
  return [
    { label: t('atsParser.contactName'), tone: contact.name ? 'ok' : 'missing', value: '' },
    { label: t('atsParser.contactEmail'), tone: contact.email && contact.emailValid ? 'ok' : 'missing', value: '' },
    { label: t('atsParser.contactPhone'), tone: contact.phone ? 'ok' : 'missing', value: '' },
    { label: t('atsParser.contactLocation'), tone: contact.location ? 'ok' : 'missing', value: '' },
    {
      label: t('atsParser.contactLinks'),
      tone: contact.linkCount > 0 ? 'ok' : 'optional',
      value: t('atsParser.contactLinkCount', { count: contact.linkCount }),
    },
  ]
})

function sectionLabel(code: string) {
  const key = parserSectionLabelKey(code)
  return key ? t(key) : code
}

/** 未登记的码（后端先上线）退回显示码本身，不静默吞掉——用户至少能看到"有个新风险"。 */
function warningText(warning: ParserViewWarning) {
  const key = parserWarningLabelKey(warning.code)
  if (!key) return warning.code
  return t(key, { count: warning.count ?? 0, section: warning.section ?? '' })
}

async function loadParserView(versionId: number) {
  parserLoading.value = true
  parserError.value = ''
  try {
    parserView.value = await getAtsParserView(versionId)
  } catch {
    parserView.value = null
    parserError.value = t('atsParser.loadError')
  } finally {
    parserLoading.value = false
  }
}

async function copyParserText() {
  if (!parserView.value) return
  try {
    await navigator.clipboard.writeText(parserView.value.plainText)
    copied.value = true
    if (copiedTimer) clearTimeout(copiedTimer)
    copiedTimer = setTimeout(() => { copied.value = false }, 2_000)
  } catch {
    // 剪贴板被拒（非安全上下文/无权限）时保持按钮原状，不提示"已复制"——宁可没反应也不说谎
    copied.value = false
  }
}

// 解析器视角只依赖简历版本：同一版本换 JD 重跑检查时不必重新取
watch(() => result.value?.resumeVersionId, (versionId) => {
  if (versionId) void loadParserView(versionId)
})

onMounted(async () => {
  await load()
  const id = Number(route.query.result)
  if (!Number.isInteger(id) || id <= 0) return
  try {
    result.value = (await getAtsCheck(id))
    if (result.value.analysisStatus === 'ANALYZING') startPolling(id)
  } catch {
    error.value = t('ats.restoreError')
  }
})

onBeforeUnmount(() => {
  stopPolling()
  if (copiedTimer) clearTimeout(copiedTimer)
})
</script>

<template>
  <section class="workspace-page ats-page">
    <header class="ats-heading">
      <p class="eyebrow"><SearchCheck :size="14" />{{ t('ats.eyebrow') }}</p>
      <h1>{{ t('ats.title') }}</h1>
      <p class="page-lead">{{ t('ats.titleDesc') }}</p>
      <div class="ats-disclaimer"><AlertTriangle :size="15" /><span>{{ t('ats.disclaimer') }}</span></div>
    </header>

    <form class="ats-config" @submit.prevent="check(true)">
      <header class="ats-section-heading">
        <span><ListChecks :size="19" /></span>
        <div><p>{{ t('ats.setupEyebrow') }}</p><h2>{{ t('ats.setupTitle') }}</h2><small>{{ t('ats.setupDescription') }}</small></div>
      </header>
      <label>{{ t('ats.resume') }}<select v-model.number="selectedResumeId" :disabled="optionsLoading || analyzing" @change="loadVersions"><option :value="null" disabled>{{ t('ats.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <label>{{ t('ats.version') }}<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions || analyzing" required><option value="" disabled>{{ t('ats.selectVersion') }}</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ t(resumeSourceLabelKey(version.sourceType)) }}</option></select></label>
      <label>{{ t('ats.job') }}<select v-model="jobDescriptionId" :disabled="optionsLoading || analyzing" required><option value="" disabled>{{ t('ats.selectJob') }}</option><option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <div class="check-actions">
        <button type="button" class="btn-neon btn-ghost" :disabled="loading || optionsLoading || analyzing" @click="check(false)"><SearchCheck :size="16" />{{ checkingMode === 'RULES' ? t('ats.checkingRules') : t('ats.runRules') }}</button>
        <button class="btn-neon btn-primary" :disabled="loading || optionsLoading || analyzing"><BrainCircuit :size="16" />{{ checkingMode === 'AI' || analyzing ? t('ats.checkingAi') : t('ats.runAi') }}</button>
      </div>
    </form>

    <p v-if="optionsError || error" class="form-error" role="alert">{{ error || optionsError }}</p>

    <section v-if="analyzing" class="analysis-progress" role="status" aria-live="polite">
      <span class="analysis-icon"><BrainCircuit :size="24" /></span>
      <div><p class="section-kicker">{{ t('ats.sourceHybrid') }}</p><h2>{{ t('ats.analyzingTitle') }}</h2><p>{{ t('ats.analyzingDescription') }}</p><small>{{ t('ats.analyzingWait') }}</small></div>
      <span class="progress-line" aria-hidden="true" />
    </section>

    <article v-if="showReport && result" class="ats-result">
      <header class="result-heading">
        <div><p class="section-kicker">{{ t('ats.resultEyebrow') }}</p><h2>{{ t('ats.resultTitle') }}</h2></div>
        <span class="source-badge" :class="{ hybrid: result.analysisSource === 'HYBRID' }"><Sparkles v-if="result.analysisSource === 'HYBRID'" :size="13" /><FileSearch v-else :size="13" />{{ sourceLabel(result) }}</span>
      </header>

      <section v-if="pollingTimedOut && result.analysisStatus === 'ANALYZING'" class="fallback-banner background">
        <BrainCircuit :size="18" /><div><strong>{{ t('ats.analyzingBackground') }}</strong><p>{{ t('ats.analyzingBackgroundHint') }}</p></div>
      </section>
      <section v-else-if="result.fallback" class="fallback-banner">
        <AlertTriangle :size="18" /><div><strong>{{ t('ats.fallbackTitle') }}</strong><p>{{ fallbackMessage(result.fallback.code) }}</p></div>
        <button v-if="result.fallback.consentRequired" class="btn-neon btn-primary" @click="authorizeAi"><ShieldCheck :size="15" />{{ t('ats.authorizeAi') }}</button>
        <button v-else-if="result.fallback.retryable" class="btn-neon btn-ghost" :disabled="retrying" @click="retryAi"><RefreshCw :size="15" :class="{ spinning: retrying }" />{{ retrying ? t('ats.retrying') : t('ats.retryAi') }}</button>
      </section>

      <div class="score-grid"><p><strong>{{ t('ats.score') }}</strong><span>{{ result.totalScore }}</span></p><p><strong>{{ t('ats.structure') }}</strong><span>{{ result.checks.structure }}</span></p><p><strong>{{ t('ats.coverage') }}</strong><span>{{ result.checks.keywordCoverage }}</span></p></div>

      <section v-if="gap" class="keyword-gap">
        <header>
          <span><Target :size="18" /></span>
          <div><h3>{{ t('atsParser.keywordTitle') }}</h3><p>{{ t('atsParser.keywordDesc') }}</p></div>
          <strong>{{ gap.coverage }}<small>%</small></strong>
        </header>
        <div class="gap-groups">
          <article v-for="group in gapGroups" :key="group.key" :class="['gap-group', group.tone]">
            <header><span>{{ group.label }}</span><small>{{ group.items.length }}</small></header>
            <p v-if="!group.items.length" class="gap-empty">{{ t('atsParser.keywordNone') }}</p>
            <ul v-else><li v-for="item in group.items" :key="`${group.key}-${item}`">{{ item }}</li></ul>
          </article>
        </div>
      </section>
      <p v-else-if="result.analysisStatus !== 'ANALYZING'" class="form-hint" role="status">{{ t('atsParser.keywordLegacy') }}</p>

      <section v-if="result.aiInsights" class="ai-insights">
        <header><span><BrainCircuit :size="18" /></span><div><h3>{{ t('ats.aiSummary') }}</h3><p>{{ result.aiInsights.summary }}</p></div><small>{{ t('ats.confidence') }} · {{ result.aiInsights.confidence }}</small></header>

        <div class="insight-section">
          <h3>{{ t('ats.semanticCoverage') }}</h3>
          <div class="coverage-list"><article v-for="item in result.aiInsights.semanticCoverage" :key="`${item.requirement}-${item.status}`"><header><strong>{{ item.requirement }}</strong><span :class="item.status.toLowerCase()">{{ coverageLabel(item.status) }}</span></header><p>{{ item.reason }}</p><small><Quote :size="12" />{{ item.evidence || t('ats.noEvidence') }}</small></article></div>
        </div>

        <div class="insight-columns">
          <section><h3>{{ t('ats.evidenceQuality') }}</h3><article v-for="(item, index) in result.aiInsights.evidenceFindings" :key="`${item.section}-${item.assessment}`"><strong>{{ item.section }}</strong><p>{{ item.assessment }}</p><small>{{ item.suggestion }}</small><RouterLink v-if="editorLocation(item.section, 'evidence', index)" class="insight-edit-link" :to="editorLocation(item.section, 'evidence', index)!"><PencilLine :size="13" />{{ t('atsOpenEvidenceInEditor') }}</RouterLink></article></section>
          <section><h3>{{ t('ats.readabilityRisks') }}</h3><ul><li v-for="risk in result.aiInsights.readabilityRisks" :key="risk">{{ risk }}</li><li v-if="!result.aiInsights.readabilityRisks.length">{{ t('ats.noRisks') }}</li></ul></section>
        </div>

        <div class="insight-section action-list"><h3>{{ t('ats.aiActions') }}</h3><article v-for="(item, index) in result.aiInsights.prioritizedActions" :key="`${item.priority}-${item.section}-${item.action}`"><span>{{ item.priority }}</span><div><strong>{{ item.section }}</strong><p>{{ item.action }}</p><small>{{ t('ats.basis') }} · {{ item.basis }}</small><RouterLink v-if="editorLocation(item.section, 'action', index)" class="insight-edit-link" :to="editorLocation(item.section, 'action', index)!"><PencilLine :size="13" />{{ t('atsOpenActionInEditor') }}</RouterLink></div></article></div>
      </section>

      <div class="ats-findings"><section class="priority-findings"><h3><AlertTriangle :size="15" />{{ t('ats.priorities') }}</h3><ol><li v-for="priority in result.priorities" :key="priority">{{ priority }}</li><li v-if="!result.priorities.length">{{ t('ats.noPriorities') }}</li></ol></section><section class="passed-findings"><h3><CheckCircle2 :size="15" />{{ t('ats.passed') }}</h3><ul><li v-for="passed in result.passedChecks" :key="passed">{{ passed }}</li></ul></section><section class="risk-findings"><h3><AlertTriangle :size="15" />{{ t('ats.risks') }}</h3><ul><li v-for="risk in result.risks" :key="risk">{{ risk }}</li><li v-if="!result.risks.length">{{ t('ats.noRisks') }}</li></ul></section></div>
      <section class="parser-view">
        <header class="parser-heading">
          <span><ScanText :size="18" /></span>
          <div><h3>{{ t('atsParser.title') }}</h3><p>{{ t('atsParser.desc') }}</p></div>
          <button v-if="parserView" type="button" class="btn-neon btn-ghost parser-copy" @click="copyParserText">
            <component :is="copied ? Check : Copy" :size="14" />{{ copied ? t('atsParser.copied') : t('atsParser.copy') }}
          </button>
        </header>

        <p v-if="parserError" class="form-error parser-error" role="alert">
          {{ parserError }}
          <button type="button" class="parser-retry" @click="loadParserView(result.resumeVersionId)">{{ t('atsParser.retry') }}</button>
        </p>
        <LoadingBlock v-else-if="parserLoading" :rows="3" />

        <template v-else-if="parserView">
          <p class="parser-meta" role="status">
            {{ t('atsParser.meta', { chars: parserView.charCount, lines: parserView.lineCount, items: parserItemTotal }) }}
          </p>
          <div class="parser-contact">
            <span v-for="field in contactChips" :key="field.label" :class="field.tone">
              <component v-if="field.tone !== 'optional'" :is="field.tone === 'missing' ? AlertTriangle : Check" :size="12" />{{ field.label }}<small v-if="field.value">{{ field.value }}</small>
            </span>
          </div>
          <div class="parser-sections">
            <span v-for="section in parserView.sections" :key="section.code" :class="{ empty: !section.present }">
              {{ sectionLabel(section.code) }}
              <small>{{ section.present ? t('atsParser.sectionItems', { count: section.itemCount }) : t('atsParser.sectionEmpty') }}</small>
            </span>
          </div>
          <ul v-if="parserView.warnings.length" class="parser-warnings">
            <li v-for="(warning, index) in parserView.warnings" :key="`${warning.code}-${warning.section ?? index}`">
              <AlertTriangle :size="13" />{{ warningText(warning) }}
            </li>
          </ul>
          <p v-else class="parser-clean"><CheckCircle2 :size="13" />{{ t('atsParser.noWarnings') }}</p>
          <details class="parser-text-block">
            <summary>{{ t('atsParser.textToggle') }}</summary>
            <pre class="parser-text">{{ parserView.plainText }}</pre>
          </details>
        </template>
      </section>

      <small class="result-disclaimer">{{ result.disclaimer }}</small>
    </article>
  </section>
</template>

<style scoped>
.ats-page { width: min(100%, 980px); max-width: 980px; gap: 24px; }
.ats-heading { display: grid; gap: 0; padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.ats-heading .eyebrow { justify-self: start; }
.ats-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.ats-heading .page-lead { max-width: 680px; font-size: 12px; }
.ats-disclaimer { display: inline-flex; align-items: center; gap: 7px; justify-self: start; margin-top: 15px; padding: 8px 10px; border: 1px solid color-mix(in srgb, var(--warning) 25%, var(--border)); border-radius: 5px; color: var(--warning); background: var(--warning-light); font-size: 9px; }
.ats-config { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; padding: 24px; border: 1px solid var(--border); border-left: 4px solid var(--info); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.ats-section-heading { grid-column: 1 / -1; display: grid; grid-template-columns: 40px minmax(0, 1fr); gap: 12px; padding-bottom: 18px; border-bottom: 1px solid var(--border-soft); }
.ats-section-heading > span, .analysis-icon, .ai-insights > header > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--info); background: var(--info-light); }
.ats-section-heading p, .ats-section-heading h2, .ats-section-heading small, .section-kicker { display: block; margin: 0; }
.ats-section-heading p, .section-kicker { margin-bottom: 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.ats-section-heading h2, .result-heading h2 { margin: 0; color: var(--text-primary); font-size: 16px; }
.ats-section-heading small { margin-top: 5px; color: var(--text-secondary); font-size: 10px; }
.ats-config label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.ats-config select { width: 100%; min-height: 40px; padding: 8px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font-size: 11px; }
.ats-config select:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.ats-config .btn-neon { grid-column: 1 / -1; justify-self: end; }
.check-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 8px; }
.analysis-progress { position: relative; display: grid; grid-template-columns: 48px 1fr; gap: 13px; overflow: hidden; padding: 22px 24px 26px; border: 1px solid var(--border); border-left: 4px solid var(--accent); border-radius: 7px; background: var(--bg-surface); }
.analysis-progress h2 { margin: 0 0 6px; font-size: 16px; }.analysis-progress p { margin: 0; color: var(--text-secondary); font-size: 11px; }.analysis-progress small { display: block; margin-top: 7px; color: var(--text-tertiary); font-size: 9px; }
.progress-line { position: absolute; right: 0; bottom: 0; left: 0; height: 3px; overflow: hidden; background: var(--border-soft); }.progress-line::after { content: ''; display: block; width: 38%; height: 100%; background: var(--accent); animation: analyze 1.8s ease-in-out infinite; }
.ats-result { display: grid; gap: 18px; padding: 24px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.result-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }.source-badge { display: inline-flex; align-items: center; gap: 5px; padding: 6px 8px; border-radius: 5px; color: var(--warning); background: var(--warning-light); font-size: 9px; font-weight: 750; }.source-badge.hybrid { color: var(--accent); background: var(--accent-light); }
.fallback-banner { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 12px 14px; border-left: 3px solid var(--warning); background: var(--warning-light); color: var(--warning); }.fallback-banner.background { border-left-color: var(--info); color: var(--info); background: var(--info-light); }.fallback-banner strong { font-size: 11px; }.fallback-banner p { margin: 3px 0 0; color: var(--text-secondary); font-size: 9px; line-height: 1.5; }
.score-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 0; margin: 0; border-block: 1px solid var(--border); }.score-grid p { display: grid; gap: 5px; margin: 0; padding: 14px; border-right: 1px solid var(--border-soft); }.score-grid p:last-child { border-right: 0; }.score-grid strong { color: var(--text-tertiary); font-size: 9px; }.score-grid span { color: var(--accent); font-family: var(--font-utility); font-size: 22px; font-weight: 700; }
.ai-insights { display: grid; gap: 0; border-block: 1px solid var(--border); }.ai-insights > header { display: grid; grid-template-columns: 44px minmax(0, 1fr) auto; gap: 12px; align-items: start; padding: 18px 0; }.ai-insights h3 { margin: 0; color: var(--text-primary); font-size: 11px; }.ai-insights > header p { margin: 5px 0 0; color: var(--text-secondary); font-size: 10px; line-height: 1.6; }.ai-insights > header small { color: var(--text-tertiary); font-size: 8px; }
.insight-section { padding: 18px 0; border-top: 1px solid var(--border-soft); }.coverage-list { display: grid; margin-top: 11px; }.coverage-list article { padding: 11px 0; border-top: 1px solid var(--border-soft); }.coverage-list article:first-child { border-top: 0; }.coverage-list article header { display: flex; justify-content: space-between; gap: 12px; }.coverage-list article header span, .action-list > article > span { padding: 3px 5px; border-radius: 4px; color: var(--warning); background: var(--warning-light); font-size: 8px; font-weight: 750; }.coverage-list article header span.matched { color: var(--success); background: var(--success-light); }.coverage-list article header span.missing { color: var(--danger); background: var(--danger-light); }.coverage-list article p, .insight-columns article p, .action-list article p { margin: 5px 0; color: var(--text-secondary); font-size: 10px; line-height: 1.5; }.coverage-list article small { display: flex; align-items: flex-start; gap: 5px; color: var(--text-tertiary); font-size: 9px; line-height: 1.5; }
.insight-columns { display: grid; grid-template-columns: 1fr 1fr; border-top: 1px solid var(--border-soft); }.insight-columns > section { padding: 18px 18px 18px 0; }.insight-columns > section + section { padding-right: 0; padding-left: 18px; border-left: 1px solid var(--border-soft); }.insight-columns article { padding: 10px 0; border-bottom: 1px solid var(--border-soft); }.insight-columns article strong { font-size: 10px; }.insight-columns article small, .action-list article small { color: var(--text-tertiary); font-size: 9px; line-height: 1.5; }.insight-columns ul { display: grid; gap: 7px; margin: 11px 0 0; padding-left: 17px; color: var(--text-secondary); font-size: 10px; line-height: 1.5; }
.action-list > article { display: grid; grid-template-columns: 30px 1fr; gap: 10px; padding: 11px 0; border-bottom: 1px solid var(--border-soft); }.action-list > article > span { align-self: start; text-align: center; }.action-list > article strong { font-size: 10px; }
.insight-edit-link { display: flex; align-items: center; gap: 5px; width: fit-content; margin-top: 9px; color: var(--accent); font-size: 10px; font-weight: 700; text-decoration: none; }.insight-edit-link:hover { text-decoration: underline; }.insight-edit-link:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 3px; }
.ats-findings { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }.ats-findings section { padding: 15px 17px; border: 1px solid var(--border-soft); border-radius: 6px; background: var(--bg-page); }.ats-findings .risk-findings { grid-column: 1 / -1; }.ats-findings h3 { display: flex; align-items: center; gap: 6px; margin: 0 0 9px; color: var(--text-primary); font-size: 11px; }.priority-findings h3, .risk-findings h3 { color: var(--warning); }.passed-findings h3 { color: var(--success); }.ats-findings ol, .ats-findings ul { display: grid; gap: 6px; margin: 0; padding-left: 18px; color: var(--text-secondary); font-size: 10px; line-height: 1.5; }.result-disclaimer { color: var(--text-tertiary); font-size: 9px; line-height: 1.55; }
/* B4：关键词缺口 */
.keyword-gap { display: grid; border-top: 1px solid var(--border); }
.keyword-gap > header { display: grid; grid-template-columns: 40px minmax(0, 1fr) auto; gap: 12px; align-items: start; padding: 16px 0; }
.keyword-gap > header > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--info); background: var(--info-light); }
.keyword-gap h3 { margin: 0; color: var(--text-primary); font-size: 11px; }
.keyword-gap > header p { max-width: 620px; margin: 4px 0 0; color: var(--text-secondary); font-size: 10px; line-height: 1.55; }
.keyword-gap > header strong { color: var(--accent); font-family: var(--font-utility); font-size: 20px; font-weight: 700; }
.keyword-gap > header strong small { font-size: 10px; }
.gap-groups { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; padding-bottom: 16px; }
.gap-group { padding: 12px 14px; border: 1px solid var(--border-soft); border-left: 3px solid var(--border); border-radius: 6px; background: var(--bg-page); }
.gap-group.missing { border-left-color: var(--danger); }
.gap-group.partial { border-left-color: var(--warning); }
.gap-group.matched { border-left-color: var(--success); }
.gap-group header { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; color: var(--text-secondary); font-size: 10px; font-weight: 700; }
.gap-group header small { color: var(--text-primary); font-family: var(--font-utility); font-size: 13px; }
.gap-group ul { display: flex; flex-wrap: wrap; gap: 5px; margin: 9px 0 0; padding: 0; list-style: none; }
.gap-group li { padding: 3px 7px; border: 1px solid var(--border-soft); border-radius: 4px; background: var(--bg-surface); color: var(--text-secondary); font-size: 9px; }
.gap-group.missing li { border-color: color-mix(in srgb, var(--danger) 30%, var(--border-soft)); color: var(--danger); }
.gap-group.partial li { color: var(--warning); }
.gap-group.matched li { color: var(--success); }
.gap-empty { margin: 9px 0 0; color: var(--text-tertiary); font-size: 9px; }
/* B4：解析器视角 */
.parser-view { display: grid; padding-top: 16px; border-top: 1px solid var(--border); }
.parser-heading { display: grid; grid-template-columns: 40px minmax(0, 1fr) auto; gap: 12px; align-items: start; padding-bottom: 14px; }
.parser-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--info); background: var(--info-light); }
.parser-heading h3 { margin: 0; color: var(--text-primary); font-size: 11px; }
.parser-heading p { max-width: 620px; margin: 4px 0 0; color: var(--text-secondary); font-size: 10px; line-height: 1.55; }
.parser-copy { padding: 6px 10px; font-size: 9px; }
.parser-error { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.parser-retry { border: 0; color: var(--accent); background: none; font-size: 9px; font-weight: 700; text-decoration: underline; cursor: pointer; }
.parser-meta { margin: 0 0 10px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; }
.parser-contact, .parser-sections { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 10px; }
.parser-contact span, .parser-sections span { display: inline-flex; align-items: center; gap: 5px; padding: 4px 8px; border: 1px solid var(--border-soft); border-radius: 4px; background: var(--bg-page); color: var(--text-secondary); font-size: 9px; }
.parser-contact span svg { color: var(--success); }
.parser-contact span.missing { border-color: color-mix(in srgb, var(--danger) 30%, var(--border-soft)); color: var(--danger); }
.parser-contact span.missing svg { color: var(--danger); }
.parser-contact span.optional { color: var(--text-tertiary); }
.parser-sections span small { color: var(--text-tertiary); font-family: var(--font-utility); }
.parser-sections span.empty { border-style: dashed; opacity: .6; }
.parser-warnings { display: grid; gap: 6px; margin: 0 0 10px; padding: 0; list-style: none; }
.parser-warnings li { display: flex; align-items: flex-start; gap: 6px; color: var(--warning); font-size: 10px; line-height: 1.5; }
.parser-clean { display: flex; align-items: center; gap: 6px; margin: 0 0 10px; color: var(--success); font-size: 10px; }
.parser-text-block { border: 1px solid var(--border-soft); border-radius: 6px; background: var(--bg-page); }
.parser-text-block summary { padding: 10px 12px; color: var(--text-secondary); font-size: 10px; font-weight: 650; cursor: pointer; }
.parser-text { max-height: 320px; margin: 0; padding: 0 12px 12px; overflow: auto; color: var(--text-primary); font-family: var(--font-utility); font-size: 10px; line-height: 1.65; white-space: pre-wrap; }
.spinning { animation: spin .8s linear infinite; }
@keyframes analyze { from { transform: translateX(-110%); } to { transform: translateX(270%); } } @keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .progress-line::after, .spinning { animation: none; } }
@media (max-width: 680px) { .ats-heading h1 { font-size: 29px; }.ats-config { grid-template-columns: 1fr; padding: 20px 16px; }.ats-section-heading, .ats-config .btn-neon { grid-column: auto; }.check-actions { grid-column: auto; display: grid; width: 100%; }.ats-config .btn-neon { width: 100%; justify-content: center; }.analysis-progress { grid-template-columns: 40px 1fr; padding: 18px 16px 22px; }.ats-result { padding: 20px 16px; }.result-heading { align-items: flex-start; flex-direction: column; }.fallback-banner { grid-template-columns: 22px 1fr; }.fallback-banner .btn-neon { grid-column: 1 / -1; width: 100%; justify-content: center; }.score-grid { grid-template-columns: 1fr; }.score-grid p { border-right: 0; border-bottom: 1px solid var(--border-soft); }.score-grid p:last-child { border-bottom: 0; }.ai-insights > header { grid-template-columns: 40px 1fr; }.ai-insights > header small { grid-column: 2; }.insight-columns, .ats-findings { grid-template-columns: 1fr; }.insight-columns > section, .insight-columns > section + section { padding: 16px 0; border-left: 0; }.insight-columns > section + section { border-top: 1px solid var(--border-soft); }.ats-findings .risk-findings { grid-column: auto; } }
@media (max-width: 680px) { .gap-groups { grid-template-columns: 1fr; } .parser-heading { grid-template-columns: 40px minmax(0, 1fr); } .parser-copy { grid-column: 2; justify-self: start; } }
</style>
