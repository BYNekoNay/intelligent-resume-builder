<script setup lang="ts">
import axios from 'axios'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, Clipboard, FileText, Mail, RefreshCw, Send, ShieldCheck, Sparkles } from 'lucide-vue-next'
import { getTask, retryTask, type AiTask } from '@/api/ai'
import {
  generateCommunication,
  generateCommunicationWithAi,
  type CommunicationAiResult,
  type CommunicationGenerationSource,
  type CommunicationOutputLanguage,
  type CommunicationType,
} from '@/api/communication'
import type { ApiResponse } from '@/api/client'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'
import { useLocale } from '@/i18n'

const POLL_INTERVAL_MS = 2_000
const POLL_TIMEOUT_MS = 90_000

const route = useRoute()
const router = useRouter()
const { locale, t } = useLocale()
const resumeVersionId = ref('')
const jobId = ref('')
const type = ref<CommunicationType>('COVER_LETTER')
const draft = ref('')
const source = ref<CommunicationGenerationSource | null>(null)
const draftContext = ref<{ resumeVersionId: number; jobDescriptionId: number; type: CommunicationType } | null>(null)
const error = ref('')
const failureKind = ref<'CONSENT' | 'QUOTA' | 'FAILED' | null>(null)
const copyStatus = ref('')
const loadingMode = ref<'AI' | 'TEMPLATE' | 'RETRY' | null>(null)
const waitingInBackground = ref(false)
const activeTaskId = ref<number | null>(null)
let pollTimer: ReturnType<typeof setTimeout> | null = null
let pollGeneration = 0

const outputLanguage = computed<CommunicationOutputLanguage>(() => locale.value === 'en-US' ? 'EN' : 'ZH_CN')
const typeLabel = computed(() => ({
  COVER_LETTER: t('communication.cover'),
  EMAIL: t('communication.email'),
  OPENING_MESSAGE: t('communication.opening'),
}[type.value]))
const {
  resumes, jobs, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load, loadVersions,
} = useResumeJobOptions()

function stopPolling() {
  pollGeneration += 1
  if (pollTimer) clearTimeout(pollTimer)
  pollTimer = null
}

function resetResult() {
  error.value = ''
  failureKind.value = null
  copyStatus.value = ''
  draft.value = ''
  source.value = null
  draftContext.value = null
  waitingInBackground.value = false
}

function validateSelection() {
  if (resumeVersionId.value && jobId.value) return true
  error.value = t('communication.selectError')
  return false
}

function setDraft(text: string, generationSource: CommunicationGenerationSource) {
  draft.value = text
  source.value = generationSource
  draftContext.value = {
    resumeVersionId: Number(resumeVersionId.value),
    jobDescriptionId: Number(jobId.value),
    type: type.value,
  }
}

function errorCode(cause: unknown) {
  if (!axios.isAxiosError<ApiResponse<unknown>>(cause)) return null
  return cause.response?.data?.code ?? null
}

function handleCreateError(cause: unknown) {
  const code = errorCode(cause)
  failureKind.value = code === 40302 ? 'CONSENT' : code === 42901 ? 'QUOTA' : 'FAILED'
  error.value = code === 40302
    ? t('communication.consentRequired')
    : code === 42901 ? t('communication.quotaExceeded') : t('communication.aiGenerateError')
}

async function rememberTask(taskId: number | null) {
  const query = { ...route.query }
  if (taskId === null) delete query.taskId
  else query.taskId = String(taskId)
  await router.replace({ query })
}

function acceptTaskResult(task: AiTask) {
  if (task.status === 'SUCCESS' && task.resultJson) {
    const result = task.resultJson as unknown as CommunicationAiResult
    resumeVersionId.value = String(result.resumeVersionId)
    jobId.value = String(result.jobDescriptionId)
    type.value = result.type
    setDraft(result.draft, 'AI')
    failureKind.value = null
    error.value = ''
    activeTaskId.value = task.id
    return true
  }
  if (task.status === 'FAILED' || task.status === 'CANCELLED') {
    error.value = task.errorMessage || t('communication.aiGenerateError')
    failureKind.value = 'FAILED'
    activeTaskId.value = task.id
    return true
  }
  return false
}

