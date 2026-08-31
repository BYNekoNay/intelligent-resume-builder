<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, BarChart3, BookmarkPlus, CheckCircle2, FileText, Loader2, Mic2, Play, RefreshCw, Send, Shield, Sparkles } from 'lucide-vue-next'
import { createInterviewAsset } from '@/api/interviewAsset'
import {
  answerInterview, continueWithRules, finishInterview, getInterviewReport, getInterviewState,
  retryAi, startInterview,
  type AiFailureInfo, type InterviewReportResponse, type InterviewStateResponse, type LastEvaluation,
} from '@/api/interview'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'
import { useLocale } from '@/i18n'

const { locale, t } = useLocale()
const route = useRoute()
const router = useRouter()

const consentRedirectUrl = '/ai-consent?redirect=/interviews'
const reauthorizationHref = computed(() => consentRedirectUrl)
const sessionIdStorageKey = 'interview-session-id'

interface PendingRequest<T> { payload: T; idempotencyKey: string }

// ==================== 设置阶段状态 ====================
const sourceType = ref<'PLATFORM_RESUME' | 'EXTERNAL_RESUME'>('EXTERNAL_RESUME')
const interviewMode = ref<'JD_TARGETED' | 'TECHNICAL' | 'BEHAVIORAL' | 'COMPREHENSIVE'>('TECHNICAL')
const targetQuestionCount = ref(6)
const jobId = ref('')
const resumeVersionId = ref('')
const resumeText = ref('')
const starting = ref(false)
const error = ref('')

const { resumes, jobs, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load: loadOptions, loadVersions } = useResumeJobOptions()

// ==================== 会话阶段状态 ====================
const interviewId = ref<number | null>(null)
const sessionState = ref<InterviewStateResponse | null>(null)
const answer = ref('')
const submitting = ref(false)
const retrying = ref(false)
const fallingBack = ref(false)
const finishing = ref(false)

// ==================== 反馈与报告 ====================
const savingAsset = ref(false)
const savedRecordIds = ref<number[]>([])
const report = ref<InterviewReportResponse | null>(null)
const pendingStart = ref<PendingRequest<Parameters<typeof startInterview>[0]> | null>(null)
const pendingAnswer = ref<PendingRequest<{ interviewId: number; answer: string }> | null>(null)
let statePollTimer: ReturnType<typeof setTimeout> | null = null
let statePollAttempt = 0

// ==================== 计算属性 ====================
const status = computed(() => sessionState.value?.status ?? null)
const isAiLoading = computed(() => status.value === 'GENERATING_QUESTION' || status.value === 'EVALUATING_ANSWER')
const isAiFailed = computed(() => status.value === 'AI_ACTION_REQUIRED')
const isCompleted = computed(() => status.value === 'COMPLETED')
const isAwaitingAnswer = computed(() => status.value === 'AWAITING_ANSWER')
const question = computed(() => sessionState.value?.currentQuestion ?? '')
const progressText = computed(() => {
  if (!sessionState.value) return ''
  return `${sessionState.value.completedQuestionCount} / ${sessionState.value.targetQuestionCount}`
})
const canFinish = computed(() => {
  if (!sessionState.value) return false
  const count = sessionState.value.completedQuestionCount
  return count >= 1 && !isCompleted.value && !isAiLoading.value && !submitting.value && !retrying.value
})
const lastEval = computed<LastEvaluation | null>(() => sessionState.value?.lastEvaluation ?? null)
const aiFailure = computed<AiFailureInfo | null>(() => sessionState.value?.aiFailure ?? null)
const executionModeLabel = computed(() => {
  if (!sessionState.value) return ''
  return sessionState.value.executionMode === 'AI' ? t('interview.modeAi') : t('interview.modeRule')
})

const isGeneratingQuestion = computed(() => status.value === 'GENERATING_QUESTION')
const aiLoadingLabel = computed(() => isGeneratingQuestion.value ? t('interview.aiGeneratingQuestion') : t('interview.aiEvaluating'))

function samePayload(left: unknown, right: unknown) {
  return JSON.stringify(left) === JSON.stringify(right)
}

function applySessionState(result: InterviewStateResponse) {
  interviewId.value = result.interviewId
  sessionState.value = result
  if (result.status === 'COMPLETED') {
    pendingAnswer.value = null
  }
  sessionStorage.setItem(sessionIdStorageKey, String(result.interviewId))
  if (result.status === 'GENERATING_QUESTION' || result.status === 'EVALUATING_ANSWER') scheduleStatePoll()
  else stopStatePoll()
}

