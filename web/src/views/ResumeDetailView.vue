<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { archiveResumeVersion, getResume, listVersions, restoreResumeVersion, setCurrentVersion, unarchiveResumeVersion, updateResumeTitle, type ResumeSummary, type ResumeVersionSummary } from '@/api/resume'
import { listJobs, type JobDescription } from '@/api/jobDescription'
import { scoreMatch } from '@/api/scoring'
import { createExport, type ResumeTemplateCode } from '@/api/export'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const props = defineProps<{ id: string }>()
const resume = ref<ResumeSummary | null>(null)
const versions = ref<ResumeVersionSummary[]>([])
const historyView = ref<'active' | 'archived'>('active')
const associatedJob = ref<JobDescription | null>(null)
const runningAction = ref<number | null>(null)
const editingTitle = ref(false)
const titleDraft = ref('')
const savingTitle = ref(false)
const error = ref('')
const router = useRouter()

const templateNames: Record<ResumeTemplateCode, string> = {
  classic: 'resumeDetail.templateClassic',
  modern: 'resumeDetail.templateModern',
  minimal: 'resumeDetail.templateMinimal',
  ats: 'resumeEditor.templateAts',
  executive: 'resumeEditor.templateExecutive',
  compact: 'resumeEditor.templateCompact',
  academic: 'resumeEditor.templateAcademic',
}

function versionTemplate(version: ResumeVersionSummary): ResumeTemplateCode {
  return version.templateCode ?? 'classic'
}

async function load() {
  const [resumeResponse, versionResponse] = await Promise.all([
    getResume(Number(props.id)),
    listVersions(Number(props.id), false),
  ])
  resume.value = resumeResponse.data.data
  titleDraft.value = resume.value.title
  versions.value = versionResponse.data.data
  associatedJob.value = null
  if (resume.value.jobDescriptionId !== null) {
    const jobs = (await listJobs()).data.data
    associatedJob.value = jobs.find((job) => job.id === resume.value?.jobDescriptionId) ?? null
  }
}

async function loadVersions() {
  const response = await listVersions(Number(props.id), historyView.value === 'archived')
  versions.value = response.data.data
}

async function switchHistoryView(view: 'active' | 'archived') {
  if (historyView.value === view) return
  historyView.value = view
  error.value = ''
  try { await loadVersions() }
  catch { error.value = t('resumeDetail.historyLoadError') }
}

onMounted(async () => {
  try { await load() }
  catch { error.value = t('resumeDetail.loadError') }
})

function startTitleEdit() {
  titleDraft.value = resume.value?.title ?? ''
  editingTitle.value = true
}

function cancelTitleEdit() {
  titleDraft.value = resume.value?.title ?? ''
  editingTitle.value = false
}

async function saveTitle() {
  if (!titleDraft.value.trim()) { error.value = t('resumeDetail.titleEmpty'); return }
  savingTitle.value = true; error.value = ''
  try {
    const response = await updateResumeTitle(Number(props.id), titleDraft.value.trim())
    resume.value = response.data.data
    titleDraft.value = resume.value.title
    editingTitle.value = false
  } catch { error.value = t('resumeDetail.titleSaveError') }
  finally { savingTitle.value = false }
}

async function makeCurrent(version: ResumeVersionSummary) {
  if (resume.value?.currentVersionId === version.id) return
  if (!window.confirm(t('resumeDetail.switchConfirm').replace('{no}', String(version.versionNo)))) return
  runningAction.value = version.id; error.value = ''
  try { await setCurrentVersion(Number(props.id), version.id); await load() }
  catch { error.value = t('resumeDetail.switchCurrentError') }
  finally { runningAction.value = null }
}

async function restore(version: ResumeVersionSummary) {
  if (!window.confirm(t('resumeDetail.restoreConfirm').replace('{no}', String(version.versionNo)))) return
  runningAction.value = version.id; error.value = ''
  try {
    await restoreResumeVersion(Number(props.id), version.id)
    historyView.value = 'active'
    await load()
  } catch { error.value = t('resumeDetail.restoreError') }
  finally { runningAction.value = null }
}

async function archive(version: ResumeVersionSummary) {
  if (!window.confirm(t('resumeDetail.archiveConfirm').replace('{no}', String(version.versionNo)))) return
  runningAction.value = version.id; error.value = ''
  try { await archiveResumeVersion(Number(props.id), version.id); await loadVersions() }
  catch { error.value = t('resumeDetail.archiveError') }
  finally { runningAction.value = null }
}

async function unarchive(version: ResumeVersionSummary) {
  if (!window.confirm(t('resumeDetail.unarchiveConfirm').replace('{no}', String(version.versionNo)))) return
  runningAction.value = version.id; error.value = ''
  try { await unarchiveResumeVersion(Number(props.id), version.id); await loadVersions() }
  catch { error.value = t('resumeDetail.unarchiveError') }
  finally { runningAction.value = null }
}