function startPolling(taskId: number) {
  stopPolling()
  const generation = pollGeneration
  const startedAt = Date.now()
  activeTaskId.value = taskId
  waitingInBackground.value = false
  const poll = async () => {
    if (generation !== pollGeneration) return
    try {
      const task = (await getTask(taskId)).data.data
      if (generation !== pollGeneration || acceptTaskResult(task)) {
        loadingMode.value = null
        return
      }
      if (Date.now() - startedAt >= POLL_TIMEOUT_MS) {
        loadingMode.value = null
        waitingInBackground.value = true
        return
      }
      pollTimer = setTimeout(poll, POLL_INTERVAL_MS)
    } catch {
      if (generation === pollGeneration) {
        loadingMode.value = null
        failureKind.value = 'FAILED'
        error.value = t('communication.restoreError')
      }
    }
  }
  pollTimer = setTimeout(poll, POLL_INTERVAL_MS)
}

async function generateAi() {
  if (!validateSelection()) return
  stopPolling()
  resetResult()
  loadingMode.value = 'AI'
  // A new user-initiated generation must be allowed to produce a new draft.
  // Idempotency is only for retrying the same network request, not for deduplicating
  // distinct generate actions with identical form values.
  const key = `communication:${crypto.randomUUID()}`
  try {
    const task = (await generateCommunicationWithAi(
      Number(resumeVersionId.value), Number(jobId.value), type.value, outputLanguage.value, key,
    )).data.data
    activeTaskId.value = task.id
    await rememberTask(task.id)
    if (!acceptTaskResult(task)) startPolling(task.id)
    else loadingMode.value = null
  } catch (cause) {
    loadingMode.value = null
    handleCreateError(cause)
  }
}

async function generateTemplate() {
  if (!validateSelection()) return
  stopPolling()
  resetResult()
  loadingMode.value = 'TEMPLATE'
  activeTaskId.value = null
  await rememberTask(null)
  try {
    const result = (await generateCommunication(
      Number(resumeVersionId.value), Number(jobId.value), type.value, outputLanguage.value,
    )).data.data
    setDraft(result.draft, 'TEMPLATE')
  } catch {
    error.value = t('communication.templateGenerateError')
  } finally {
    loadingMode.value = null
  }
}

async function retryAi() {
  if (!activeTaskId.value) return
  stopPolling()
  error.value = ''
  failureKind.value = null
  waitingInBackground.value = false
  loadingMode.value = 'RETRY'
  try {
    const task = (await retryTask(activeTaskId.value)).data.data
    if (!acceptTaskResult(task)) startPolling(task.id)
    else loadingMode.value = null
  } catch (cause) {
    loadingMode.value = null
    handleCreateError(cause)
  }
}

async function authorizeAi() {
  await router.push({ path: '/ai-consent', query: { redirect: '/communications' } })
}

async function copyDraft() {
  try {
    await navigator.clipboard.writeText(draft.value)
    copyStatus.value = t('communication.copied')
  } catch {
    copyStatus.value = t('communication.clipboardError')
  }
}

async function useInApplication() {
  if (draftContext.value === null) return
  sessionStorage.setItem('application-draft', JSON.stringify({ ...draftContext.value, text: draft.value }))
  await router.push({ name: 'applications' })
}

