<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  Archive,
  ArrowRight,
  BriefcaseBusiness,
  CalendarDays,
  ChevronDown,
  FileText,
  MailCheck,
  Pencil,
  Plus,
  Save,
  Search,
  Trash2,
  X,
} from 'lucide-vue-next'
import {
  createApplication,
  deleteApplication,
  getApplicationStats,
  listApplications,
  updateApplication,
  updateApplicationStatus,
  type ApplicationRecord,
  type ApplicationStats,
  type ApplicationStatus,
  type FollowUpFilter,
} from '@/api/application'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'
import { useToast } from '@/composables/useToast'
import { useLocale } from '@/i18n'
import { listVersions, type ResumeSummary } from '@/api/resume'

const records = ref<ApplicationRecord[]>([])
const stats = ref<ApplicationStats | null>(null)
const loading = ref(true)
const saving = ref(false)
const loadingEdit = ref(false)
const error = ref('')
const editingId = ref<number | null>(null)
const composerOpen = ref(false)
const searchQuery = ref('')
const jobDescriptionId = ref('')
const resumeVersionId = ref('')
const coverLetterText = ref('')
const emailBodyText = ref('')
const openingMessageText = ref('')
const nextFollowUpAt = ref('')
const feedbackDraft = ref<Record<number, string>>({})
const expandedRecordId = ref<number | null>(null)
const followUpFilter = ref<FollowUpFilter>('ALL')
const draggingRecordId = ref<number | null>(null)
const dragOverLane = ref<ApplicationStatus | null>(null)
const { locale, t } = useLocale()
const { toasts, success: toastSuccess, error: toastError, dismiss } = useToast()
const {
  resumes,
  jobs,
  versions,
  selectedResumeId,
  loading: optionsLoading,
  error: optionsError,
  hasVersions,
  load: loadOptions,
  loadVersions,
} = useResumeJobOptions()

const statuses: ApplicationStatus[] = ['DRAFT', 'APPLIED', 'INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN']
const terminalStatuses: ApplicationStatus[] = ['OFFERED', 'REJECTED', 'WITHDRAWN']

const filteredRecords = computed(() => {
  const query = searchQuery.value.trim().toLocaleLowerCase(locale.value)
  if (!query) return records.value
  return records.value.filter((record) => {
    const job = jobFor(record.jobDescriptionId)
    return [job?.title, job?.companyName, record.feedbackText, String(record.id)]
      .some(value => value?.toLocaleLowerCase(locale.value).includes(query))
  })
})

const lanes = computed(() => statuses.map(status => ({
  status,
  records: filteredRecords.value.filter(record => record.status === status),
})))

const appliedCount = computed(() => records.value.filter(record => record.status === 'APPLIED').length)
const interviewCount = computed(() => records.value.filter(record => record.status === 'INTERVIEWING').length)
const offerCount = computed(() => records.value.filter(record => record.status === 'OFFERED').length)

async function load() {
  loading.value = true
  try {
    const [listResponse, statsResponse] = await Promise.all([
      listApplications(followUpFilter.value),
      getApplicationStats(),
    ])
    records.value = listResponse.data.data
    stats.value = statsResponse.data.data
    feedbackDraft.value = Object.fromEntries(records.value.map(record => [record.id, record.feedbackText ?? '']))
  } catch {
    error.value = t('applications.loadError')
  } finally {
    loading.value = false
  }
}

async function changeFollowUpFilter() {
  await load()
}

function resetForm() {
  editingId.value = null
  composerOpen.value = false
  jobDescriptionId.value = ''
  resumeVersionId.value = ''
  coverLetterText.value = ''
  emailBodyText.value = ''
  openingMessageText.value = ''
  nextFollowUpAt.value = ''
}

function openComposer() {
  resetForm()
  composerOpen.value = true
}

async function selectResumeVersion(versionId: number) {
  const currentResume = resumes.value.find(resume => resume.currentVersionId === versionId)
  if (currentResume) {
    selectedResumeId.value = currentResume.id
    await loadVersions()
  } else {
    const owningResume = await findResumeByVersionId(versionId)
    if (owningResume) {
      selectedResumeId.value = owningResume.id
      await loadVersions()
    }
  }
  resumeVersionId.value = String(versionId)
}

