<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { BarChart3, BookmarkPlus, CheckCircle2, FileText, Mic2, Play, Send, Sparkles } from 'lucide-vue-next'
import { createInterviewAsset } from '@/api/interviewAsset'
import { answerInterview, getInterviewReport, startInterview } from '@/api/interview'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const sourceType = ref<'PLATFORM_RESUME' | 'EXTERNAL_RESUME'>('EXTERNAL_RESUME')
const interviewMode = ref<'JD_TARGETED' | 'TECHNICAL' | 'BEHAVIORAL' | 'COMPREHENSIVE'>('TECHNICAL')
const jobId = ref('')
const resumeVersionId = ref('')
const resumeText = ref('')
const interviewId = ref<number | null>(null)
const question = ref('')
const answer = ref('')
const feedback = ref<{ recordId: number; questionText: string; answerText: string; score: number; strengths: string[]; improvements: string[] } | null>(null)
const report = ref<{ totalScore: number; summary: string; resumeSuggestions: string[] } | null>(null)
const savingAsset = ref(false)
const starting = ref(false)
const submitting = ref(false)
const savedRecordIds = ref<number[]>([])
const error = ref('')
const { resumes, jobs, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load: loadOptions, loadVersions } = useResumeJobOptions()

async function start() {
  error.value = ''
  if (sourceType.value === 'PLATFORM_RESUME' && !resumeVersionId.value) { error.value = t('interview.selectVersionFirst'); return }
  starting.value = true
  try {
    const result = (await startInterview({
      sourceType: sourceType.value,
      resumeVersionId: sourceType.value === 'PLATFORM_RESUME' ? Number(resumeVersionId.value) : undefined,
      externalResumeText: sourceType.value === 'EXTERNAL_RESUME' ? resumeText.value : undefined,
      jobDescriptionId: Number(jobId.value),
      interviewMode: interviewMode.value,
    })).data.data
    interviewId.value = result.interviewId
    question.value = result.firstQuestion
  } catch { error.value = t('interview.startError') }
  finally { starting.value = false }
}

async function submit() {
  if (interviewId.value === null || !answer.value.trim()) return
  error.value = ''
  submitting.value = true
  try {
    const result = (await answerInterview(interviewId.value, answer.value)).data.data
    feedback.value = { recordId: result.recordId, questionText: result.questionText, answerText: answer.value.trim(), score: result.roundScore, ...result.feedback }
    answer.value = ''
    question.value = result.nextQuestion ?? t('interview.interviewComplete')
  } catch { error.value = t('interview.submitError') }
  finally { submitting.value = false }
}

function versionSourceLabel(source: string) {
  const labels: Record<string, string> = {
    MANUAL: t('interview.sourceManual'), AI_OPTIMIZED: t('interview.sourceAiOptimized'),
    JD_CUSTOMIZED: t('interview.sourceJdCustomized'), MATERIAL_CUSTOMIZED: t('interview.sourceMaterialCustomized'),
    RESTORED: t('interview.sourceRestored'),
  }
  return labels[source] ?? t('interview.sourceOther')
}

async function saveAsset() {
  if (!feedback.value) return
  savingAsset.value = true; error.value = ''
  try {
    await createInterviewAsset({ interviewRecordId: feedback.value.recordId, questionText: feedback.value.questionText, originalAnswerText: feedback.value.answerText, suggestedAnswerText: feedback.value.improvements.join(' ') })
    savedRecordIds.value.push(feedback.value.recordId)
  } catch { error.value = t('interview.saveAssetError') }
  finally { savingAsset.value = false }
}

async function loadReport() {
  if (interviewId.value === null) return
  try { report.value = (await getInterviewReport(interviewId.value)).data.data }
  catch { error.value = t('interview.loadReportError') }
}

onMounted(() => { void loadOptions() })
</script>