async function score(version: ResumeVersionSummary) {
  const jobId = resume.value?.jobDescriptionId
  if (!jobId) return
  runningAction.value = version.id; error.value = ''
  try {
    const response = await scoreMatch(version.id, jobId)
    await router.push({ name: 'match-result', params: { matchResultId: response.data.data.matchResultId } })
  } catch { error.value = t('resumeDetail.scoreError') }
  finally { runningAction.value = null }
}

async function exportPdf(version: ResumeVersionSummary) {
  runningAction.value = version.id; error.value = ''
  try {
    const response = await createExport(version.id, versionTemplate(version))
    await router.push({ name: 'export', params: { exportTaskId: response.data.data.taskId } })
  } catch { error.value = t('resumeDetail.exportError') }
  finally { runningAction.value = null }
}
</script>

<template>
  <section class="workspace-page">
    <div class="page-heading">
      <div class="resume-title-block">
        <h1>{{ resume?.title ?? t('resumeDetail.fallbackTitle').replace('{id}', props.id) }} {{ t('resumeDetail.title') }}</h1>
        <button v-if="!editingTitle" class="text-link" type="button" @click="startTitleEdit">{{ t('resumeDetail.rename') }}</button>
      </div>
      <RouterLink class="btn-neon btn-primary" :to="{ name: 'resume-editor', params: { id: props.id } }">{{ t('resumeDetail.editNewVersion') }}</RouterLink>
    </div>
    <form v-if="editingTitle" class="workspace-card title-edit-form" @submit.prevent="saveTitle">
      <label>{{ t('resumeDetail.resumeTitle') }}<input v-model.trim="titleDraft" required maxlength="255" autofocus /></label>
      <div class="job-actions">
        <button class="btn-neon btn-ghost" type="button" :disabled="savingTitle" @click="cancelTitleEdit">{{ t('resumeDetail.cancel') }}</button>
        <button class="btn-neon btn-primary" :disabled="savingTitle">{{ savingTitle ? t('resumeDetail.saving') : t('resumeDetail.saveTitle') }}</button>
      </div>
    </form>
    <div v-if="associatedJob" class="workspace-card"><strong>{{ t('resumeDetail.linkedJob') }}</strong>{{ associatedJob.title }}{{ associatedJob.companyName ? ` · ${associatedJob.companyName}` : '' }}</div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div class="version-history-tabs" role="tablist" :aria-label="t('resumeDetail.historyFilter')">
      <button type="button" :class="{ active: historyView === 'active' }" :aria-selected="historyView === 'active'" @click="switchHistoryView('active')">{{ t('resumeDetail.activeHistory') }}</button>
      <button type="button" :class="{ active: historyView === 'archived' }" :aria-selected="historyView === 'archived'" @click="switchHistoryView('archived')">{{ t('resumeDetail.archivedHistory') }}</button>
    </div>
    <p v-if="!versions.length" class="empty-state">{{ t('resumeDetail.noVersions') }}</p>
    <div v-else class="job-list">
      <article v-for="v in versions" :key="v.id" class="workspace-card version-card">
        <div>
          <h2>v{{ v.versionNo }} · {{ v.sourceType }}
            <span class="template-badge">{{ t(templateNames[versionTemplate(v)]) }}{{ t('resumeDetail.templateSuffix') }}</span>
            <span v-if="resume?.currentVersionId === v.id" class="current-version">{{ t('resumeDetail.currentVersion') }}</span>
          </h2>
          <p>{{ v.createdAt }}</p>
        </div>
        <div class="job-actions">
          <template v-if="historyView === 'active'">
            <button v-if="resume?.currentVersionId !== v.id" class="btn-neon btn-ghost" :disabled="runningAction !== null" @click="makeCurrent(v)">{{ t('resumeDetail.setCurrent') }}</button>
            <button class="btn-neon btn-ghost" :disabled="runningAction !== null" @click="restore(v)">{{ t('resumeDetail.restoreAction') }}</button>
            <button v-if="resume?.currentVersionId !== v.id" class="btn-neon btn-ghost" :disabled="runningAction !== null" @click="archive(v)">{{ t('resumeDetail.archiveAction') }}</button>
            <button v-if="resume?.jobDescriptionId" class="btn-neon btn-ghost" :disabled="runningAction !== null" @click="score(v)">{{ t('resumeDetail.viewScore') }}</button>
            <button class="btn-neon btn-primary" :disabled="runningAction !== null" @click="exportPdf(v)">{{ runningAction === v.id ? t('resumeDetail.creatingTask') : t('resumeDetail.exportPdf') }}</button>
          </template>
          <template v-else>
            <button class="btn-neon btn-ghost" :disabled="runningAction !== null" @click="unarchive(v)">{{ t('resumeDetail.unarchiveAction') }}</button>
            <button class="btn-neon btn-primary" :disabled="runningAction !== null" @click="restore(v)">{{ t('resumeDetail.restoreAction') }}</button>
          </template>
        </div>
      </article>
    </div>
  </section>
</template>
