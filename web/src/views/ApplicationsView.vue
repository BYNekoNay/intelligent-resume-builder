<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { createApplication, deleteApplication, listApplications, updateApplication, updateApplicationStatus, type ApplicationRecord, type ApplicationStatus } from '@/api/application'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'
import { useLocale } from '@/i18n'

const records = ref<ApplicationRecord[]>([])
const loading = ref(true)
const saving = ref(false)
const loadingEdit = ref(false)
const error = ref('')
const editingId = ref<number | null>(null)
const jobDescriptionId = ref('')
const resumeVersionId = ref('')
const coverLetterText = ref('')
const emailBodyText = ref('')
const openingMessageText = ref('')
const feedbackDraft = ref<Record<number, string>>({})
const { t } = useLocale()
const { resumes, jobs, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load: loadOptions, loadVersions } = useResumeJobOptions()

async function load() {
  loading.value = true
  try { records.value = (await listApplications()).data.data }
  catch { error.value = t('applications.loadError') }
  finally { loading.value = false }
}

function resetForm() {
  editingId.value = null
  jobDescriptionId.value = ''
  resumeVersionId.value = ''
  coverLetterText.value = ''
  emailBodyText.value = ''
  openingMessageText.value = ''
}

async function selectResumeVersion(versionId: number) {
  const currentResume = resumes.value.find((resume) => resume.currentVersionId === versionId)
  if (currentResume) {
    selectedResumeId.value = currentResume.id
    await loadVersions()
  } else {
    for (const resume of resumes.value) {
      selectedResumeId.value = resume.id
      await loadVersions()
      if (versions.value.some((version) => version.id === versionId)) break
    }
  }
  resumeVersionId.value = String(versionId)
}

