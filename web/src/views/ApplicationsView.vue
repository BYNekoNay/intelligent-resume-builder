<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { createApplication, deleteApplication, listApplications, updateApplication, updateApplicationStatus, type ApplicationRecord, type ApplicationStatus } from '@/api/application'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'

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
const { resumes, jobs, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load: loadOptions, loadVersions } = useResumeJobOptions()

async function load() {
  loading.value = true
  try { records.value = (await listApplications()).data.data }
  catch { error.value = 'Unable to load application records.' }
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

async function edit(record: ApplicationRecord) {
  if (loadingEdit.value) return
  loadingEdit.value = true
  editingId.value = record.id
  jobDescriptionId.value = String(record.jobDescriptionId)
  coverLetterText.value = record.coverLetterText ?? ''
  emailBodyText.value = record.emailBodyText ?? ''
  openingMessageText.value = record.openingMessageText ?? ''
  try {
    const resumeId = resumes.value.find((resume) => resume.currentVersionId === record.resumeVersionId)?.id
    if (resumeId !== undefined) {
      selectedResumeId.value = resumeId
      await loadVersions()
    } else {
      for (const resume of resumes.value) {
        selectedResumeId.value = resume.id
        await loadVersions()
        if (versions.value.some((version) => version.id === record.resumeVersionId)) break
      }
    }
    resumeVersionId.value = String(record.resumeVersionId)
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
  } catch { error.value = 'Unable to save this application record. Review the selected job and resume version.' }
  finally { saving.value = false }
}

function allowedStatuses(status: ApplicationStatus): ApplicationStatus[] {
  const transitions: Record<ApplicationStatus, ApplicationStatus[]> = { DRAFT: ['DRAFT', 'APPLIED', 'WITHDRAWN'], APPLIED: ['APPLIED', 'INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN'], INTERVIEWING: ['INTERVIEWING', 'OFFERED', 'REJECTED', 'WITHDRAWN'], OFFERED: ['OFFERED'], REJECTED: ['REJECTED'], WITHDRAWN: ['WITHDRAWN'] }
  return transitions[status]
}
async function changeStatus(record: ApplicationRecord, status: ApplicationStatus) {
  try { const updated = (await updateApplicationStatus(record.id, status, record.version, feedbackDraft.value[record.id] ?? record.feedbackText ?? undefined)).data.data; Object.assign(record, updated); feedbackDraft.value[record.id] = record.feedbackText ?? '' }
  catch { error.value = 'Unable to update application status.' }
}
async function remove(record: ApplicationRecord) {
  if (!window.confirm('Delete this application record?')) return
  try { await deleteApplication(record.id); records.value = records.value.filter((item) => item.id !== record.id); if (editingId.value === record.id) resetForm() }
  catch { error.value = 'Unable to delete this application record.' }
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
    const currentResume = resumes.value.find((item) => item.currentVersionId === draft.resumeVersionId)
    if (currentResume) {
      selectedResumeId.value = currentResume.id
      await loadVersions()
    } else {
      for (const resume of resumes.value) {
        selectedResumeId.value = resume.id
        await loadVersions()
        if (versions.value.some((version) => version.id === draft.resumeVersionId)) break
      }
    }
    resumeVersionId.value = String(draft.resumeVersionId)
    if (draft.type === 'COVER_LETTER') coverLetterText.value = draft.text
    if (draft.type === 'EMAIL') emailBodyText.value = draft.text
    if (draft.type === 'OPENING_MESSAGE') openingMessageText.value = draft.text
  } catch { error.value = 'Unable to import the communication draft.' }
})
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">Application tracker</p><h1>Applications</h1><p>Track applications you manage manually. Nothing is sent automatically.</p>
    <form class="workspace-card compact-form" @submit.prevent="save">
      <h2>{{ editingId === null ? 'New application' : 'Edit application' }}</h2>
      <label>Resume<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>Select a resume</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <label>Resume version<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required><option value="" disabled>Select a version</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label>
      <label>Job description<select v-model="jobDescriptionId" :disabled="optionsLoading" required><option value="" disabled>Select a job</option><option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <label>Cover letter<textarea v-model="coverLetterText" rows="4" /></label><label>Email body<textarea v-model="emailBodyText" rows="4" /></label><label>Opening message<textarea v-model="openingMessageText" rows="4" /></label>
      <div class="job-actions"><button class="btn-neon btn-primary" :disabled="saving || loadingEdit || optionsLoading">{{ saving ? 'Saving...' : editingId === null ? 'Create draft' : 'Save changes' }}</button><button v-if="editingId !== null" class="btn-neon btn-ghost" type="button" :disabled="loadingEdit" @click="resetForm">Cancel</button></div>
    </form>
    <p v-if="optionsError || error" class="form-error" role="alert">{{ error || optionsError }}</p><p v-if="loading">Loading application records...</p>
    <div v-else-if="records.length" class="workspace-list"><article v-for="record in records" :key="record.id" class="workspace-card application-card"><div><strong>Application #{{ record.id }}</strong><small>Job #{{ record.jobDescriptionId }} · Resume version #{{ record.resumeVersionId }}</small></div><div class="job-actions"><button class="btn-neon btn-ghost" type="button" :disabled="loadingEdit" @click="edit(record)">{{ loadingEdit ? 'Loading...' : 'Edit' }}</button><button class="danger-action" type="button" title="Delete application record" :disabled="loadingEdit" @click="remove(record)">Delete</button></div><select :value="record.status" aria-label="Application status" @change="changeStatus(record, ($event.target as HTMLSelectElement).value as ApplicationStatus)"><option v-for="status in allowedStatuses(record.status)" :key="status" :value="status">{{ status }}</option></select><label>Feedback<textarea :value="feedbackDraft[record.id] ?? record.feedbackText ?? ''" rows="3" @input="feedbackDraft[record.id] = ($event.target as HTMLTextAreaElement).value" /></label><button class="btn-neon btn-secondary" type="button" @click="changeStatus(record, record.status)">Save feedback</button><p v-if="record.coverLetterText">Cover letter: {{ record.coverLetterText }}</p><p v-if="record.emailBodyText">Email body: {{ record.emailBodyText }}</p><p v-if="record.openingMessageText">Opening message: {{ record.openingMessageText }}</p></article></div>
    <p v-else class="empty-state">No application records yet.</p>
  </section>
</template>