onMounted(async () => {
  await load()
  const taskId = Number(route.query.taskId)
  if (!Number.isInteger(taskId) || taskId <= 0) return
  loadingMode.value = 'AI'
  try {
    const task = (await getTask(taskId)).data.data
    if (task.taskType !== 'COMMUNICATION_GENERATE') throw new Error('wrong task type')
    activeTaskId.value = task.id
    if (!acceptTaskResult(task)) startPolling(task.id)
    else loadingMode.value = null
  } catch {
    loadingMode.value = null
    failureKind.value = 'FAILED'
    error.value = t('communication.restoreError')
  }
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <section class="workspace-page communication-page">
    <header class="communication-heading">
      <p class="eyebrow"><Mail :size="14" />{{ t('communication.eyebrow') }}</p>
      <h1>{{ t('communication.title') }}</h1>
      <p class="page-lead">{{ t('communication.subtitle') }}</p>
    </header>

    <form class="workspace-card compact-form communication-setup" @submit.prevent="generateAi">
      <header class="communication-section-heading">
        <span><Sparkles :size="19" /></span>
        <div><p>{{ t('communication.setupEyebrow') }}</p><h2>{{ t('communication.setupTitle') }}</h2><small>{{ t('communication.setupDescription') }}</small></div>
      </header>
      <label>{{ t('communication.resume') }}
        <select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions">
          <option :value="null" disabled>{{ t('communication.selectResume') }}</option>
          <option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option>
        </select>
      </label>
      <label>{{ t('communication.version') }}
        <select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required>
          <option value="" disabled>{{ t('communication.selectVersion') }}</option>
          <option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option>
        </select>
      </label>
      <label>{{ t('communication.job') }}
        <select v-model="jobId" :disabled="optionsLoading" required>
          <option value="" disabled>{{ t('communication.selectJob') }}</option>
          <option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option>
        </select>
      </label>
      <label>{{ t('communication.type') }}
        <select v-model="type"><option value="COVER_LETTER">{{ t('communication.cover') }}</option><option value="EMAIL">{{ t('communication.email') }}</option><option value="OPENING_MESSAGE">{{ t('communication.opening') }}</option></select>
      </label>
      <div class="generation-actions">
        <button class="btn-neon btn-primary" type="submit" :disabled="loadingMode !== null || optionsLoading">
          <Sparkles :size="16" />{{ loadingMode === 'AI' ? t('communication.aiGenerating') : t('communication.aiGenerate') }}
        </button>
        <button class="btn-neon btn-secondary" type="button" :disabled="loadingMode !== null || optionsLoading" @click="generateTemplate">
          <FileText :size="16" />{{ loadingMode === 'TEMPLATE' ? t('communication.templateGenerating') : t('communication.templateGenerate') }}
        </button>
      </div>
    </form>

    <section v-if="optionsError || error || waitingInBackground" class="communication-status" :class="{ warning: error }" :role="error || optionsError ? 'alert' : 'status'">
      <AlertTriangle v-if="error" :size="18" />
      <Sparkles v-else :size="18" />
      <div><strong>{{ error || optionsError || t('communication.backgroundWaiting') }}</strong><p v-if="failureKind === 'QUOTA'">{{ t('communication.templateAvailable') }}</p></div>
      <button v-if="failureKind === 'CONSENT'" class="btn-neon btn-secondary" type="button" @click="authorizeAi"><ShieldCheck :size="16" />{{ t('communication.authorize') }}</button>
      <button v-else-if="failureKind === 'FAILED' && activeTaskId" class="btn-neon btn-secondary" type="button" :disabled="loadingMode !== null" @click="retryAi"><RefreshCw :size="16" />{{ t('communication.retryAi') }}</button>
      <button v-if="error || waitingInBackground" class="btn-neon btn-ghost" type="button" :disabled="loadingMode !== null" @click="generateTemplate"><FileText :size="16" />{{ t('communication.useTemplate') }}</button>
    </section>

    <article v-if="draft" class="workspace-card communication-draft">
      <header>
        <div><p class="section-kicker">{{ t('communication.draftEyebrow') }}</p><h2>{{ t('communication.draft') }}</h2></div>
        <div class="draft-badges"><span class="source-badge" :class="source?.toLowerCase()"><Sparkles v-if="source === 'AI'" :size="14" /><FileText v-else :size="14" />{{ source === 'AI' ? t('communication.sourceAi') : t('communication.sourceTemplate') }}</span><span><FileText :size="15" />{{ typeLabel }}</span></div>
      </header>
      <p class="disclaimer">{{ t('communication.verify') }}</p>
      <label>{{ t('communication.draft') }}<textarea v-model="draft" rows="14" /></label>
      <div class="job-actions"><button class="btn-neon btn-secondary" type="button" @click="copyDraft"><Clipboard :size="16" />{{ t('communication.copy') }}</button><button class="btn-neon btn-primary" type="button" @click="useInApplication"><Send :size="16" />{{ t('communication.use') }}</button></div>
      <p v-if="copyStatus" class="disclaimer">{{ copyStatus }}</p>
    </article>
  </section>
</template>

<style scoped>
.communication-page { width: min(100%, 920px); max-width: 920px; gap: 24px; }
.communication-heading { padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.communication-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.communication-heading .page-lead { max-width: 650px; font-size: 12px; }
.communication-setup { display: grid; grid-template-columns: 1fr 1fr; gap: 14px 16px; padding: 24px; border-left: 4px solid var(--info); }
.communication-section-heading { grid-column: 1 / -1; display: grid; grid-template-columns: 40px 1fr; gap: 12px; padding-bottom: 18px; border-bottom: 1px solid var(--border-soft); }
.communication-section-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--info); background: var(--info-light); }
.communication-section-heading p, .communication-section-heading h2, .communication-section-heading small { display: block; margin: 0; }
.communication-section-heading p, .section-kicker { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.communication-section-heading h2 { margin-top: 3px; font-size: 16px; }
.communication-section-heading small { margin-top: 5px; color: var(--text-secondary); font-size: 10px; }
.generation-actions { grid-column: 1 / -1; display: flex; justify-content: flex-end; gap: 10px; }
.communication-status { display: flex; align-items: center; gap: 12px; padding: 14px 16px; border: 1px solid var(--border); border-left: 4px solid var(--info); border-radius: 6px; background: var(--bg-elevated); color: var(--text-secondary); }
.communication-status.warning { border-left-color: var(--warning); }
.communication-status > div { min-width: 0; flex: 1; }
.communication-status strong, .communication-status p { display: block; margin: 0; overflow-wrap: anywhere; }
.communication-status p { margin-top: 4px; font-size: 11px; }
.communication-draft { display: grid; gap: 14px; padding: 24px; border-left: 4px solid var(--accent); }
.communication-draft > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 14px; border-bottom: 1px solid var(--border-soft); }
.communication-draft h2 { margin: 3px 0 0; font-size: 16px; }
.draft-badges { display: flex; align-items: center; justify-content: flex-end; gap: 8px; flex-wrap: wrap; }
.draft-badges > span { display: inline-flex; align-items: center; gap: 5px; color: var(--text-secondary); font-size: 10px; }
.source-badge { padding: 4px 7px; border: 1px solid var(--border); border-radius: 4px; font-weight: 700; }
.source-badge.ai { color: var(--accent); border-color: color-mix(in srgb, var(--accent) 38%, var(--border)); }
.communication-draft label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.communication-draft textarea { width: 100%; padding: 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 12px; line-height: 1.6; resize: vertical; }
.communication-draft .job-actions { justify-content: flex-end; }
@media (max-width: 680px) {
  .communication-heading h1 { font-size: 29px; }
  .communication-setup { grid-template-columns: 1fr; padding: 20px 16px; }
  .communication-section-heading { grid-column: auto; }
  .generation-actions { grid-column: auto; flex-direction: column; }
  .generation-actions .btn-neon, .communication-status .btn-neon { width: 100%; justify-content: center; }
  .communication-status { align-items: stretch; flex-wrap: wrap; }
  .communication-status > svg { flex: 0 0 auto; }
  .communication-status > div { flex-basis: calc(100% - 34px); }
  .communication-draft { padding: 20px 16px; }
  .communication-draft > header { align-items: flex-start; }
  .communication-draft .job-actions .btn-neon { flex: 1; justify-content: center; }
}
</style>