function stopStatePoll() {
  if (statePollTimer !== null) clearTimeout(statePollTimer)
  statePollTimer = null
  statePollAttempt = 0
}

function scheduleStatePoll() {
  if (statePollTimer !== null || interviewId.value === null) return
  const delay = Math.min(1000 * 2 ** Math.min(statePollAttempt, 3), 5000)
  statePollTimer = setTimeout(() => { void pollInterviewState() }, delay)
}

async function pollInterviewState() {
  statePollTimer = null
  const id = interviewId.value
  if (id === null || !isAiLoading.value) return
  try {
    const result = (await getInterviewState(id)).data.data
    if (interviewId.value !== id) return
    statePollAttempt += 1
    applySessionState(result)
    if (result.status === 'COMPLETED') void loadReport()
  } catch {
    statePollAttempt += 1
    scheduleStatePoll()
  }
}

// ==================== 开始面试 ====================
async function start() {
  error.value = ''
  if (sourceType.value === 'PLATFORM_RESUME' && !resumeVersionId.value) { error.value = t('interview.selectVersionFirst'); return }
  const selectedJobId = jobId.value ? Number(jobId.value) : undefined
  if (!selectedJobId && !window.confirm(t('interview.confirmStartWithoutJob'))) return
  starting.value = true
  const payload: Parameters<typeof startInterview>[0] = {
      sourceType: sourceType.value,
      resumeVersionId: sourceType.value === 'PLATFORM_RESUME' ? Number(resumeVersionId.value) : undefined,
      externalResumeText: sourceType.value === 'EXTERNAL_RESUME' ? resumeText.value : undefined,
      jobDescriptionId: selectedJobId,
      interviewMode: interviewMode.value,
      targetQuestionCount: targetQuestionCount.value,
      outputLanguage: locale.value === 'zh-CN' ? 'ZH_CN' : 'EN',
  }
  const pending = pendingStart.value && samePayload(pendingStart.value.payload, payload)
    ? pendingStart.value
    : { payload, idempotencyKey: crypto.randomUUID() }
  pendingStart.value = pending
  try {
    const result = (await startInterview(payload, pending.idempotencyKey)).data.data
    pendingStart.value = null
    applySessionState(result)
  } catch (e: any) {
    const status = e?.response?.status ?? e?.status
    if (status === 403) {
      router.push(consentRedirectUrl)
      return
    }
    error.value = t('interview.startError')
  }
  finally { starting.value = false }
}

// ==================== 提交回答 ====================
async function submit() {
  if (interviewId.value === null || !answer.value.trim()) return
  const payload = { interviewId: interviewId.value, answer: answer.value.trim() }
  const pending = pendingAnswer.value && samePayload(pendingAnswer.value.payload, payload)
    ? pendingAnswer.value
    : { payload, idempotencyKey: crypto.randomUUID() }
  pendingAnswer.value = pending
  error.value = ''
  submitting.value = true
  try {
    const result = (await answerInterview(payload.interviewId, payload.answer, pending.idempotencyKey)).data.data
    applySessionState(result)
    if (result.status !== 'AI_ACTION_REQUIRED') {
      pendingAnswer.value = null
      answer.value = ''
    }
  } catch { error.value = t('interview.submitError') }
  finally { submitting.value = false }
}

// ==================== AI 重试 ====================
async function retry() {
  if (interviewId.value === null) return
  error.value = ''
  retrying.value = true
  try {
    const result = (await retryAi(interviewId.value)).data.data
    applySessionState(result)
    if (result.status !== 'AI_ACTION_REQUIRED') {
      pendingAnswer.value = null
      answer.value = ''
    }
  } catch { error.value = t('interview.retryError') }
  finally { retrying.value = false }
}

// ==================== 规则降级 ====================
async function fallbackToRules() {
  if (interviewId.value === null) return
  error.value = ''
  fallingBack.value = true
  try {
    const result = (await continueWithRules(interviewId.value)).data.data
    applySessionState(result)
  } catch { error.value = t('interview.fallbackError') }
  finally { fallingBack.value = false }
}

