<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { createInterviewAsset } from '@/api/interviewAsset'
import { answerInterview, getInterviewReport, startInterview } from '@/api/interview'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'

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
  if (sourceType.value === 'PLATFORM_RESUME' && !resumeVersionId.value) {
    error.value = 'Select a platform resume version before starting.'
    return
  }
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
  } catch { error.value = 'Unable to start the interview. Confirm AI consent, a job description, and resume text.' }
}

async function submit() {
  if (interviewId.value === null || !answer.value.trim()) return
  error.value = ''
  try {
    const result = (await answerInterview(interviewId.value, answer.value)).data.data
    feedback.value = { recordId: result.recordId, questionText: result.questionText, answerText: answer.value.trim(), score: result.roundScore, ...result.feedback }
    answer.value = ''
    question.value = result.nextQuestion ?? 'Interview complete. Review the report or save the answer asset.'
  } catch { error.value = 'Unable to submit this answer.' }
}

async function saveAsset() {
  if (!feedback.value) return
  savingAsset.value = true
  error.value = ''
  try {
    await createInterviewAsset({ interviewRecordId: feedback.value.recordId, questionText: feedback.value.questionText, originalAnswerText: feedback.value.answerText, suggestedAnswerText: feedback.value.improvements.join(' ') })
    savedRecordIds.value.push(feedback.value.recordId)
  } catch { error.value = 'Unable to save this answer to your asset library.' }
  finally { savingAsset.value = false }
}

async function loadReport() {
  if (interviewId.value === null) return
  try { report.value = (await getInterviewReport(interviewId.value)).data.data }
  catch { error.value = 'Unable to load the interview report.' }
}

onMounted(() => { void loadOptions() })
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">AI interview</p><h1>Practice Interview</h1><p>Questions use only the resume text and job description you provide. Answers remain separate from AI feedback.</p>
    <p v-if="error || optionsError" class="form-error" role="alert">{{ error || optionsError }}</p>
    <form v-if="interviewId === null" class="workspace-card" @submit.prevent="start">
      <label>Resume source<select v-model="sourceType"><option value="EXTERNAL_RESUME">External resume text</option><option value="PLATFORM_RESUME">Saved platform resume</option></select></label>
      <label>Interview mode<select v-model="interviewMode"><option value="TECHNICAL">Technical</option><option value="BEHAVIORAL">Behavioral</option><option value="JD_TARGETED">Job-targeted</option><option value="COMPREHENSIVE">Comprehensive</option></select></label>
      <template v-if="sourceType === 'PLATFORM_RESUME'"><label>Resume<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>Select a resume</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label><label>Resume version<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required><option value="" disabled>Select a version</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label></template>
      <label v-else>Resume text<textarea v-model.trim="resumeText" rows="10" required /></label>
      <label>Job description<select v-model="jobId" :disabled="optionsLoading" required><option value="" disabled>Select a job</option><option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <button class="btn-neon btn-primary" :disabled="optionsLoading">Start technical interview</button>
    </form>
    <div v-else class="workspace-card"><h2>{{ question }}</h2><form @submit.prevent="submit"><label>Your answer<textarea v-model="answer" rows="6" required /></label><button class="btn-neon btn-primary">Submit answer</button></form>
      <article v-if="feedback" class="workspace-card"><strong>Round score: {{ feedback.score }}</strong><h3>Feedback</h3><ul><li v-for="item in feedback.strengths" :key="item">{{ item }}</li><li v-for="item in feedback.improvements" :key="item">{{ item }}</li></ul><button v-if="!savedRecordIds.includes(feedback.recordId)" class="btn-neon btn-secondary" type="button" :disabled="savingAsset" @click="saveAsset">{{ savingAsset ? 'Saving...' : 'Save to answer assets' }}</button><span v-else class="disclaimer">Saved to answer assets.</span></article>
      <button class="btn-neon btn-ghost" type="button" @click="loadReport">View report</button><article v-if="report"><h2>Total score: {{ report.totalScore }}</h2><p>{{ report.summary }}</p><h3>Resume suggestions</h3><ul><li v-for="item in report.resumeSuggestions" :key="item">{{ item }}</li></ul></article>
    </div>
  </section>
</template>
