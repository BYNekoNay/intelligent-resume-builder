<script setup lang="ts">
import axios from 'axios'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertTriangle, Bookmark, Clipboard, FileText, FolderOpen, Mail, Pencil, Plus, RefreshCw, Save, Send, ShieldCheck, Sparkles, Trash2, X } from 'lucide-vue-next'
import { getTask, retryTask, type AiTask } from '@/api/ai'
import {
  createTemplate,
  deleteTemplate,
  generateCommunication,
  generateCommunicationWithAi,
  listTemplates,
  previewTemplate,
  saveCommunicationDraft,
  updateTemplate,
  type CommunicationAiResult,
  type CommunicationGenerationSource,
  type CommunicationOutputLanguage,
  type CommunicationTemplateSummary,
  type CommunicationType,
  type TemplatePayload,
  type TemplatePreview,
  type TemplateScene,
} from '@/api/communication'
import type { ApiResponse } from '@/api/client'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'
import { useToast } from '@/composables/useToast'
import { useLocale } from '@/i18n'

const POLL_INTERVAL_MS = 2_000
const POLL_TIMEOUT_MS = 90_000

const route = useRoute()
const router = useRouter()
const { locale, t } = useLocale()
const { toasts, success: toastSuccess, error: toastError, dismiss } = useToast()
const activeTab = ref<'ai' | 'templates'>('ai')
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

// ==================== 模板库 ====================
const templates = ref<CommunicationTemplateSummary[]>([])
const sceneFilter = ref<TemplateScene | ''>('')
const templatesLoading = ref(false)
const previewing = ref<CommunicationTemplateSummary | null>(null)
const previewResult = ref<TemplatePreview | null>(null)
const previewDraft = ref('')
const previewLoading = ref(false)
const savingDraft = ref(false)
const showTemplateDialog = ref(false)
const templateDialogMode = ref<'create' | 'edit'>('create')
const templateDialogName = ref('')
const templateDialogScene = ref<TemplateScene>('GENERAL')
const editingTemplateId = ref<number | null>(null)
const templateSaving = ref(false)

const outputLanguage = computed<CommunicationOutputLanguage>(() => locale.value === 'en-US' ? 'EN' : 'ZH_CN')
const typeLabel = computed(() => ({
  COVER_LETTER: t('communication.cover'),
  EMAIL: t('communication.email'),
  OPENING_MESSAGE: t('communication.opening'),
}[type.value]))
const sceneLabels: Record<TemplateScene, string> = {
  FOLLOW_UP: t('communication.sceneFollowUp'),
  THANK_YOU: t('communication.sceneThankYou'),
  SALARY: t('communication.sceneSalary'),
  DECLINE: t('communication.sceneDecline'),
  GENERAL: t('communication.sceneGeneral'),
}
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
  const text = previewing.value ? previewDraft.value : draft.value
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
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

// ==================== 模板库 ====================

async function loadTemplates() {
  templatesLoading.value = true
  error.value = ''
  try {
    templates.value = (await listTemplates({
      scene: sceneFilter.value || undefined,
      outputLanguage: outputLanguage.value,
    })).data.data
  } catch {
    error.value = t('communication.templateLoadError')
  } finally {
    templatesLoading.value = false
  }
}

function visibleTemplates() {
  return templates.value
}

async function openPreview(template: CommunicationTemplateSummary) {
  if (!resumeVersionId.value || !jobId.value) {
    error.value = t('communication.selectError')
    return
  }
  previewing.value = template
  previewResult.value = null
  previewDraft.value = ''
  previewLoading.value = true
  error.value = ''
  try {
    const result = (await previewTemplate(template.id, Number(resumeVersionId.value), Number(jobId.value))).data.data
    previewResult.value = result
    previewDraft.value = result.filledBody
  } catch {
    error.value = t('communication.previewError')
  } finally {
    previewLoading.value = false
  }
}

function closePreview() {
  previewing.value = null
  previewResult.value = null
  previewDraft.value = ''
}

async function confirmSaveDraft() {
  if (!previewResult.value || !previewDraft.value.trim()) return
  savingDraft.value = true
  error.value = ''
  try {
    const result = (await saveCommunicationDraft({
      resumeVersionId: Number(resumeVersionId.value),
      jobDescriptionId: Number(jobId.value),
      type: previewResult.value.type,
      draftText: previewDraft.value,
      templateId: previewResult.value.id,
    })).data.data
    setDraft(result.draft, 'TEMPLATE')
    activeTab.value = 'ai'
    closePreview()
    toastSuccess(t('toast.draftSaved'))
  } catch {
    error.value = t('toast.draftSaveError')
    toastError(t('toast.draftSaveError'))
  } finally {
    savingDraft.value = false
  }
}