<template>
  <section class="workspace-page interview-page">
    <header class="interview-heading">
      <div><p class="eyebrow"><Mic2 :size="14" />{{ t('interview.eyebrow') }}</p><h1>{{ t('interview.title') }}</h1><p class="page-lead">{{ t('interview.subtitle') }}</p></div>
      <div class="interview-route" :aria-label="t('interview.progressLabel')"><span class="active"><b>1</b>{{ t('interview.routePrepare') }}</span><i /><span :class="{ active: interviewId !== null }"><b>2</b>{{ t('interview.routePractice') }}</span><i /><span :class="{ active: report }"><b>3</b>{{ t('interview.routeReview') }}</span></div>
    </header>
    <p v-if="error || optionsError" class="form-error" role="alert">{{ error || optionsError }}</p>
    <form v-if="interviewId === null" class="interview-setup" @submit.prevent="start">
      <header class="interview-section-heading"><span><FileText :size="19" /></span><div><p>{{ t('interview.setupEyebrow') }}</p><h2>{{ t('interview.setupTitle') }}</h2><small>{{ t('interview.setupDescription') }}</small></div></header>
      <label>{{ t('interview.sourceType') }}<select v-model="sourceType"><option value="EXTERNAL_RESUME">{{ t('interview.externalResume') }}</option><option value="PLATFORM_RESUME">{{ t('interview.platformResume') }}</option></select></label>
      <label>{{ t('interview.interviewMode') }}<select v-model="interviewMode"><option value="TECHNICAL">{{ t('interview.modeTechnical') }}</option><option value="BEHAVIORAL">{{ t('interview.modeBehavioral') }}</option><option value="JD_TARGETED">{{ t('interview.modeJdTargeted') }}</option><option value="COMPREHENSIVE">{{ t('interview.modeComprehensive') }}</option></select></label>
      <template v-if="sourceType === 'PLATFORM_RESUME'"><label>{{ t('common.selectResume') }}<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>{{ t('common.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label><label>{{ t('common.selectVersion') }}<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required><option value="" disabled>{{ t('common.selectVersion') }}</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} &middot; {{ versionSourceLabel(version.sourceType) }}</option></select></label></template>
      <label v-else class="wide-field">{{ t('interview.resumeText') }}<textarea v-model.trim="resumeText" rows="8" required :placeholder="t('interview.resumeTextPlaceholder')" /></label>
      <label class="wide-field">{{ t('common.selectJob') }}<select v-model="jobId" :disabled="optionsLoading" required><option value="" disabled>{{ t('common.selectJob') }}</option><option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <button class="btn-neon btn-primary" :disabled="optionsLoading || starting"><Play :size="16" />{{ starting ? t('interview.starting') : t('interview.startButton') }}</button>
    </form>
    <div v-else class="interview-session">
      <section class="question-stage"><p class="section-kicker">{{ t('interview.currentQuestion') }}</p><h2>{{ question }}</h2><form @submit.prevent="submit"><label>{{ t('interview.yourAnswer') }}<textarea v-model="answer" rows="7" required :placeholder="t('interview.answerPlaceholder')" /></label><button class="btn-neon btn-primary" :disabled="submitting"><Send :size="16" />{{ submitting ? t('interview.submitting') : t('interview.submitAnswer') }}</button></form></section>
      <article v-if="feedback" class="feedback-panel"><header><div><p class="section-kicker">{{ t('interview.roundFeedback') }}</p><h2>{{ t('interview.feedbackTitle') }}</h2></div><strong>{{ feedback.score }}</strong></header><div class="feedback-columns"><section><h3><CheckCircle2 :size="15" />{{ t('interview.feedbackStrengths') }}</h3><ul><li v-for="item in feedback.strengths" :key="item">{{ item }}</li></ul></section><section><h3><Sparkles :size="15" />{{ t('interview.feedbackImprovements') }}</h3><ul><li v-for="item in feedback.improvements" :key="item">{{ item }}</li></ul></section></div><div class="feedback-actions"><button v-if="!savedRecordIds.includes(feedback.recordId)" class="btn-neon btn-secondary" type="button" :disabled="savingAsset" @click="saveAsset"><BookmarkPlus :size="16" />{{ savingAsset ? t('interview.saving') : t('interview.saveAsset') }}</button><span v-else class="saved-state"><CheckCircle2 :size="14" />{{ t('interview.saved') }}</span></div></article>
      <button class="btn-neon btn-ghost report-trigger" type="button" @click="loadReport"><BarChart3 :size="16" />{{ t('interview.reportTitle') }}</button>
      <article v-if="report" class="interview-report"><header><div><p class="section-kicker">{{ t('interview.reviewEyebrow') }}</p><h2>{{ t('interview.reportTitle') }}</h2></div><strong><small>{{ t('interview.totalScore') }}</small>{{ report.totalScore }}</strong></header><p>{{ report.summary }}</p><section><h3>{{ t('interview.resumeSuggestions') }}</h3><ul><li v-for="item in report.resumeSuggestions" :key="item">{{ item }}</li></ul></section></article>
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
.interview-setup select, .interview-setup textarea, .question-stage textarea { width: 100%; padding: 10px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font: inherit; font-size: 12px; resize: vertical; }
.interview-setup select:focus, .interview-setup textarea:focus, .question-stage textarea:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.interview-setup > .btn-neon { grid-column: 1 / -1; justify-self: end; }
.interview-session { display: grid; gap: 18px; }
.question-stage, .feedback-panel, .interview-report { padding: 24px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.question-stage { border-top: 4px solid var(--info); }
.question-stage > h2 { margin: 4px 0 20px; max-width: 760px; font-family: var(--font-display); font-size: 22px; line-height: 1.35; letter-spacing: 0; }
.question-stage form { display: grid; gap: 12px; }
.question-stage .btn-neon { justify-self: end; }
.feedback-panel > header, .interview-report > header { display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-bottom: 14px; border-bottom: 1px solid var(--border-soft); }
.feedback-panel header h2, .interview-report header h2 { margin: 0; font-size: 16px; }
.feedback-panel header > strong { color: var(--accent); font-family: var(--font-utility); font-size: 28px; }
.feedback-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 18px; padding-top: 16px; }
.feedback-columns section { padding: 14px 16px; border: 1px solid var(--border-soft); border-radius: 6px; background: var(--bg-page); }
.feedback-columns h3 { display: flex; align-items: center; gap: 6px; margin: 0 0 9px; color: var(--success); font-size: 11px; }
.feedback-columns section:last-child h3 { color: var(--highlight); }
.feedback-columns ul, .interview-report ul { display: grid; gap: 6px; margin: 0; padding-left: 18px; color: var(--text-secondary); font-size: 10px; line-height: 1.55; }
.feedback-actions { display: flex; justify-content: flex-end; margin-top: 14px; }
.saved-state { display: inline-flex; align-items: center; gap: 5px; color: var(--success); font-size: 10px; font-weight: 700; }
.report-trigger { justify-self: start; }
.interview-report { border-left: 4px solid var(--accent); }
.interview-report header > strong { display: grid; justify-items: end; color: var(--accent); font-family: var(--font-utility); font-size: 28px; }
.interview-report header small { color: var(--text-tertiary); font-size: 8px; }
.interview-report > p { color: var(--text-secondary); font-size: 11px; line-height: 1.65; }
.interview-report section { margin-top: 18px; }
.interview-report h3 { margin: 0 0 10px; font-size: 12px; }
@media (max-width: 760px) { .interview-heading { grid-template-columns: 1fr; } .interview-route { justify-content: flex-start; } .interview-setup { grid-template-columns: 1fr; padding: 20px 16px; } .interview-section-heading, .interview-setup .wide-field, .interview-setup > .btn-neon { grid-column: auto; } .interview-setup > .btn-neon, .question-stage .btn-neon { width: 100%; justify-content: center; } .feedback-columns { grid-template-columns: 1fr; } .question-stage, .feedback-panel, .interview-report { padding: 20px 16px; } }
@media (max-width: 480px) { .interview-heading h1 { font-size: 29px; } .interview-route { width: 100%; } .interview-route i { flex: 1; min-width: 8px; } }
</style>
