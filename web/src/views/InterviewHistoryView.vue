<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, BarChart3, CalendarDays, FileClock, Search } from 'lucide-vue-next'
import { getInterviewReport, listInterviewHistory, type InterviewReportResponse, type InterviewSessionSummary } from '@/api/interview'
import { listJobs, type JobDescription } from '@/api/jobDescription'
import { useLocale } from '@/i18n'
import { useRouter } from 'vue-router'

const { locale, t } = useLocale()
const router = useRouter()
const sessions = ref<InterviewSessionSummary[]>([])
const jobs = ref<JobDescription[]>([])
const jobDescriptionId = ref<number | null>(null)
const loading = ref(false)
const error = ref('')

const selectedReport = ref<InterviewReportResponse | null>(null)
const selectedSession = ref<InterviewSessionSummary | null>(null)
const reportLoading = ref(false)

const filteredSessions = computed(() => sessions.value)

function formatDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(locale.value, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}

function jobTitle(id: number | null) {
  if (id === null) return t('history.noJob')
  return jobs.value.find((job) => job.id === id)?.title || `${t('history.jobRef')} #${id}`
}

function sourceLabel(source: string) {
  return source === 'PLATFORM_RESUME' ? t('interview.platformResume') : t('interview.externalResume')
}

function modeLabel(mode: string) {
  const labels: Record<string, string> = {
    JD_TARGETED: t('interview.modeJdTargeted'),
    TECHNICAL: t('interview.modeTechnical'),
    BEHAVIORAL: t('interview.modeBehavioral'),
    COMPREHENSIVE: t('interview.modeComprehensive'),
  }
  return labels[mode] ?? mode
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    sessions.value = (await listInterviewHistory({ jobDescriptionId: jobDescriptionId.value ?? undefined })).data.data
  } catch {
    error.value = t('history.loadError')
  } finally {
    loading.value = false
  }
}

async function openReport(session: InterviewSessionSummary) {
  reportLoading.value = true
  error.value = ''
  selectedSession.value = session
  selectedReport.value = null
  try {
    selectedReport.value = (await getInterviewReport(session.id)).data.data
  } catch {
    error.value = t('history.reportError')
  } finally {
    reportLoading.value = false
  }
}

function backToList() {
  selectedReport.value = null
  selectedSession.value = null
}

onMounted(async () => {
  void load()
  try {
    jobs.value = (await listJobs()).data.data
  } catch {
    // JD 列表加载失败不阻塞历史列表
  }
})
</script>

