<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
const savedRecordIds = ref<number[]>([])
const error = ref('')
const { resumes, jobs, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load: loadOptions, loadVersions } = useResumeJobOptions()

async function start() {
  error.value = ''
  if (sourceType.value === 'PLATFORM_RESUME' && !resumeVersionId.value) { error.value = t('interview.selectVersionFirst'); return }
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
}

async function submit() {
  if (interviewId.value === null || !answer.value.trim()) return
  error.value = ''
  try {
    const result = (await answerInterview(interviewId.value, answer.value)).data.data
    feedback.value = { recordId: result.recordId, questionText: result.questionText, answerText: answer.value.trim(), score: result.roundScore, ...result.feedback }
    answer.value = ''
    question.value = result.nextQuestion ?? t('interview.interviewComplete')
  } catch { error.value = t('interview.submitError') }
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
  <section class="workspace-page">
    <p class="eyebrow">{{ t('interview.eyebrow') }}</p>
    <h1>{{ t('interview.eyebrow') }}</h1>
    <p>{{ t('interview.submitError') }}</p>
    <p v-if="error || optionsError" class="form-error" role="alert">{{ error || optionsError }}</p>
    <form v-if="interviewId === null" class="workspace-card" @submit.prevent="start">
      <label>{{ t('interview.sourceType') }}<select v-model="sourceType"><option value="EXTERNAL_RESUME">{{ t('interview.externalResume') }}</option><option value="PLATFORM_RESUME">{{ t('interview.platformResume') }}</option></select></label>
      <label>{{ t('interview.interviewMode') }}<select v-model="interviewMode"><option value="TECHNICAL">{{ t('interview.modeTechnical') }}</option><option value="BEHAVIORAL">{{ t('interview.modeBehavioral') }}</option><option value="JD_TARGETED">{{ t('interview.modeJdTargeted') }}</option><option value="COMPREHENSIVE">{{ t('interview.modeComprehensive') }}</option></select></label>
      <template v-if="sourceType === 'PLATFORM_RESUME'"><label>{{ t('common.selectResume') }}<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>{{ t('common.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label><label>{{ t('common.selectVersion') }}<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required><option value="" disabled>{{ t('common.selectVersion') }}</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label></template>
      <label v-else>{{ t('interview.resumeText') }}<textarea v-model.trim="resumeText" rows="10" required :placeholder="t('interview.resumeTextPlaceholder')" /></label>
      <label>{{ t('common.selectJob') }}<select v-model="jobId" :disabled="optionsLoading" required><option value="" disabled>{{ t('common.selectJob') }}</option><option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <button class="btn-neon btn-primary" :disabled="optionsLoading">{{ t('interview.startButton') }}</button>
    </form>
    <div v-else class="workspace-card">
      <h2>{{ question }}</h2>
      <form @submit.prevent="submit"><label>{{ t('interview.question') }}<textarea v-model="answer" rows="6" required :placeholder="t('interview.answerPlaceholder')" /></label><button class="btn-neon btn-primary">{{ t('interview.submitAnswer') }}</button></form>
      <article v-if="feedback" class="workspace-card">
        <strong>{{ t('interview.feedbackScore') }}: {{ feedback.score }}</strong>
        <h3>{{ t('interview.feedbackStrengths') }}</h3><ul><li v-for="item in feedback.strengths" :key="item">{{ item }}</li></ul>
        <h3>{{ t('interview.feedbackImprovements') }}</h3><ul><li v-for="item in feedback.improvements" :key="item">{{ item }}</li></ul>
        <button v-if="!savedRecordIds.includes(feedback.recordId)" class="btn-neon btn-secondary" type="button" :disabled="savingAsset" @click="saveAsset">{{ savingAsset ? t('interview.saving') : t('interview.saveAsset') }}</button>
        <span v-else class="disclaimer">{{ t('interview.saved') }}</span>
      </article>
      <button class="btn-neon btn-ghost" type="button" @click="loadReport">{{ t('interview.reportTitle') }}</button>
      <article v-if="report"><h2>{{ t('interview.totalScore') }}: {{ report.totalScore }}</h2><p>{{ report.summary }}</p><h3>{{ t('interview.resumeSuggestions') }}</h3><ul><li v-for="item in report.resumeSuggestions" :key="item">{{ item }}</li></ul></article>
    </div>
  </section>
</template>