// ==================== 主动结束 ====================
async function finish() {
  if (interviewId.value === null) return
  if (!window.confirm(t('interview.confirmFinish'))) return
  error.value = ''
  finishing.value = true
  try {
    const result = (await finishInterview(interviewId.value)).data.data
    applySessionState(result)
  } catch { error.value = t('interview.finishError') }
  finally { finishing.value = false }
}

// ==================== 保存答案资产 ====================
async function saveAsset() {
  if (!lastEval.value) return
  savingAsset.value = true; error.value = ''
  try {
    await createInterviewAsset({
      interviewRecordId: lastEval.value.recordId,
      questionText: lastEval.value.questionText,
      originalAnswerText: lastEval.value.answerText,
      suggestedAnswerText: lastEval.value.suggestedAnswer ?? undefined,
    })
    savedRecordIds.value.push(lastEval.value.recordId)
  } catch { error.value = t('interview.saveAssetError') }
  finally { savingAsset.value = false }
}

// ==================== 加载报告 ====================
async function loadReport() {
  if (interviewId.value === null) return
  try { report.value = (await getInterviewReport(interviewId.value)).data.data }
  catch { error.value = t('interview.loadReportError') }
}

// ==================== 版本来源标签 ====================
function versionSourceLabel(source: string) {
  const labels: Record<string, string> = {
    MANUAL: t('interview.sourceManual'), AI_OPTIMIZED: t('interview.sourceAiOptimized'),
    JD_CUSTOMIZED: t('interview.sourceJdCustomized'), MATERIAL_CUSTOMIZED: t('interview.sourceMaterialCustomized'),
    RESTORED: t('interview.sourceRestored'),
  }
  return labels[source] ?? t('interview.sourceOther')
}

// ==================== 五维评分标签 ====================
function dimensionLabels(scores: { relevance: number; evidenceSpecificity: number; structureClarity: number; roleCompetency: number; authenticityReflection: number } | null) {
  if (!scores) return []
  const maxMap: Record<string, number> = { relevance: 25, evidenceSpecificity: 25, structureClarity: 20, roleCompetency: 20, authenticityReflection: 10 }
  const labelMap: Record<string, string> = {
    relevance: t('interview.dimRelevance'), evidenceSpecificity: t('interview.dimEvidence'),
    structureClarity: t('interview.dimStructure'), roleCompetency: t('interview.dimRoleCompetency'),
    authenticityReflection: t('interview.dimAuthenticity'),
  }
  return Object.entries(scores).map(([key, value]) => ({
    key, label: labelMap[key] ?? key, value,
    percent: Math.round((value / (maxMap[key] ?? 25)) * 100),
  }))
}

// ==================== 刷新恢复 ====================
async function recoverSession() {
  const savedId = sessionStorage.getItem(sessionIdStorageKey)
  if (!savedId) return
  const id = Number(savedId)
  if (Number.isNaN(id)) return
  try {
    const result = (await getInterviewState(id)).data.data
    applySessionState(result)
    if (result.status === 'COMPLETED') {
      void loadReport()
    }
  } catch {
    sessionStorage.removeItem(sessionIdStorageKey)
  }
}

onMounted(() => {
  void loadOptions().then(() => {
    const requestedJobId = Number(route.query.jobDescriptionId)
    if (Number.isInteger(requestedJobId) && jobs.value.some(job => job.id === requestedJobId)) {
      jobId.value = String(requestedJobId)
      interviewMode.value = 'JD_TARGETED'
      return
    }
    void recoverSession()
  })
})

onBeforeUnmount(stopStatePoll)
</script>