<template>
  <section class="workspace-page history-page">
    <header class="history-heading">
      <button class="history-back" type="button" @click="router.push({ name: 'interviews' })">
        <ArrowLeft :size="15" />{{ t('history.backToInterview') }}
      </button>
      <div>
        <p class="eyebrow"><FileClock :size="14" />{{ t('history.eyebrow') }}</p>
        <h1>{{ t('history.title') }}</h1>
        <p class="page-lead">{{ t('history.subtitle') }}</p>
      </div>
    </header>

    <div v-if="selectedSession" class="report-view">
      <button class="btn-neon btn-ghost" type="button" @click="backToList"><ArrowLeft :size="15" />{{ t('history.backToList') }}</button>
      <article v-if="reportLoading" class="workspace-card report-card">{{ t('history.loading') }}</article>
      <article v-else-if="selectedReport" class="workspace-card report-card">
        <header class="report-header">
          <div>
            <p class="section-kicker">{{ t('history.reportEyebrow') }}</p>
            <h2>{{ t('interview.reportTitle') }} · {{ jobTitle(selectedSession.jobDescriptionId) }}</h2>
            <small>{{ formatDate(selectedSession.updatedAt) }}</small>
          </div>
          <strong><small>{{ t('interview.totalScore') }}</small>{{ selectedReport.totalScore }}</strong>
        </header>
        <p class="report-summary">{{ selectedReport.summary }}</p>
        <div class="report-meta">
          <span>{{ t('interview.reportTargetCount') }}: {{ selectedReport.targetQuestionCount }}</span>
          <span>{{ t('interview.reportActualCount') }}: {{ selectedReport.actualQuestionCount }}</span>
          <span v-if="selectedReport.evaluationSource">{{ t('interview.reportEvalSource') }}: {{ selectedReport.evaluationSource === 'AI' ? t('interview.modeAi') : selectedReport.evaluationSource === 'MIXED' ? t('interview.modeMixed') : t('interview.modeRule') }}</span>
        </div>

        <div class="report-sections">
          <section><h3>{{ t('interview.feedbackStrengths') }}</h3><ul><li v-for="item in selectedReport.strengths" :key="item">{{ item }}</li></ul></section>
          <section><h3>{{ t('interview.feedbackImprovements') }}</h3><ul><li v-for="item in selectedReport.weaknesses" :key="item">{{ item }}</li></ul></section>
          <section><h3>{{ t('interview.resumeSuggestions') }}</h3><ul><li v-for="item in selectedReport.resumeSuggestions" :key="item">{{ item }}</li></ul></section>
          <section><h3>{{ t('interview.expressionSuggestions') }}</h3><ul><li v-for="item in selectedReport.expressionSuggestions" :key="item">{{ item }}</li></ul></section>
        </div>

        <div v-if="selectedReport.rounds.length" class="rounds-block">
          <h3>{{ t('history.roundsTitle') }}</h3>
          <details v-for="round in selectedReport.rounds" :key="round.roundNo" class="round-card">
            <summary>
              <span class="round-no">#{{ round.roundNo }}</span>
              <span class="round-question">{{ round.questionText }}</span>
              <span class="round-score">{{ round.roundScore }}</span>
            </summary>
            <div class="round-body">
              <p><strong>{{ t('interview.yourAnswer') }}</strong>{{ round.answerText }}</p>
              <p v-if="round.suggestedAnswer"><strong>{{ t('history.suggestedAnswer') }}</strong>{{ round.suggestedAnswer }}</p>
              <div v-if="round.strengths.length" class="round-feedback">
                <strong>{{ t('interview.feedbackStrengths') }}</strong><ul><li v-for="item in round.strengths" :key="item">{{ item }}</li></ul>
              </div>
              <div v-if="round.improvements.length" class="round-feedback">
                <strong>{{ t('interview.feedbackImprovements') }}</strong><ul><li v-for="item in round.improvements" :key="item">{{ item }}</li></ul>
              </div>
            </div>
          </details>
        </div>
      </article>
    </div>

    <template v-else>
      <form class="history-filters" @submit.prevent="load">
        <label>{{ t('history.jobFilter') }}<select v-model="jobDescriptionId"><option :value="null">{{ t('history.allJobs') }}</option><option v-for="job in jobs" :key="job.id" :value="job.id">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
        <button class="btn-neon btn-ghost" :disabled="loading"><Search :size="16" />{{ t('history.filter') }}</button>
      </form>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <p v-if="loading" class="empty-state">{{ t('history.loading') }}</p>
      <p v-else-if="!filteredSessions.length" class="empty-state">{{ t('history.empty') }}</p>
      <div v-else class="workspace-list">
        <article v-for="session in filteredSessions" :key="session.id" class="workspace-card session-row" @click="openReport(session)">
          <div class="session-main">
            <h2>{{ jobTitle(session.jobDescriptionId) }}</h2>
            <div class="session-meta">
              <span>{{ sourceLabel(session.sourceType) }}</span>
              <span>{{ modeLabel(session.interviewMode) }}</span>
              <span>{{ session.actualQuestionCount }}/{{ session.targetQuestionCount }} {{ t('history.questions') }}</span>
              <span>{{ t('history.score') }}: {{ session.totalScore }}</span>
            </div>
          </div>
          <div class="session-date"><CalendarDays :size="14" />{{ formatDate(session.updatedAt) }}</div>
          <BarChart3 :size="17" class="session-arrow" />
        </article>
      </div>
    </template>
  </section>
</template>