function openSaveAsTemplate() {
  templateDialogMode.value = 'create'
  editingTemplateId.value = null
  templateDialogName.value = ''
  templateDialogScene.value = type.value === 'COVER_LETTER' ? 'GENERAL' : 'GENERAL'
  showTemplateDialog.value = true
}

function openEditTemplate(template: CommunicationTemplateSummary) {
  templateDialogMode.value = 'edit'
  editingTemplateId.value = template.id
  templateDialogName.value = template.name
  templateDialogScene.value = template.scene
  showTemplateDialog.value = true
}

async function saveTemplateDialog() {
  if (!templateDialogName.value.trim()) {
    error.value = t('communication.templateNameRequired')
    return
  }
  templateSaving.value = true
  error.value = ''
  const payload: TemplatePayload = {
    name: templateDialogName.value.trim(),
    scene: templateDialogScene.value,
    type: type.value,
    bodyText: previewing.value ? previewDraft.value : draft.value,
    outputLanguage: outputLanguage.value,
  }
  try {
    if (templateDialogMode.value === 'create') {
      await createTemplate(payload)
      toastSuccess(t('toast.templateSaved'))
    } else if (editingTemplateId.value !== null) {
      await updateTemplate(editingTemplateId.value, payload)
      toastSuccess(t('toast.templateSaved'))
    }
    showTemplateDialog.value = false
    await loadTemplates()
  } catch (cause) {
    const code = errorCode(cause)
    if (code === 40301) {
      error.value = t('communication.templateReadOnly')
    } else if (code === 40001) {
      error.value = t('communication.templateIllegalPlaceholder')
    } else {
      error.value = t('toast.templateSaveError')
    }
    toastError(error.value)
  } finally {
    templateSaving.value = false
  }
}

async function removeTemplate(template: CommunicationTemplateSummary) {
  if (!window.confirm(t('communication.confirmDeleteTemplate'))) return
  error.value = ''
  try {
    void deleteTemplate(template.id)
    templates.value = templates.value.filter(item => item.id !== template.id)
    toastSuccess(t('toast.templateDeleted'))
  } catch (cause) {
    const code = errorCode(cause)
    error.value = code === 40301 ? t('communication.templateReadOnly') : t('communication.templateDeleteError')
    toastError(error.value)
  }
}