<template>
  <section class="workspace-page interview-page">
    <header class="interview-heading">
      <div><p class="eyebrow"><Mic2 :size="14" />{{ t('interview.eyebrow') }}</p><h1>{{ t('interview.title') }}</h1><p class="page-lead">{{ t('interview.subtitle') }}</p></div>
      <div class="interview-route" :aria-label="t('interview.progressLabel')"><span class="active"><b>1</b>{{ t('interview.routePrepare') }}</span><i /><span :class="{ active: interviewId !== null && !isCompleted }"><b>2</b>{{ t('interview.routePractice') }}</span><i /><span :class="{ active: report }"><b>3</b>{{ t('interview.routeReview') }}</span></div>
    </header>
    <p v-if="error || optionsError" class="form-error" role="alert">{{ error || optionsError }}</p>

    <!-- ==================== 设置阶段 ==================== -->
    <form v-if="interviewId === null" class="interview-setup" @submit.prevent="start">
      <header class="interview-section-heading"><span><FileText :size="19" /></span><div><p>{{ t('interview.setupEyebrow') }}</p><h2>{{ t('interview.setupTitle') }}</h2><small>{{ t('interview.setupDescription') }}</small></div></header>
      <label>{{ t('interview.sourceType') }}<select v-model="sourceType"><option value="EXTERNAL_RESUME">{{ t('interview.externalResume') }}</option><option value="PLATFORM_RESUME">{{ t('interview.platformResume') }}</option></select></label>
      <label>{{ t('interview.interviewMode') }}<select v-model="interviewMode"><option value="TECHNICAL">{{ t('interview.modeTechnical') }}</option><option value="BEHAVIORAL">{{ t('interview.modeBehavioral') }}</option><option value="JD_TARGETED">{{ t('interview.modeJdTargeted') }}</option><option value="COMPREHENSIVE">{{ t('interview.modeComprehensive') }}</option></select></label>
      <label>{{ t('interview.targetQuestionCount') }}<input v-model.number="targetQuestionCount" type="number" min="4" max="12" /></label>
      <small class="field-hint">{{ t('interview.targetQuestionHint', { min: Math.ceil(targetQuestionCount * 0.5), max: Math.floor(targetQuestionCount * 1.5) }) }}</small>
      <template v-if="sourceType === 'PLATFORM_RESUME'"><label>{{ t('common.selectResume') }}<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>{{ t('common.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label><label>{{ t('common.selectVersion') }}<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required><option value="" disabled>{{ t('common.selectVersion') }}</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} &middot; {{ versionSourceLabel(version.sourceType) }}</option></select></label></template>
      <label v-else class="wide-field">{{ t('interview.resumeText') }}<textarea v-model.trim="resumeText" rows="8" required :placeholder="t('interview.resumeTextPlaceholder')" /></label>
      <label class="wide-field">{{ t('interview.jobSelection') }}<select v-model="jobId" :disabled="optionsLoading"><option value="">{{ t('interview.noJobOption') }}</option><option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <button class="btn-neon btn-primary" :disabled="optionsLoading || starting"><Play :size="16" />{{ starting ? t('interview.starting') : t('interview.startButton') }}</button>
    </form>

    <!-- ==================== 会话阶段 ==================== -->
    <div v-else class="interview-session">
      <!-- 进度与模式标识 -->
      <div class="session-toolbar">
        <span class="mode-badge" :class="sessionState?.executionMode?.toLowerCase()">{{ executionModeLabel }}</span>
        <span class="progress-badge">{{ progressText }}</span>
      </div>

      <!-- AI 加载中 -->
      <section v-if="isAiLoading" class="question-stage ai-loading">
        <div class="ai-loading-indicator">
          <Loader2 :size="28" class="spin" />
          <p class="section-kicker">{{ aiLoadingLabel }}</p>
          <p class="ai-loading-hint">{{ t('interview.aiLoadingHint') }}</p>
        </div>
      </section>

      <!-- AI 失败面板 -->
      <section v-else-if="isAiFailed" class="question-stage ai-failed">
        <header class="ai-failure-header">
          <AlertTriangle :size="22" />
          <div>
            <p class="section-kicker">{{ t('interview.aiFailedTitle') }}</p>
            <h2>{{ t('interview.aiFailedDescription') }}</h2>
          </div>
        </header>
        <div class="ai-failure-actions">
          <button v-if="aiFailure?.retryable" class="btn-neon btn-secondary" type="button" :disabled="retrying" @click="retry">
            <RefreshCw :size="16" />{{ retrying ? t('interview.retrying') : t('interview.retryAi') }}
          </button>
          <button class="btn-neon btn-primary" type="button" :disabled="fallingBack" @click="fallbackToRules">
            <Shield :size="16" />{{ fallingBack ? t('interview.fallingBack') : t('interview.continueWithRules') }}
          </button>
          <a v-if="aiFailure?.reauthorizationRequired" class="btn-neon btn-ghost" :href="reauthorizationHref">
            {{ t('interview.reauthorize') }}
          </a>
        </div>
      </section>

      <section v-else-if="isCompleted" class="question-stage completed" aria-live="polite">
        <p class="section-kicker">{{ t('interview.routeReview') }}</p>
        <h2>{{ t('interview.interviewComplete') }}</h2>
      </section>

      <!-- 正常问题展示 -->
      <section v-else-if="question" class="question-stage">
        <p class="section-kicker">{{ t('interview.currentQuestion') }}</p>
        <h2>{{ question }}</h2>
        <form v-if="isAwaitingAnswer" @submit.prevent="submit">
          <label>{{ t('interview.yourAnswer') }}<textarea v-model="answer" rows="7" required :placeholder="t('interview.answerPlaceholder')" /></label>
          <button class="btn-neon btn-primary" :disabled="submitting || !answer.trim()"><Send :size="16" />{{ submitting ? t('interview.submitting') : t('interview.submitAnswer') }}</button>
        </form>
      </section>

      <!-- 上一轮评估反馈 -->
      <article v-if="lastEval" class="feedback-panel">
        <header>
          <div><p class="section-kicker">{{ t('interview.roundFeedback') }} #{{ lastEval.roundNo }}</p><h2>{{ t('interview.feedbackTitle') }}</h2></div>
          <strong>{{ lastEval.roundScore }}</strong>
        </header>
        <!-- 五维评分 -->
        <div class="dimension-scores">
          <div class="dimension-item" v-for="dim in dimensionLabels(lastEval.dimensionScores)" :key="dim.key">
            <span class="dimension-label">{{ dim.label }}</span>
            <div class="dimension-bar"><div class="dimension-fill" :style="{ width: dim.percent + '%' }" /></div>
            <span class="dimension-value">{{ dim.value }}</span>
          </div>
        </div>
        <div class="feedback-columns">
          <section><h3><CheckCircle2 :size="15" />{{ t('interview.feedbackStrengths') }}</h3><ul><li v-for="item in lastEval.strengths" :key="item">{{ item }}</li></ul></section>
          <section><h3><Sparkles :size="15" />{{ t('interview.feedbackImprovements') }}</h3><ul><li v-for="item in lastEval.improvements" :key="item">{{ item }}</li></ul></section>
        </div>
        <div class="feedback-actions">
          <button v-if="!savedRecordIds.includes(lastEval.recordId)" class="btn-neon btn-secondary" type="button" :disabled="savingAsset" @click="saveAsset">
            <BookmarkPlus :size="16" />{{ savingAsset ? t('interview.saving') : t('interview.saveAsset') }}
          </button>
          <span v-else class="saved-state"><CheckCircle2 :size="14" />{{ t('interview.saved') }}</span>
        </div>
      </article>

      <!-- 结束面试按钮 -->
      <div v-if="canFinish && !isCompleted" class="session-actions">
        <button class="btn-neon btn-ghost" type="button" :disabled="finishing" @click="finish">
          {{ finishing ? t('interview.finishing') : t('interview.finishInterview') }}
        </button>
      </div>

      <!-- 报告触发 -->
      <button v-if="isCompleted" class="btn-neon btn-ghost report-trigger" type="button" @click="loadReport">
        <BarChart3 :size="16" />{{ t('interview.reportTitle') }}
      </button>

      <!-- 报告 -->
      <article v-if="report" class="interview-report">
        <header>
          <div><p class="section-kicker">{{ t('interview.reviewEyebrow') }}</p><h2>{{ t('interview.reportTitle') }}</h2></div>
          <strong><small>{{ t('interview.totalScore') }}</small>{{ report.totalScore }}</strong>
        </header>
        <p>{{ report.summary }}</p>
        <div class="report-meta">
          <span>{{ t('interview.reportTargetCount') }}: {{ report.targetQuestionCount }}</span>
          <span>{{ t('interview.reportActualCount') }}: {{ report.actualQuestionCount }}</span>
          <span v-if="report.evaluationSource">{{ t('interview.reportEvalSource') }}: {{ report.evaluationSource === 'AI' ? t('interview.modeAi') : report.evaluationSource === 'MIXED' ? t('interview.modeMixed') : t('interview.modeRule') }}</span>
        </div>
        <div v-if="report.dimensionScores" class="dimension-scores">
          <div class="dimension-item" v-for="dim in dimensionLabels(report.dimensionScores)" :key="dim.key">
            <span class="dimension-label">{{ dim.label }}</span>
            <div class="dimension-bar"><div class="dimension-fill" :style="{ width: dim.percent + '%' }" /></div>
            <span class="dimension-value">{{ dim.value }}</span>
          </div>
        </div>
        <div class="report-sections">
          <section><h3>{{ t('interview.feedbackStrengths') }}</h3><ul><li v-for="item in report.strengths" :key="item">{{ item }}</li></ul></section>
          <section><h3>{{ t('interview.feedbackImprovements') }}</h3><ul><li v-for="item in report.weaknesses" :key="item">{{ item }}</li></ul></section>
          <section><h3>{{ t('interview.resumeSuggestions') }}</h3><ul><li v-for="item in report.resumeSuggestions" :key="item">{{ item }}</li></ul></section>
          <section><h3>{{ t('interview.expressionSuggestions') }}</h3><ul><li v-for="item in report.expressionSuggestions" :key="item">{{ item }}</li></ul></section>
        </div>
      </article>
    </div>
  </section>
</template>

<style scoped>
.interview-page { width: min(100%, 980px); max-width: 980px; gap: 24px; }
.interview-heading { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 28px; padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.interview-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.interview-heading .page-lead { max-width: 640px; font-size: 12px; }
.interview-route { display: flex; align-items: center; gap: 8px; padding-bottom: 3px; }
.interview-route span { display: grid; justify-items: center; gap: 5px; color: var(--text-tertiary); font-size: 9px; white-space: nowrap; }
.interview-route b { display: grid; width: 26px; height: 26px; place-items: center; border: 1px solid var(--border); border-radius: 50%; font-family: var(--font-utility); font-size: 9px; }
.interview-route span.active { color: var(--accent); font-weight: 700; }
.interview-route span.active b { border-color: var(--accent); color: white; background: var(--accent); }
.interview-route i { width: 24px; height: 1px; background: var(--border); }
.interview-setup { display: grid; grid-template-columns: 1fr 1fr; gap: 14px 16px; padding: 24px; border: 1px solid var(--border); border-left: 4px solid var(--highlight); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.interview-section-heading { grid-column: 1 / -1; display: grid; grid-template-columns: 40px minmax(0, 1fr); gap: 12px; padding-bottom: 18px; border-bottom: 1px solid var(--border-soft); }
.interview-section-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--accent); background: var(--accent-light); }
.interview-section-heading p, .interview-section-heading h2, .interview-section-heading small { display: block; margin: 0; }
.interview-section-heading p, .section-kicker { margin: 0 0 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.interview-section-heading h2 { color: var(--text-primary); font-size: 16px; }
.interview-section-heading small { margin-top: 5px; color: var(--text-secondary); font-size: 10px; }
.interview-setup label, .question-stage label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.interview-setup .wide-field { grid-column: 1 / -1; }
.interview-setup select, .interview-setup textarea, .interview-setup input[type="number"], .question-stage textarea { width: 100%; padding: 10px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font: inherit; font-size: 12px; resize: vertical; }
.interview-setup select:focus, .interview-setup textarea:focus, .interview-setup input[type="number"]:focus, .question-stage textarea:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.interview-setup > .btn-neon { grid-column: 1 / -1; justify-self: end; }
.field-hint { grid-column: 1 / -1; color: var(--text-tertiary); font-size: 10px; margin-top: -6px; }
.interview-session { display: grid; gap: 18px; }
.session-toolbar { display: flex; align-items: center; gap: 10px; }
.mode-badge { padding: 3px 10px; border-radius: 12px; font-size: 10px; font-weight: 700; font-family: var(--font-utility); }
.mode-badge.ai { background: var(--accent-light); color: var(--accent); }
.mode-badge.rule { background: var(--highlight-light, #fef3c7); color: var(--highlight, #d97706); }
.progress-badge { padding: 3px 10px; border: 1px solid var(--border); border-radius: 12px; font-size: 10px; color: var(--text-secondary); font-family: var(--font-utility); }
.question-stage, .feedback-panel, .interview-report { padding: 24px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.question-stage { border-top: 4px solid var(--info); }
.question-stage.completed { border-top-color: var(--success); }
.question-stage > h2 { margin: 4px 0 20px; max-width: 760px; font-family: var(--font-display); font-size: 22px; line-height: 1.35; letter-spacing: 0; }
.question-stage form { display: grid; gap: 12px; }
.question-stage .btn-neon { justify-self: end; }
/* AI loading */
.question-stage.ai-loading { border-top-color: var(--accent); }
.ai-loading-indicator { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 30px 0; text-align: center; }
.ai-loading-indicator .section-kicker { font-size: 13px; color: var(--accent); }
.ai-loading-hint { font-size: 11px; color: var(--text-tertiary); }
.spin { animation: spin 1.2s linear infinite; color: var(--accent); }
@keyframes spin { to { transform: rotate(360deg); } }
/* AI failure */
.question-stage.ai-failed { border-top-color: var(--error, #ef4444); }
.ai-failure-header { display: flex; align-items: flex-start; gap: 14px; }
.ai-failure-header > svg { color: var(--error, #ef4444); flex-shrink: 0; margin-top: 2px; }
.ai-failure-header h2 { font-size: 14px; margin: 2px 0 0; color: var(--text-primary); }
.ai-failure-actions { display: flex; gap: 10px; margin-top: 18px; justify-content: flex-end; }
/* Feedback */
.feedback-panel > header, .interview-report > header { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-bottom: 14px; border-bottom: 1px solid var(--border-soft); }
.feedback-panel header h2, .interview-report header h2 { margin: 0; font-size: 16px; }
.feedback-panel header > strong { color: var(--accent); font-family: var(--font-utility); font-size: 28px; }
/* Dimension scores */
.dimension-scores { display: grid; gap: 8px; padding: 14px 0; border-bottom: 1px solid var(--border-soft); }
.dimension-item { display: grid; grid-template-columns: 120px 1fr 36px; align-items: center; gap: 8px; }
.dimension-label { font-size: 10px; color: var(--text-tertiary); font-weight: 600; }
.dimension-bar { height: 6px; border-radius: 3px; background: var(--border-soft); overflow: hidden; }
.dimension-fill { height: 100%; border-radius: 3px; background: var(--accent); transition: width 0.4s ease; }
.dimension-value { font-size: 10px; font-weight: 700; color: var(--text-secondary); text-align: right; font-family: var(--font-utility); }
.feedback-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; padding-top: 16px; }
.feedback-columns section { padding: 14px 16px; border: 1px solid var(--border-soft); border-radius: 6px; background: var(--bg-page); }
.feedback-columns h3 { display: flex; align-items: center; gap: 6px; margin: 0 0 9px; color: var(--success); font-size: 11px; }
.feedback-columns section:last-child h3 { color: var(--highlight); }
.feedback-columns ul, .interview-report ul { display: grid; gap: 6px; margin: 0; padding-left: 18px; color: var(--text-secondary); font-size: 10px; line-height: 1.55; }
.feedback-actions { display: flex; justify-content: flex-end; margin-top: 14px; }
.saved-state { display: inline-flex; align-items: center; gap: 5px; color: var(--success); font-size: 10px; font-weight: 700; }
.session-actions { display: flex; justify-content: center; }
.report-trigger { justify-self: start; }
.interview-report { border-left: 4px solid var(--accent); }
.interview-report header > strong { display: grid; justify-items: end; color: var(--accent); font-family: var(--font-utility); font-size: 28px; }
.interview-report header small { color: var(--text-tertiary); font-size: 8px; }
.interview-report > p { color: var(--text-secondary); font-size: 11px; line-height: 1.65; }
.report-meta { display: flex; gap: 16px; padding: 10px 0; border-bottom: 1px solid var(--border-soft); font-size: 10px; color: var(--text-tertiary); font-family: var(--font-utility); }
.interview-report section { margin-top: 18px; }
.interview-report h3 { margin: 0 0 10px; font-size: 12px; }
.report-sections { display: grid; grid-template-columns: 1fr 1fr; gap: 0 20px; }
@media (max-width: 760px) { .interview-heading { grid-template-columns: 1fr; } .interview-route { justify-content: flex-start; } .interview-setup { grid-template-columns: 1fr; padding: 20px 16px; } .interview-section-heading, .interview-setup .wide-field, .interview-setup > .btn-neon { grid-column: auto; } .interview-setup > .btn-neon, .question-stage .btn-neon { width: 100%; justify-content: center; } .feedback-columns, .report-sections { grid-template-columns: 1fr; } .question-stage, .feedback-panel, .interview-report { padding: 20px 16px; } .dimension-item { grid-template-columns: 90px 1fr 30px; } }
@media (max-width: 480px) { .interview-heading h1 { font-size: 29px; } .interview-route { width: 100%; } .interview-route i { flex: 1; min-width: 8px; } }
</style>