/** 并行加载所有简历的版本并定位 versionId 所属简历，避免逐个串行请求（消除循环 N+1）。 */
async function findResumeByVersionId(versionId: number): Promise<ResumeSummary | null> {
  const candidates = await Promise.all(resumes.value.map(async (resume) => {
    try {
      const list = (await listVersions(resume.id)).data.data
      return list.some(version => version.id === versionId) ? resume : null
    } catch {
      return null
    }
  }))
  return candidates.find(resume => resume !== null) ?? null
}

async function edit(record: ApplicationRecord) {
  if (loadingEdit.value) return
  loadingEdit.value = true
  composerOpen.value = true
  editingId.value = record.id
  jobDescriptionId.value = String(record.jobDescriptionId)
  coverLetterText.value = record.coverLetterText ?? ''
  emailBodyText.value = record.emailBodyText ?? ''
  openingMessageText.value = record.openingMessageText ?? ''
  nextFollowUpAt.value = record.nextFollowUpAt ? record.nextFollowUpAt.slice(0, 16) : ''
  try {
    await selectResumeVersion(record.resumeVersionId)
    error.value = ''
    document.querySelector('.application-composer')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  } finally {
    loadingEdit.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  const payload = {
    jobDescriptionId: Number(jobDescriptionId.value),
    resumeVersionId: Number(resumeVersionId.value),
    status: 'DRAFT' as ApplicationStatus,
    coverLetterText: coverLetterText.value || undefined,
    emailBodyText: emailBodyText.value || undefined,
    openingMessageText: openingMessageText.value || undefined,
    nextFollowUpAt: nextFollowUpAt.value ? `${nextFollowUpAt.value}:00` : null,
  }
  try {
    if (editingId.value === null) {
      const created = (await createApplication(payload)).data.data
      records.value.unshift(created)
    } else {
      const existing = records.value.find(record => record.id === editingId.value)
      const updated = (await updateApplication(editingId.value, {
        ...payload,
        status: existing?.status ?? 'DRAFT',
        version: existing?.version,
      })).data.data
      records.value = records.value.map(record => record.id === updated.id ? updated : record)
    }
    resetForm()
    await load()
  } catch {
    error.value = t('applications.saveError')
  } finally {
    saving.value = false
  }
}

function allowedStatuses(status: ApplicationStatus): ApplicationStatus[] {
  const transitions: Record<ApplicationStatus, ApplicationStatus[]> = {
    DRAFT: ['DRAFT', 'APPLIED', 'WITHDRAWN'],
    APPLIED: ['APPLIED', 'INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN'],
    INTERVIEWING: ['INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN'],
    OFFERED: ['OFFERED'],
    REJECTED: ['REJECTED'],
    WITHDRAWN: ['WITHDRAWN'],
  }
  return transitions[status]
}

function statusLabel(status: ApplicationStatus) {
  const labels: Record<ApplicationStatus, string> = {
    DRAFT: t('applications.statusDraft'),
    APPLIED: t('applications.statusApplied'),
    INTERVIEWING: t('applications.statusInterviewing'),
    OFFERED: t('applications.statusOffered'),
    REJECTED: t('applications.statusRejected'),
    WITHDRAWN: t('applications.statusWithdrawn'),
  }
  return labels[status]
}

function jobFor(id: number) {
  return jobs.value.find(job => job.id === id)
}

function recordTitle(record: ApplicationRecord) {
  return jobFor(record.jobDescriptionId)?.title || `${t('applications.jobRef')} #${record.jobDescriptionId}`
}

function recordCompany(record: ApplicationRecord) {
  return jobFor(record.jobDescriptionId)?.companyName || t('applications.jobRef')
}

function formatDate(value: string | null) {
  if (!value) return t('applications.statusDraft')
  return new Intl.DateTimeFormat(locale.value, { month: 'short', day: 'numeric' }).format(new Date(value))
}

function formatFollowUp(value: string | null) {
  if (!value) return ''
  return new Intl.DateTimeFormat(locale.value, { month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(new Date(value))
}

function isOverdue(record: ApplicationRecord) {
  if (!record.nextFollowUpAt) return false
  if (terminalStatuses.includes(record.status)) return false
  return new Date(record.nextFollowUpAt).getTime() < Date.now()
}

function showFollowUp(record: ApplicationRecord) {
  return record.nextFollowUpAt && !terminalStatuses.includes(record.status)
}

function messageCount(record: ApplicationRecord) {
  return [record.coverLetterText, record.emailBodyText, record.openingMessageText].filter(Boolean).length
}

async function changeStatus(record: ApplicationRecord, status: ApplicationStatus) {
  try {
    const updated = (await updateApplicationStatus(
      record.id,
      status,
      record.version,
      feedbackDraft.value[record.id] ?? record.feedbackText ?? undefined,
    )).data.data
    Object.assign(record, updated)
    feedbackDraft.value[record.id] = record.feedbackText ?? ''
  } catch (cause: any) {
    if (cause?.response?.data?.code === 40901) {
      toastError(t('toast.applicationConflict'))
      await load()
    } else {
      toastError(t('toast.applicationStatusError'))
    }
  }
}

// ==================== 原生 HTML5 拖拽 ====================

function onCardDragStart(record: ApplicationRecord, event: DragEvent) {
  draggingRecordId.value = record.id
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move'
    event.dataTransfer.setData('text/plain', String(record.id))
  }
}

function onCardDragEnd() {
  draggingRecordId.value = null
  dragOverLane.value = null
}

function onLaneDragOver(status: ApplicationStatus, event: DragEvent) {
  event.preventDefault()
  if (event.dataTransfer) event.dataTransfer.dropEffect = 'move'
  dragOverLane.value = status
}

function onLaneDragLeave() {
  dragOverLane.value = null
}

async function onLaneDrop(status: ApplicationStatus, event: DragEvent) {
  event.preventDefault()
  dragOverLane.value = null
  const recordId = Number(event.dataTransfer?.getData('text/plain') ?? draggingRecordId.value)
  const record = records.value.find(item => item.id === recordId)
  if (!record) return
  if (record.status === status) return
  // 非法迁移：toast 阻止，不发起任何请求、不移列
  if (!allowedStatuses(record.status).includes(status)) {
    toastError(t('toast.applicationInvalidMove'))
    return
  }
  try {
    const updated = (await updateApplicationStatus(
      record.id,
      status,
      record.version,
      feedbackDraft.value[record.id] ?? record.feedbackText ?? undefined,
    )).data.data
    records.value = records.value.map(item => item.id === updated.id ? updated : item)
    feedbackDraft.value[updated.id] = updated.feedbackText ?? ''
    toastSuccess(t('toast.applicationMoved'))
  } catch (cause: any) {
    // 乐观锁冲突：toast + 重新拉列表
    if (cause?.response?.data?.code === 40901) {
      toastError(t('toast.applicationConflict'))
      await load()
    } else {
      toastError(t('toast.applicationStatusError'))
    }
  }
}

function formatPercent(value: number | null) {
  return value === null ? '—' : `${value}%`
}

function formatRate(value: number | null) {
  return value === null ? '—' : `${Math.round(value * 100)}%`
}

function formatDays(value: number | null) {
  return value === null ? '—' : `${value}${t('applications.daysUnit')}`
}

async function remove(record: ApplicationRecord) {
  if (!window.confirm(t('applications.confirmDelete'))) return
  try {
    await deleteApplication(record.id)
    records.value = records.value.filter(item => item.id !== record.id)
    if (editingId.value === record.id) resetForm()
  } catch {
    error.value = t('applications.deleteError')
  }
}

onMounted(async () => {
  void load()
  await loadOptions()
  const storedDraft = sessionStorage.getItem('application-draft')
  if (!storedDraft) return
  sessionStorage.removeItem('application-draft')
  try {
    const draft = JSON.parse(storedDraft) as { resumeVersionId: number; jobDescriptionId: number; type: string; text: string }
    composerOpen.value = true
    jobDescriptionId.value = String(draft.jobDescriptionId)
    await selectResumeVersion(draft.resumeVersionId)
    if (draft.type === 'COVER_LETTER') coverLetterText.value = draft.text
    if (draft.type === 'EMAIL') emailBodyText.value = draft.text
    if (draft.type === 'OPENING_MESSAGE') openingMessageText.value = draft.text
  } catch {
    error.value = t('applications.importDraftError')
  }
})
</script>

<template>
  <section class="workspace-page applications-page">
    <header class="applications-heading">
      <div>
        <p class="eyebrow"><BriefcaseBusiness :size="14" />{{ t('applications.eyebrow') }}</p>
        <h1>{{ t('applications.title') }}</h1>
        <p class="page-lead">{{ t('applications.subtitle') }}</p>
      </div>
      <button class="btn-neon btn-primary" type="button" @click="openComposer">
        <Plus :size="16" />{{ t('applications.create') }}
      </button>
    </header>

    <section class="pipeline-summary" :aria-label="t('applications.title')">
      <div><span>{{ t('applications.recordCount') }}</span><strong>{{ stats?.total ?? records.length }}</strong></div>
      <div><span>{{ t('applications.statusApplied') }}</span><strong>{{ appliedCount }}</strong></div>
      <div><span>{{ t('applications.statusInterviewing') }}</span><strong>{{ interviewCount }}</strong></div>
      <div><span>{{ t('applications.statusOffered') }}</span><strong>{{ offerCount }}</strong></div>
    </section>

    <section v-if="stats" class="funnel-summary" :aria-label="t('applications.funnelTitle')">
      <div class="funnel-title"><span>{{ t('applications.funnelTitle') }}</span><small>{{ t('applications.funnelSubtitle') }}</small></div>
      <div class="funnel-metric"><span>{{ t('applications.convAppliedInterviewing') }}</span><strong>{{ formatRate(stats.conversionRates.appliedToInterviewing) }}</strong></div>
      <div class="funnel-metric"><span>{{ t('applications.convInterviewingOffered') }}</span><strong>{{ formatRate(stats.conversionRates.interviewingToOffered) }}</strong></div>
      <div class="funnel-metric"><span>{{ t('applications.convAppliedOffered') }}</span><strong>{{ formatRate(stats.conversionRates.appliedToOffered) }}</strong></div>
      <div class="funnel-metric"><span>{{ t('applications.avgApplied') }}</span><strong>{{ formatDays(stats.avgStageDurationDays.applied) }}</strong></div>
      <div class="funnel-metric"><span>{{ t('applications.avgInterviewing') }}</span><strong>{{ formatDays(stats.avgStageDurationDays.interviewing) }}</strong></div>
      <div class="funnel-metric"><span>{{ t('applications.avgTotalToOffer') }}</span><strong>{{ formatDays(stats.avgStageDurationDays.totalToOffer) }}</strong></div>
    </section>

    <form v-if="composerOpen" class="workspace-card compact-form application-composer" @submit.prevent="save">
      <header class="application-section-heading">
        <span><Plus v-if="editingId === null" :size="19" /><Pencil v-else :size="19" /></span>
        <div>
          <p>{{ editingId === null ? t('applications.createEyebrow') : t('applications.editEyebrow') }}</p>
          <h2>{{ editingId === null ? t('applications.create') : t('applications.edit') }}</h2>
          <small>{{ t('applications.composerDescription') }}</small>
        </div>
        <button class="icon-button" type="button" :title="t('applications.cancel')" @click="resetForm"><X :size="17" /></button>
      </header>
      <label>{{ t('applications.resume') }}<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>{{ t('applications.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <label>{{ t('applications.version') }}<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required><option value="" disabled>{{ t('applications.selectVersion') }}</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label>
      <label>{{ t('applications.job') }}<select v-model="jobDescriptionId" :disabled="optionsLoading" required><option value="" disabled>{{ t('applications.selectJob') }}</option><option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <label>{{ t('applications.cover') }}<textarea v-model="coverLetterText" rows="4" /></label>
      <label>{{ t('applications.email') }}<textarea v-model="emailBodyText" rows="4" /></label>
      <label>{{ t('applications.opening') }}<textarea v-model="openingMessageText" rows="4" /></label>
      <label>{{ t('applications.nextFollowUp') }}<input v-model="nextFollowUpAt" type="datetime-local" /></label>
      <div class="job-actions"><button class="btn-neon btn-primary" :disabled="saving || loadingEdit || optionsLoading"><Save :size="16" />{{ saving ? t('applications.saving') : editingId === null ? t('applications.createDraft') : t('applications.saveChanges') }}</button><button class="btn-neon btn-ghost" type="button" :disabled="loadingEdit" @click="resetForm">{{ t('applications.cancel') }}</button></div>
    </form>

    <p v-if="optionsError || error" class="form-error" role="alert">{{ error || optionsError }}</p>

    <div class="pipeline-toolbar">
      <label class="pipeline-search"><Search :size="16" /><span class="sr-only">{{ t('applications.title') }}</span><input v-model="searchQuery" type="search" :placeholder="t('applications.job')" /></label>
      <label class="follow-up-filter">{{ t('applications.followUpFilter') }}<select v-model="followUpFilter" @change="changeFollowUpFilter"><option value="ALL">{{ t('applications.followUpAll') }}</option><option value="TODAY">{{ t('applications.followUpToday') }}</option><option value="OVERDUE">{{ t('applications.followUpOverdue') }}</option></select></label>
    </div>

    <p v-if="loading" class="pipeline-loading">{{ t('applications.loading') }}</p>
    <div v-else-if="records.length" class="application-board">
      <section v-for="lane in lanes" :key="lane.status" class="pipeline-lane" :class="[`lane-${lane.status.toLowerCase()}`, { 'is-drag-over': dragOverLane === lane.status }]" @dragover="onLaneDragOver(lane.status, $event)" @dragleave="onLaneDragLeave" @drop="onLaneDrop(lane.status, $event)">
        <header><span class="lane-marker" /><h2>{{ statusLabel(lane.status) }}</h2><strong>{{ lane.records.length }}</strong></header>
        <div class="lane-records">
          <article v-for="record in lane.records" :key="record.id" class="application-ticket" :class="{ 'is-dragging': draggingRecordId === record.id }" draggable="true" @dragstart="onCardDragStart(record, $event)" @dragend="onCardDragEnd">
            <header>
              <div><span>{{ recordCompany(record) }}</span><h3>{{ recordTitle(record) }}</h3></div>
              <button class="icon-button" type="button" :title="t('applications.editAction')" :disabled="loadingEdit" @click="edit(record)"><Pencil :size="14" /></button>
            </header>
            <div class="ticket-lineage">
              <span><FileText :size="13" />{{ t('applications.versionRef') }} #{{ record.resumeVersionId }}</span>
              <ArrowRight :size="13" />
              <span><MailCheck :size="13" />{{ messageCount(record) }}/3</span>
            </div>
            <div class="ticket-date"><CalendarDays :size="13" />{{ formatDate(record.appliedAt || record.updatedAt) }}</div>
            <div v-if="showFollowUp(record)" class="ticket-follow-up" :class="{ overdue: isOverdue(record) }"><CalendarDays :size="13" />{{ t('applications.nextFollowUp') }}: {{ formatFollowUp(record.nextFollowUpAt) }}</div>
            <label class="stage-control"><span>{{ t('applications.status') }}</span><select :value="record.status" :aria-label="t('applications.status')" @change="changeStatus(record, ($event.target as HTMLSelectElement).value as ApplicationStatus)"><option v-for="status in allowedStatuses(record.status)" :key="status" :value="status">{{ statusLabel(status) }}</option></select></label>
            <button class="ticket-expand" type="button" :aria-expanded="expandedRecordId === record.id" @click="expandedRecordId = expandedRecordId === record.id ? null : record.id"><span>{{ t('applications.feedback') }}</span><ChevronDown :size="15" /></button>
            <div v-if="expandedRecordId === record.id" class="ticket-details">
              <label>{{ t('applications.feedback') }}<textarea :value="feedbackDraft[record.id] ?? record.feedbackText ?? ''" rows="3" @input="feedbackDraft[record.id] = ($event.target as HTMLTextAreaElement).value" /></label>
              <button class="btn-neon btn-secondary" type="button" @click="changeStatus(record, record.status)">{{ t('applications.saveFeedback') }}</button>
              <div class="application-drafts"><p v-if="record.coverLetterText"><strong>{{ t('applications.cover') }}</strong>{{ record.coverLetterText }}</p><p v-if="record.emailBodyText"><strong>{{ t('applications.email') }}</strong>{{ record.emailBodyText }}</p><p v-if="record.openingMessageText"><strong>{{ t('applications.opening') }}</strong>{{ record.openingMessageText }}</p></div>
              <button class="danger-action" type="button" :title="t('applications.delete')" @click="remove(record)"><Trash2 :size="14" />{{ t('applications.delete') }}</button>
            </div>
          </article>
          <p v-if="!lane.records.length" class="lane-empty">{{ t('applications.empty') }}</p>
        </div>
      </section>
    </div>
    <div v-else class="empty-state application-empty"><BriefcaseBusiness :size="24" /><strong>{{ t('applications.empty') }}</strong><button class="btn-neon btn-secondary" type="button" @click="openComposer"><Plus :size="15" />{{ t('applications.create') }}</button></div>

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
.applications-page { width: min(100%, 1440px); max-width: 1440px; gap: 22px; }
.applications-heading { display: flex; align-items: end; justify-content: space-between; gap: 24px; padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.applications-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.applications-heading .page-lead { max-width: 650px; font-size: 12px; }
.pipeline-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); border-block: 1px solid var(--border); }
.pipeline-summary div { display: flex; align-items: baseline; justify-content: space-between; gap: 16px; padding: 15px 20px; border-right: 1px solid var(--border); }
.pipeline-summary div:last-child { border-right: 0; }
.pipeline-summary span { color: var(--text-tertiary); font-size: 10px; font-weight: 700; }
.pipeline-summary strong { color: var(--text-primary); font-family: var(--font-utility); font-size: 22px; }
.funnel-summary { display: grid; grid-template-columns: 1.4fr repeat(6, minmax(0, 1fr)); align-items: center; gap: 0; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); overflow: hidden; }
.funnel-title { display: grid; gap: 3px; padding: 12px 16px; background: var(--accent-light); }
.funnel-title span { color: var(--accent); font-size: 11px; font-weight: 700; }
.funnel-title small { color: var(--text-secondary); font-size: 9px; }
.funnel-metric { display: grid; gap: 3px; padding: 12px 14px; border-left: 1px solid var(--border-soft); }
.funnel-metric span { color: var(--text-tertiary); font-size: 9px; font-weight: 700; }
.funnel-metric strong { color: var(--text-primary); font-family: var(--font-utility); font-size: 16px; }
.application-composer { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px 16px; padding: 24px; border-left: 4px solid var(--accent); }
.application-section-heading { grid-column: 1 / -1; display: grid; grid-template-columns: 40px 1fr auto; gap: 12px; padding-bottom: 18px; border-bottom: 1px solid var(--border-soft); }
.application-section-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--accent); background: var(--accent-light); }
.application-section-heading p, .application-section-heading h2, .application-section-heading small { display: block; margin: 0; }
.application-section-heading p { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.application-section-heading h2 { margin-top: 3px; font-size: 16px; }
.application-section-heading small { margin-top: 5px; color: var(--text-secondary); font-size: 10px; }
.application-composer .job-actions { grid-column: 1 / -1; justify-content: flex-end; }
.pipeline-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 18px; }
.pipeline-toolbar > span { display: inline-flex; align-items: center; gap: 6px; color: var(--text-tertiary); font-size: 10px; }
.pipeline-search { display: flex; width: min(100%, 360px); height: 38px; align-items: center; gap: 8px; padding: 0 11px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-input); color: var(--text-tertiary); }
.pipeline-search:focus-within { border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.pipeline-search input { min-width: 0; flex: 1; border: 0; outline: 0; background: transparent; color: var(--text-primary); font: inherit; font-size: 11px; }
.follow-up-filter { display: inline-flex; align-items: center; gap: 8px; color: var(--text-secondary); font-size: 10px; font-weight: 700; }
.follow-up-filter select { height: 34px; padding: 0 9px; border: 1px solid var(--border); border-radius: 5px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 10px; }
.application-board { display: grid; grid-template-columns: repeat(6, minmax(220px, 1fr)); gap: 12px; padding-bottom: 12px; overflow-x: auto; scroll-snap-type: x proximity; }
.pipeline-lane { min-height: 390px; border: 1px solid var(--border); border-radius: 7px; background: color-mix(in srgb, var(--bg-page) 72%, var(--bg-surface)); scroll-snap-align: start; transition: background 0.15s ease; }
.pipeline-lane.is-drag-over { background: var(--accent-light); border-color: var(--accent); }
.pipeline-lane > header { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 8px; min-height: 44px; padding: 0 12px; border-bottom: 1px solid var(--border); background: var(--bg-surface); }
.pipeline-lane h2 { margin: 0; font-size: 11px; }
.pipeline-lane header strong { display: grid; width: 22px; height: 22px; place-items: center; border-radius: 50%; color: var(--text-secondary); background: var(--bg-page); font-family: var(--font-utility); font-size: 9px; }
.lane-marker { width: 7px; height: 7px; border-radius: 50%; background: var(--text-tertiary); }
.lane-applied .lane-marker { background: var(--accent); }
.lane-interviewing .lane-marker { background: var(--highlight); }
.lane-offered .lane-marker { background: var(--success); }
.lane-rejected .lane-marker, .lane-withdrawn .lane-marker { background: var(--border-strong); }
.lane-records { display: grid; align-content: start; gap: 9px; padding: 9px; }
.application-ticket { border: 1px solid var(--border); border-radius: 6px; background: var(--bg-surface); box-shadow: var(--shadow-sm); cursor: grab; }
.application-ticket.is-dragging { opacity: 0.45; }
.application-ticket > header { display: flex; align-items: start; justify-content: space-between; gap: 8px; padding: 12px 12px 8px; }
.application-ticket header span { display: block; overflow: hidden; color: var(--text-tertiary); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }
.application-ticket h3 { margin: 3px 0 0; overflow-wrap: anywhere; font-size: 12px; line-height: 1.4; }
.icon-button { display: grid; width: 30px; height: 30px; flex: 0 0 30px; place-items: center; border: 0; border-radius: 5px; color: var(--text-tertiary); background: transparent; cursor: pointer; }
.icon-button:hover { color: var(--accent); background: var(--accent-light); }
.icon-button:focus-visible, .ticket-expand:focus-visible { outline: 2px solid var(--border-focus); outline-offset: 2px; }
.ticket-lineage { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; align-items: center; gap: 6px; margin: 0 12px; padding: 8px 0; border-block: 1px solid var(--border-soft); color: var(--text-secondary); font-size: 9px; }
.ticket-lineage span, .ticket-date { display: inline-flex; min-width: 0; align-items: center; gap: 4px; }
.ticket-lineage span:first-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ticket-date { padding: 8px 12px 4px; color: var(--text-tertiary); font-size: 9px; }
.ticket-follow-up { display: inline-flex; align-items: center; gap: 4px; padding: 3px 12px; color: var(--text-secondary); font-size: 9px; }
.ticket-follow-up.overdue { color: var(--danger, #ef4444); font-weight: 700; }
.stage-control { display: grid; gap: 5px; padding: 7px 12px 10px; color: var(--text-tertiary); font-size: 9px; font-weight: 700; }
.stage-control select { width: 100%; padding: 7px 8px; border: 1px solid var(--border); border-radius: 5px; color: var(--text-primary); background: var(--bg-input); font: inherit; font-size: 10px; }
.ticket-expand { display: flex; width: 100%; min-height: 34px; align-items: center; justify-content: space-between; padding: 0 12px; border: 0; border-top: 1px solid var(--border-soft); color: var(--text-secondary); background: transparent; font: inherit; font-size: 9px; font-weight: 700; cursor: pointer; }
.ticket-expand[aria-expanded="true"] svg { transform: rotate(180deg); }
.ticket-details { display: grid; gap: 9px; padding: 11px 12px 12px; border-top: 1px solid var(--border-soft); }
.ticket-details label { display: grid; gap: 5px; color: var(--text-secondary); font-size: 9px; font-weight: 700; }
.ticket-details textarea { width: 100%; padding: 8px; border: 1px solid var(--border); border-radius: 5px; color: var(--text-primary); background: var(--bg-input); font: inherit; font-size: 10px; resize: vertical; }
.ticket-details .btn-secondary { justify-self: start; }
.ticket-details .danger-action { display: inline-flex; align-items: center; gap: 5px; justify-self: start; }
.application-drafts { display: grid; gap: 6px; }
.application-drafts p { display: grid; gap: 3px; margin: 0; padding: 8px 9px; border-left: 2px solid var(--border); color: var(--text-secondary); background: var(--bg-page); font-size: 9px; line-height: 1.5; white-space: pre-wrap; }
.application-drafts strong { color: var(--text-primary); font-size: 8px; }
.lane-empty { margin: 14px 8px; color: var(--text-tertiary); font-size: 9px; line-height: 1.5; text-align: center; }
.pipeline-loading { min-height: 260px; color: var(--text-secondary); }
.application-empty { display: grid; min-height: 260px; place-items: center; align-content: center; gap: 12px; }
.application-empty strong { font-size: 13px; }
.toast-region { position: fixed; right: 18px; bottom: 18px; z-index: 60; display: grid; gap: 8px; width: min(320px, calc(100vw - 36px)); }
.toast-item { padding: 11px 14px; border-radius: 7px; color: #fff; font-size: 11px; line-height: 1.5; box-shadow: var(--shadow-lg, 0 8px 24px rgba(0,0,0,0.16)); cursor: pointer; }
.toast-success { background: var(--success, #16a34a); }
.toast-error { background: var(--danger, #ef4444); }
.toast-info { background: var(--info, #2563eb); }
.toast-enter-active, .toast-leave-active { transition: opacity 0.2s ease, transform 0.2s ease; }
.toast-enter-from, .toast-leave-to { opacity: 0; transform: translateY(6px); }
@media (max-width: 900px) { .pipeline-summary { grid-template-columns: repeat(2, 1fr); }.pipeline-summary div:nth-child(2) { border-right: 0; }.pipeline-summary div:nth-child(-n+2) { border-bottom: 1px solid var(--border); }.application-composer { grid-template-columns: 1fr 1fr; }.application-board { grid-template-columns: repeat(6, minmax(240px, 78vw)); }.funnel-summary { grid-template-columns: repeat(3, 1fr); }.funnel-title { grid-column: 1 / -1; } }
@media (max-width: 680px) { .applications-heading { align-items: stretch; flex-direction: column; }.applications-heading .btn-primary { align-self: start; }.application-composer { grid-template-columns: 1fr; padding: 20px 16px; }.application-section-heading, .application-composer .job-actions { grid-column: auto; }.application-composer .job-actions .btn-neon { flex: 1; justify-content: center; }.pipeline-toolbar { align-items: stretch; flex-direction: column; }.pipeline-search { width: 100%; }.application-board { margin-inline: -16px; padding-inline: 16px; }.pipeline-toolbar > span { display: none; }.funnel-summary { grid-template-columns: repeat(2, 1fr); } }
</style>