onMounted(async () => {
  await load()
  void loadTemplates()
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

    <div class="communication-tabs" role="tablist" :aria-label="t('communication.tabsLabel')">
      <button type="button" :class="{ active: activeTab === 'ai' }" :aria-selected="activeTab === 'ai'" @click="activeTab = 'ai'"><Sparkles :size="15" />{{ t('communication.aiGenerate') }}</button>
      <button type="button" :class="{ active: activeTab === 'templates' }" :aria-selected="activeTab === 'templates'" @click="activeTab = 'templates'"><FolderOpen :size="15" />{{ t('communication.templateLibrary') }}</button>
    </div>

    <!-- ==================== AI / 模板生成 ==================== -->
    <template v-if="activeTab === 'ai'">
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
        <div class="job-actions"><button class="btn-neon btn-ghost" type="button" @click="openSaveAsTemplate"><Bookmark :size="15" />{{ t('communication.saveAsTemplate') }}</button><button class="btn-neon btn-secondary" type="button" @click="copyDraft"><Clipboard :size="16" />{{ t('communication.copy') }}</button><button class="btn-neon btn-primary" type="button" @click="useInApplication"><Send :size="16" />{{ t('communication.use') }}</button></div>
        <p v-if="copyStatus" class="disclaimer">{{ copyStatus }}</p>
      </article>
    </template>

    <!-- ==================== 模板库 ==================== -->
    <template v-else>
      <form class="template-toolbar" @submit.prevent="loadTemplates">
        <label>{{ t('communication.sceneFilter') }}<select v-model="sceneFilter"><option value="">{{ t('communication.sceneAll') }}</option><option v-for="(label, scene) in sceneLabels" :key="scene" :value="scene">{{ label }}</option></select></label>
        <button class="btn-neon btn-ghost" :disabled="templatesLoading">{{ t('communication.templateRefresh') }}</button>
      </form>
      <p v-if="error" class="form-error" role="alert">{{ error }}</p>
      <p v-if="templatesLoading" class="empty-state">{{ t('communication.templatesLoading') }}</p>
      <p v-else-if="!visibleTemplates().length" class="empty-state">{{ t('communication.templatesEmpty') }}</p>
      <div v-else class="template-grid">
        <article v-for="template in visibleTemplates()" :key="template.id" class="template-card">
          <header>
            <span class="template-scene">{{ sceneLabels[template.scene] }}</span>
            <span class="template-type">{{ template.type }}</span>
            <span v-if="template.isSystem" class="template-system">{{ t('communication.templateSystem') }}</span>
          </header>
          <h2>{{ template.name }}</h2>
          <p v-if="template.description">{{ template.description }}</p>
          <div class="template-actions">
            <button class="btn-neon btn-primary" type="button" :disabled="!resumeVersionId || !jobId" @click="openPreview(template)"><FileText :size="14" />{{ t('communication.previewFill') }}</button>
            <template v-if="!template.isSystem">
              <button class="icon-template-action" type="button" :title="t('communication.editTemplate')" :aria-label="t('communication.editTemplate')" @click="openEditTemplate(template)"><Pencil :size="14" /></button>
              <button class="icon-template-action danger" type="button" :title="t('communication.deleteTemplate')" :aria-label="t('communication.deleteTemplate')" @click="removeTemplate(template)"><Trash2 :size="14" /></button>
            </template>
          </div>
        </article>
      </div>
    </template>

    <!-- ==================== 预览面板 ==================== -->
    <section v-if="previewing" class="workspace-card preview-panel">
      <header>
        <div><p class="section-kicker">{{ t('communication.previewEyebrow') }}</p><h2>{{ t('communication.previewTitle') }} · {{ previewing.name }}</h2></div>
        <button class="icon-button" type="button" :title="t('common.cancel')" @click="closePreview"><X :size="17" /></button>
      </header>
      <p v-if="previewLoading" class="empty-state">{{ t('communication.previewLoading') }}</p>
      <template v-else-if="previewResult">
        <div v-if="previewResult.missingPlaceholders.length" class="missing-hint">
          <strong>{{ t('communication.missingPlaceholders') }}:</strong>
          <span v-for="name in previewResult.missingPlaceholders" :key="name" class="missing-tag">{{ name }}</span>
        </div>
        <label>{{ t('communication.draft') }}<textarea v-model="previewDraft" rows="14" /></label>
        <div class="job-actions">
          <button class="btn-neon btn-primary" type="button" :disabled="savingDraft || !previewDraft.trim()" @click="confirmSaveDraft"><Save :size="15" />{{ savingDraft ? t('communication.savingDraft') : t('communication.confirmSaveDraft') }}</button>
          <button class="btn-neon btn-secondary" type="button" @click="copyDraft"><Clipboard :size="15" />{{ t('communication.copy') }}</button>
          <button class="btn-neon btn-ghost" type="button" @click="useInApplication"><Send :size="15" />{{ t('communication.use') }}</button>
        </div>
        <p class="disclaimer">{{ t('communication.neverSend') }}</p>
      </template>
    </section>

    <!-- ==================== 存为模板弹窗 ==================== -->
    <div v-if="showTemplateDialog" class="dialog-backdrop" @click.self="showTemplateDialog = false">
      <form class="dialog-card" @submit.prevent="saveTemplateDialog">
        <header><h2>{{ templateDialogMode === 'create' ? t('communication.saveAsTemplate') : t('communication.editTemplate') }}</h2><button class="icon-button" type="button" :aria-label="t('common.cancel')" @click="showTemplateDialog = false"><X :size="17" /></button></header>
        <label>{{ t('communication.templateName') }}<input v-model="templateDialogName" maxlength="128" required /></label>
        <label>{{ t('communication.templateScene') }}<select v-model="templateDialogScene"><option v-for="(label, scene) in sceneLabels" :key="scene" :value="scene">{{ label }}</option></select></label>
        <div class="job-actions"><button class="btn-neon btn-ghost" type="button" @click="showTemplateDialog = false">{{ t('common.cancel') }}</button><button class="btn-neon btn-primary" :disabled="templateSaving"><Save :size="15" />{{ templateSaving ? t('communication.savingDraft') : t('communication.saveTemplate') }}</button></div>
      </form>
    </div>

    <div class="toast-region" aria-live="polite">
      <TransitionGroup name="toast">
        <div v-for="toast in toasts" :key="toast.id" class="toast-item" :class="`toast-${toast.type}`" @click="dismiss(toast.id)">
          {{ toast.message }}
        </div>
      </TransitionGroup>
    </div>
  </section>
</template>

<style scoped>
.communication-page { width: min(100%, 980px); max-width: 980px; gap: 24px; }
.communication-heading { padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.communication-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.communication-heading .page-lead { max-width: 650px; font-size: 12px; }
.communication-tabs { display: inline-flex; gap: 4px; padding: 4px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-surface); justify-self: start; }
.communication-tabs button { display: inline-flex; align-items: center; gap: 6px; min-height: 34px; padding: 0 14px; border: 0; border-radius: 5px; color: var(--text-secondary); background: transparent; font: inherit; font-size: 11px; font-weight: 700; cursor: pointer; }
.communication-tabs button.active { color: var(--accent); background: var(--accent-light); }
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
.communication-draft label, .preview-panel label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.communication-draft textarea, .preview-panel textarea { width: 100%; padding: 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 12px; line-height: 1.6; resize: vertical; }
.communication-draft .job-actions { justify-content: flex-end; }
.template-toolbar { display: flex; align-items: end; gap: 12px; padding: 14px 16px; border-block: 1px solid var(--border-soft); }
.template-toolbar label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.template-toolbar select { padding: 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 11px; }
.template-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 12px; }
.template-card { display: grid; gap: 10px; padding: 16px; border: 1px solid var(--border); border-top: 3px solid var(--info); border-radius: 7px; background: var(--bg-surface); }
.template-card header { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.template-scene, .template-system { padding: 3px 8px; border-radius: 11px; font-size: 9px; font-weight: 700; }
.template-scene { color: var(--info); background: var(--info-light); }
.template-system { color: var(--text-tertiary); background: var(--bg-page); }
.template-type { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; }
.template-card h2 { margin: 0; font-size: 13px; }
.template-card p { margin: 0; color: var(--text-secondary); font-size: 10px; line-height: 1.5; }
.template-actions { display: flex; align-items: center; gap: 6px; }
.template-actions .btn-neon { min-height: 30px; padding: 0 10px; font-size: 10px; }
.icon-template-action { display: grid; width: 30px; height: 30px; place-items: center; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: transparent; cursor: pointer; }
.icon-template-action:hover { color: var(--accent); border-color: var(--accent); }
.icon-template-action.danger:hover { color: var(--danger, #ef4444); border-color: var(--danger, #ef4444); }
.preview-panel { display: grid; gap: 14px; padding: 24px; border-left: 4px solid var(--accent); }
.preview-panel > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 14px; border-bottom: 1px solid var(--border-soft); }
.preview-panel header h2 { margin: 3px 0 0; font-size: 16px; }
.missing-hint { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; padding: 10px 12px; border: 1px solid var(--warning, #d97706); border-radius: 6px; background: var(--highlight-light, #fef3c7); font-size: 10px; }
.missing-hint strong { color: var(--highlight, #d97706); }
.missing-tag { padding: 2px 7px; border-radius: 9px; color: var(--highlight, #d97706); background: #fff; font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.preview-panel .job-actions { justify-content: flex-end; }
.disclaimer { margin: 0; color: var(--text-tertiary); font-size: 10px; }
.dialog-backdrop { position: fixed; inset: 0; z-index: 70; display: grid; place-items: center; padding: 20px; background: rgba(0, 0, 0, 0.4); }
.dialog-card { display: grid; gap: 14px; width: min(420px, 100%); padding: 20px; border-radius: 9px; background: var(--bg-surface); box-shadow: var(--shadow-lg, 0 12px 32px rgba(0,0,0,0.2)); }
.dialog-card header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 12px; border-bottom: 1px solid var(--border-soft); }
.dialog-card header h2 { margin: 0; font-size: 15px; }
.dialog-card label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.dialog-card input, .dialog-card select { padding: 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 11px; }
.toast-region { position: fixed; right: 18px; bottom: 18px; z-index: 80; display: grid; gap: 8px; width: min(320px, calc(100vw - 36px)); }
.toast-item { padding: 11px 14px; border-radius: 7px; color: #fff; font-size: 11px; line-height: 1.5; box-shadow: var(--shadow-lg, 0 8px 24px rgba(0,0,0,0.16)); cursor: pointer; }
.toast-success { background: var(--success, #16a34a); }
.toast-error { background: var(--danger, #ef4444); }
.toast-info { background: var(--info, #2563eb); }
.toast-enter-active, .toast-leave-active { transition: opacity 0.2s ease, transform 0.2s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(6px); }
.icon-button { display: grid; width: 30px; height: 30px; place-items: center; border: 0; border-radius: 5px; color: var(--text-tertiary); background: transparent; cursor: pointer; }
.icon-button:hover { color: var(--accent); background: var(--accent-light); }
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
  .template-toolbar { align-items: stretch; flex-direction: column; }
  .template-grid { grid-template-columns: 1fr; }
  .preview-panel { padding: 20px 16px; }
}
</style>