<style scoped>
.history-page { width: min(100%, 920px); max-width: 920px; gap: 24px; }
.history-heading { display: grid; gap: 10px; padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.history-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.history-heading .page-lead { max-width: 650px; font-size: 12px; }
.history-back { display: inline-flex; align-items: center; gap: 6px; justify-self: start; color: var(--text-secondary); font-size: 11px; font-weight: 650; background: transparent; border: 0; cursor: pointer; }
.history-back:hover { color: var(--accent); }
.history-filters { display: grid; grid-template-columns: 1fr auto; align-items: end; gap: 12px; padding: 14px 16px; border-block: 1px solid var(--border-soft); }
.history-filters label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.history-filters select { width: 100%; padding: 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 11px; }
.session-row { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 14px; padding: 16px 18px; cursor: pointer; }
.session-row:hover { border-left-color: var(--accent); }
.session-main { display: grid; gap: 6px; min-width: 0; }
.session-main h2 { margin: 0; font-size: 14px; }
.session-meta { display: flex; flex-wrap: wrap; gap: 6px; }
.session-meta span { padding: 3px 7px; border: 1px solid var(--border); border-radius: 4px; color: var(--text-secondary); font-size: 9px; }
.session-date { display: inline-flex; align-items: center; gap: 5px; color: var(--text-tertiary); font-size: 10px; }
.session-arrow { color: var(--text-tertiary); }
.report-view { display: grid; gap: 16px; }
.report-card { display: grid; gap: 14px; padding: 24px; border-left: 4px solid var(--accent); }
.report-header { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-bottom: 14px; border-bottom: 1px solid var(--border-soft); }
.report-header h2 { margin: 3px 0 4px; font-size: 16px; }
.report-header small { color: var(--text-tertiary); font-size: 9px; }
.report-header > strong { color: var(--accent); font-family: var(--font-utility); font-size: 28px; }
.report-header > strong small { display: block; color: var(--text-tertiary); font-size: 8px; text-align: right; }
.report-summary { margin: 0; color: var(--text-secondary); font-size: 11px; line-height: 1.65; }
.report-meta { display: flex; gap: 16px; padding: 10px 0; border-bottom: 1px solid var(--border-soft); font-size: 10px; color: var(--text-tertiary); font-family: var(--font-utility); }
.report-sections { display: grid; grid-template-columns: 1fr 1fr; gap: 0 20px; }
.report-sections h3, .rounds-block h3 { margin: 0 0 10px; font-size: 12px; }
.report-sections ul { display: grid; gap: 6px; margin: 0; padding-left: 18px; color: var(--text-secondary); font-size: 10px; line-height: 1.55; }
.rounds-block { display: grid; gap: 10px; padding-top: 6px; }
.round-card { border: 1px solid var(--border); border-radius: 6px; background: var(--bg-page); }
.round-card summary { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; align-items: center; gap: 10px; padding: 11px 14px; cursor: pointer; list-style: none; }
.round-card summary::-webkit-details-marker { display: none; }
.round-no { color: var(--accent); font-family: var(--font-utility); font-size: 11px; font-weight: 700; }
.round-question { overflow: hidden; color: var(--text-primary); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.round-score { color: var(--accent); font-family: var(--font-utility); font-size: 13px; font-weight: 700; }
.round-body { display: grid; gap: 10px; padding: 4px 14px 14px; border-top: 1px solid var(--border-soft); }
.round-body p { display: grid; gap: 4px; margin: 0; color: var(--text-secondary); font-size: 11px; line-height: 1.6; white-space: pre-wrap; }
.round-body p strong { color: var(--text-primary); font-size: 10px; }
.round-feedback { display: grid; gap: 4px; }
.round-feedback strong { color: var(--text-primary); font-size: 10px; }
.round-feedback ul { display: grid; gap: 4px; margin: 0; padding-left: 16px; color: var(--text-secondary); font-size: 10px; }
@media (max-width: 680px) { .history-heading h1 { font-size: 29px; } .history-filters { grid-template-columns: 1fr; } .history-filters .btn-neon { width: 100%; justify-content: center; } .session-row { grid-template-columns: 1fr auto; } .report-sections { grid-template-columns: 1fr; } .report-card { padding: 20px 16px; } }
</style>