async function edit(record: ApplicationRecord) {
  if (loadingEdit.value) return
  loadingEdit.value = true
  editingId.value = record.id
  jobDescriptionId.value = String(record.jobDescriptionId)
  coverLetterText.value = record.coverLetterText ?? ''
  emailBodyText.value = record.emailBodyText ?? ''
  openingMessageText.value = record.openingMessageText ?? ''
  try {
    await selectResumeVersion(record.resumeVersionId)
    error.value = ''
  } finally {
    loadingEdit.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  const payload = { jobDescriptionId: Number(jobDescriptionId.value), resumeVersionId: Number(resumeVersionId.value), status: 'DRAFT' as ApplicationStatus, coverLetterText: coverLetterText.value || undefined, emailBodyText: emailBodyText.value || undefined, openingMessageText: openingMessageText.value || undefined }
  try {
    if (editingId.value === null) {
      const created = (await createApplication(payload)).data.data
      records.value.unshift(created)
    } else {
      const existing = records.value.find((record) => record.id === editingId.value)
      const updated = (await updateApplication(editingId.value, { ...payload, status: existing?.status ?? 'DRAFT', version: existing?.version })).data.data
      records.value = records.value.map((record) => record.id === updated.id ? updated : record)
    }
    resetForm()
  } catch { error.value = t('applications.saveError') }
  finally { saving.value = false }
}

function allowedStatuses(status: ApplicationStatus): ApplicationStatus[] {
  const transitions: Record<ApplicationStatus, ApplicationStatus[]> = { DRAFT: ['DRAFT', 'APPLIED', 'WITHDRAWN'], APPLIED: ['APPLIED', 'INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN'], INTERVIEWING: ['INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN'], OFFERED: ['OFFERED'], REJECTED: ['REJECTED'], WITHDRAWN: ['WITHDRAWN'] }
  return transitions[status]
}
async function changeStatus(record: ApplicationRecord, status: ApplicationStatus) {
  try { const updated = (await updateApplicationStatus(record.id, status, record.version, feedbackDraft.value[record.id] ?? record.feedbackText ?? undefined)).data.data; Object.assign(record, updated); feedbackDraft.value[record.id] = record.feedbackText ?? '' }
  catch { error.value = t('applications.statusError') }
}
async function remove(record: ApplicationRecord) {
  if (!window.confirm(t('applications.confirmDelete'))) return
  try { await deleteApplication(record.id); records.value = records.value.filter((item) => item.id !== record.id); if (editingId.value === record.id) resetForm() }
  catch { error.value = t('applications.deleteError') }
}

onMounted(async () => {
  void load()
  await loadOptions()
  const storedDraft = sessionStorage.getItem('application-draft')
  if (!storedDraft) return
  sessionStorage.removeItem('application-draft')
  try {
    const draft = JSON.parse(storedDraft) as { resumeVersionId: number; jobDescriptionId: number; type: string; text: string }
    jobDescriptionId.value = String(draft.jobDescriptionId)
    await selectResumeVersion(draft.resumeVersionId)
    if (draft.type === 'COVER_LETTER') coverLetterText.value = draft.text
    if (draft.type === 'EMAIL') emailBodyText.value = draft.text
    if (draft.type === 'OPENING_MESSAGE') openingMessageText.value = draft.text
  } catch { error.value = t('applications.importDraftError') }
})
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">{{ t('applications.eyebrow') }}</p><h1>{{ t('applications.title') }}</h1><p>{{ t('applications.subtitle') }}</p>
    <form class="workspace-card compact-form" @submit.prevent="save">
      <h2>{{ editingId === null ? t('applications.create') : t('applications.edit') }}</h2>
      <label>{{ t('applications.resume') }}<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>{{ t('applications.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <label>{{ t('applications.version') }}<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required><option value="" disabled>{{ t('applications.selectVersion') }}</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label>
      <label>{{ t('applications.job') }}<select v-model="jobDescriptionId" :disabled="optionsLoading" required><option value="" disabled>{{ t('applications.selectJob') }}</option><option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <label>{{ t('applications.cover') }}<textarea v-model="coverLetterText" rows="4" /></label><label>{{ t('applications.email') }}<textarea v-model="emailBodyText" rows="4" /></label><label>{{ t('applications.opening') }}<textarea v-model="openingMessageText" rows="4" /></label>
      <div class="job-actions"><button class="btn-neon btn-primary" :disabled="saving || loadingEdit || optionsLoading">{{ saving ? t('applications.saving') : editingId === null ? t('applications.createDraft') : t('applications.saveChanges') }}</button><button v-if="editingId !== null" class="btn-neon btn-ghost" type="button" :disabled="loadingEdit" @click="resetForm">{{ t('applications.cancel') }}</button></div>
    </form>
    <p v-if="optionsError || error" class="form-error" role="alert">{{ error || optionsError }}</p><p v-if="loading">{{ t('applications.loading') }}</p>
    <div v-else-if="records.length" class="workspace-list"><article v-for="record in records" :key="record.id" class="workspace-card application-card"><div><strong>{{ t('applications.application') }} #{{ record.id }}</strong><small>{{ t('applications.jobRef') }} #{{ record.jobDescriptionId }} · {{ t('applications.versionRef') }} #{{ record.resumeVersionId }}</small></div><div class="job-actions"><button class="btn-neon btn-ghost" type="button" :disabled="loadingEdit" @click="edit(record)">{{ loadingEdit ? t('applications.loading') : t('applications.editAction') }}</button><button class="danger-action" type="button" :title="t('applications.delete')" :disabled="loadingEdit" @click="remove(record)">{{ t('applications.delete') }}</button></div><select :value="record.status" :aria-label="t('applications.title')" @change="changeStatus(record, ($event.target as HTMLSelectElement).value as ApplicationStatus)"><option v-for="status in allowedStatuses(record.status)" :key="status" :value="status">{{ status }}</option></select><label>{{ t('applications.feedback') }}<textarea :value="feedbackDraft[record.id] ?? record.feedbackText ?? ''" rows="3" @input="feedbackDraft[record.id] = ($event.target as HTMLTextAreaElement).value" /></label><button class="btn-neon btn-secondary" type="button" @click="changeStatus(record, record.status)">{{ t('applications.saveFeedback') }}</button><p v-if="record.coverLetterText">{{ t('applications.cover') }}: {{ record.coverLetterText }}</p><p v-if="record.emailBodyText">{{ t('applications.email') }}: {{ record.emailBodyText }}</p><p v-if="record.openingMessageText">{{ t('applications.opening') }}: {{ record.openingMessageText }}</p></article></div>
    <p v-else class="empty-state">{{ t('applications.empty') }}</p>
  </section>
</template>
