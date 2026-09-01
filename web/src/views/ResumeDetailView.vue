<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { Archive, ArrowLeft, BriefcaseBusiness, CheckCircle2, Download, FileClock, GitCompareArrows, Pencil, Plus, RotateCcw } from 'lucide-vue-next'
import { archiveResumeVersion, getResume, listVersions, restoreResumeVersion, setCurrentVersion, unarchiveResumeVersion, updateResumeTitle, type ResumeSummary, type ResumeVersionSummary } from '@/api/resume'
import { listJobs, type JobDescription } from '@/api/jobDescription'
import { listInterviewAssets, type InterviewAsset } from '@/api/interviewAsset'
import { SECTION_KEYS, type SectionKey } from '@/resume/sectionRegistry'
import { scoreMatch } from '@/api/scoring'
import { createExport, type ResumeTemplateCode } from '@/api/export'
import { useLocale } from '@/i18n'

const { locale, t } = useLocale()
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

const relatedAssets = ref<InterviewAsset[]>([])
const relatedSectionKey = ref<string>('')
const sectionLabels: Record<string, string> = {
  basics: t('resumeEditor.basicsLabel'),
  objective: t('resumeEditor.objectiveLabel'),
  links: t('resumeEditor.linksLabel'),
  work: t('resumeEditor.workLabel'),
  volunteering: t('resumeEditor.volunteeringLabel'),
  skills: t('resumeEditor.skillsLabel'),
  projects: t('resumeEditor.projectsLabel'),
  education: t('resumeEditor.educationLabel'),
  courses: t('resumeEditor.coursesLabel'),
  certificates: t('resumeEditor.certificatesLabel'),
  publications: t('resumeEditor.publicationsLabel'),
  awards: t('resumeEditor.awardsLabel'),
  languages: t('resumeEditor.languagesLabel'),
  customSections: t('resumeEditor.customSectionsLabel'),
}

const visibleRelatedAssets = computed(() => {
  if (!relatedSectionKey.value) return relatedAssets.value
  return relatedAssets.value.filter((asset) => asset.sectionKeys.includes(relatedSectionKey.value))
})

function versionTemplate(version: ResumeVersionSummary): ResumeTemplateCode {
  return version.templateCode ?? 'classic'
}

function formatDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(locale.value, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}

function sourceLabel(source: ResumeVersionSummary['sourceType']) {
  return t({
    MANUAL: 'resumeDetail.sourceManual', AI_OPTIMIZED: 'resumeDetail.sourceAiOptimized', JD_CUSTOMIZED: 'resumeDetail.sourceJdCustomized',
    MATERIAL_CUSTOMIZED: 'resumeDetail.sourceMaterialCustomized', RESTORED: 'resumeDetail.sourceRestored',
  }[source])
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
  try {
    await load()
    await loadRelatedAssets()
  } catch { error.value = t('resumeDetail.loadError') }
})

async function loadRelatedAssets() {
  try {
    // 与 ResumeEditorView 对齐：选中章节时后端按 sectionKey 过滤，避免全量拉取后再前端筛选
    relatedAssets.value = (await listInterviewAssets(relatedSectionKey.value ? { sectionKey: relatedSectionKey.value } : undefined)).data.data
  } catch {
    relatedAssets.value = []
  }
}

// 章节筛选变化时重新从后端按 sectionKey 拉取
watch(relatedSectionKey, () => {
  void loadRelatedAssets()
})

function compareVersion(version: ResumeVersionSummary) {
  const base = resume.value?.currentVersionId ?? version.id
  void router.push({
    name: 'resume-compare',
    params: { id: props.id },
    query: { base: String(base), compare: String(version.id) },
  })
}

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
  <section class="workspace-page resume-history-page">
    <RouterLink class="history-back-link" :to="{ name: 'resume-list' }"><ArrowLeft :size="15" />{{ t('resumeDetail.backToList') }}</RouterLink>
    <header class="history-heading">
      <div>
        <p class="eyebrow"><FileClock :size="14" /> {{ t('resumeDetail.title') }}</p>
        <div class="resume-title-block">
          <h1>{{ resume?.title ?? t('resumeDetail.fallbackTitle').replace('{id}', props.id) }}</h1>
          <button v-if="!editingTitle" class="rename-action" type="button" :title="t('resumeDetail.rename')" @click="startTitleEdit"><Pencil :size="15" /><span>{{ t('resumeDetail.rename') }}</span></button>
        </div>
        <p class="page-lead">{{ t('resumeDetail.subtitle') }}</p>
      </div>
      <RouterLink class="btn-neon btn-primary" :to="{ name: 'resume-editor', params: { id: props.id } }"><Plus :size="16" />{{ t('resumeDetail.editNewVersion') }}</RouterLink>
    </header>
    <form v-if="editingTitle" class="title-edit-form history-edit-title" @submit.prevent="saveTitle">
      <label>{{ t('resumeDetail.resumeTitle') }}<input v-model.trim="titleDraft" required maxlength="255" autofocus /></label>
      <div class="job-actions">
        <button class="btn-neon btn-ghost" type="button" :disabled="savingTitle" @click="cancelTitleEdit">{{ t('resumeDetail.cancel') }}</button>
        <button class="btn-neon btn-primary" :disabled="savingTitle">{{ savingTitle ? t('resumeDetail.saving') : t('resumeDetail.saveTitle') }}</button>
      </div>
    </form>
    <div v-if="associatedJob" class="linked-job-band"><BriefcaseBusiness :size="18" /><div><small>{{ t('resumeDetail.linkedJob') }}</small><strong>{{ associatedJob.title }}{{ associatedJob.companyName ? ` · ${associatedJob.companyName}` : '' }}</strong></div></div>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <section class="history-collection" aria-labelledby="version-history-title">
      <header class="history-toolbar">
        <div><p class="section-kicker">{{ t('resumeDetail.collectionEyebrow') }}</p><h2 id="version-history-title">{{ historyView === 'active' ? t('resumeDetail.activeHistory') : t('resumeDetail.archivedHistory') }}</h2></div>
        <div class="version-history-tabs" role="tablist" :aria-label="t('resumeDetail.historyFilter')">
          <button type="button" :class="{ active: historyView === 'active' }" :aria-selected="historyView === 'active'" @click="switchHistoryView('active')">{{ t('resumeDetail.activeHistory') }}</button>
          <button type="button" :class="{ active: historyView === 'archived' }" :aria-selected="historyView === 'archived'" @click="switchHistoryView('archived')">{{ t('resumeDetail.archivedHistory') }}</button>
        </div>
      </header>
      <p v-if="!versions.length" class="history-empty">{{ t('resumeDetail.noVersions') }}</p>
      <div v-else class="version-list">
        <article v-for="v in versions" :key="v.id" class="version-row version-card">
          <div class="version-number"><span>v{{ v.versionNo }}</span><CheckCircle2 v-if="resume?.currentVersionId === v.id" :size="15" /></div>
          <div class="version-copy">
            <div class="version-meta"><span>{{ sourceLabel(v.sourceType) }}</span><span>{{ t(templateNames[versionTemplate(v)]) }}{{ t('resumeDetail.templateSuffix') }}</span><span v-if="resume?.currentVersionId === v.id" class="current-version">{{ t('resumeDetail.currentVersion') }}</span></div>
            <strong>{{ v.optimizationSummary || t('resumeDetail.versionFallbackSummary') }}</strong>
            <small>{{ formatDate(v.createdAt) }}</small>
          </div>
          <div class="job-actions version-actions">
          <template v-if="historyView === 'active'">
            <button v-if="resume?.currentVersionId !== v.id" class="btn-neon btn-ghost" :disabled="runningAction !== null" @click="makeCurrent(v)">{{ t('resumeDetail.setCurrent') }}</button>
            <button class="icon-history-action" :title="t('resumeDetail.restoreAction')" :aria-label="`${t('resumeDetail.restoreAction')} v${v.versionNo}`" :disabled="runningAction !== null" @click="restore(v)"><RotateCcw :size="15" /></button>
            <button class="icon-history-action" :title="t('resumeDetail.compareAction')" :aria-label="`${t('resumeDetail.compareAction')} v${v.versionNo}`" :disabled="runningAction !== null" @click="compareVersion(v)"><GitCompareArrows :size="15" /></button>
            <button v-if="resume?.currentVersionId !== v.id" class="icon-history-action" :title="t('resumeDetail.archiveAction')" :aria-label="`${t('resumeDetail.archiveAction')} v${v.versionNo}`" :disabled="runningAction !== null" @click="archive(v)"><Archive :size="15" /></button>
            <button v-if="resume?.jobDescriptionId" class="btn-neon btn-ghost" :disabled="runningAction !== null" @click="score(v)">{{ t('resumeDetail.viewScore') }}</button>
            <button class="btn-neon btn-primary" :disabled="runningAction !== null" @click="exportPdf(v)"><Download v-if="runningAction !== v.id" :size="15" />{{ runningAction === v.id ? t('resumeDetail.creatingTask') : t('resumeDetail.exportPdf') }}</button>
          </template>
          <template v-else>
            <button class="btn-neon btn-ghost" :disabled="runningAction !== null" @click="unarchive(v)">{{ t('resumeDetail.unarchiveAction') }}</button>
            <button class="btn-neon btn-primary" :disabled="runningAction !== null" @click="restore(v)"><RotateCcw :size="15" />{{ t('resumeDetail.restoreAction') }}</button>
          </template>
          </div>
        </article>
      </div>
    </section>

    <section class="related-assets" aria-labelledby="related-assets-title">
      <header class="history-toolbar">
        <div><p class="section-kicker">{{ t('resumeDetail.assetsEyebrow') }}</p><h2 id="related-assets-title">{{ t('resumeDetail.assetsTitle') }}</h2></div>
        <select v-model="relatedSectionKey" class="assets-section-filter" :aria-label="t('assets.sectionFilter')">
          <option value="">{{ t('resumeDetail.assetsAll') }}</option>
          <option v-for="key in SECTION_KEYS" :key="key" :value="key">{{ sectionLabels[key] }}</option>
        </select>
      </header>
      <p v-if="!visibleRelatedAssets.length" class="history-empty">{{ t('resumeDetail.assetsEmpty') }}</p>
      <div v-else class="asset-panel-list">
        <article v-for="asset in visibleRelatedAssets" :key="asset.id" class="asset-panel-card">
          <h3>{{ asset.questionText }}</h3>
          <div v-if="asset.sectionKeys.length" class="asset-tags">
            <span v-for="key in asset.sectionKeys" :key="key" class="asset-tag">{{ sectionLabels[key] }}</span>
          </div>
          <p>{{ asset.originalAnswerText }}</p>
        </article>
      </div>
    </section>
  </section>
</template>

<style scoped>
.resume-history-page { width: min(100%, 1040px); max-width: 1040px; gap: 24px; }
.history-back-link { display: inline-flex; align-items: center; gap: 6px; justify-self: start; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.history-back-link:hover { color: var(--accent); text-decoration: none; }
.history-heading { display: flex; align-items: end; justify-content: space-between; gap: 24px; padding-bottom: 23px; border-bottom: 1px solid var(--border); }
.resume-title-block { gap: 9px; }
.resume-title-block h1 { margin: 5px 0 6px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.rename-action { display: inline-flex; align-items: center; gap: 5px; min-height: 30px; padding: 4px 7px; border: 1px solid transparent; border-radius: 5px; color: var(--text-tertiary); background: transparent; font-size: 10px; font-weight: 650; cursor: pointer; }
.rename-action:hover { border-color: var(--border); color: var(--accent); background: var(--accent-light); }
.history-heading .page-lead { max-width: 620px; font-size: 12px; }
.history-edit-title { padding: 18px 20px; border: 1px solid var(--border); border-left: 4px solid var(--highlight); border-radius: 7px; background: var(--bg-surface); }
.linked-job-band { display: grid; grid-template-columns: 38px minmax(0, 1fr); align-items: center; gap: 12px; padding: 14px 16px; border: 1px solid color-mix(in srgb, var(--info) 25%, var(--border)); border-radius: 7px; color: var(--info); background: var(--info-light); }
.linked-job-band > svg { justify-self: center; }
.linked-job-band div { display: grid; gap: 2px; }
.linked-job-band small { color: var(--text-secondary); font-size: 9px; }
.linked-job-band strong { color: var(--text-primary); font-size: 12px; }
.history-collection { display: grid; gap: 0; }
.history-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-bottom: 13px; border-bottom: 1px solid var(--border); }
.section-kicker { margin: 0 0 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.history-toolbar h2 { margin: 0; color: var(--text-primary); font-size: 16px; }
.version-history-tabs { padding: 3px; border-radius: 6px; box-shadow: none; }
.version-history-tabs button { min-height: 30px; padding: 5px 9px; border-radius: 4px; font-size: 10px; font-weight: 650; }
.history-empty { margin: 0; padding: 28px 4px; border-bottom: 1px solid var(--border); color: var(--text-secondary); font-size: 11px; }
.version-list { display: grid; }
.version-row { display: grid; grid-template-columns: 58px minmax(0, 1fr) minmax(220px, auto); align-items: center; gap: 16px; min-height: 98px; padding: 15px 4px; border-bottom: 1px solid var(--border); }
.version-number { display: grid; justify-items: center; gap: 5px; color: var(--accent); }
.version-number span { font-family: var(--font-utility); font-size: 15px; font-weight: 700; }
.version-copy { display: grid; min-width: 0; gap: 4px; }
.version-meta { display: flex; align-items: center; flex-wrap: wrap; gap: 5px; }
.version-meta > span { min-height: 20px; padding: 3px 6px; border: 1px solid var(--border); border-radius: 4px; color: var(--text-secondary); background: var(--bg-surface); font-size: 8px; font-weight: 700; }
.version-meta .current-version { margin: 0; border-color: color-mix(in srgb, var(--success) 30%, var(--border)); color: var(--success); background: var(--success-light); }
.version-copy > strong { overflow: hidden; color: var(--text-primary); font-size: 12px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.version-copy > small { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; }
.version-actions { flex-wrap: wrap; }
.version-actions .btn-neon { min-height: 32px; padding: 0 9px; font-size: 9px; }
.icon-history-action { display: grid; width: 32px; height: 32px; place-items: center; padding: 0; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); cursor: pointer; }
.icon-history-action:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.related-assets { display: grid; gap: 0; margin-top: 26px; padding-top: 4px; }
.assets-section-filter { min-height: 32px; padding: 0 9px; border: 1px solid var(--border); border-radius: 5px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 10px; }
.asset-panel-list { display: grid; gap: 10px; padding-top: 12px; }
.asset-panel-card { padding: 13px 15px; border: 1px solid var(--border); border-left: 3px solid var(--info); border-radius: 6px; background: var(--bg-surface); }
.asset-panel-card h3 { margin: 0 0 6px; font-size: 12px; }
.asset-panel-card p { margin: 0; color: var(--text-secondary); font-size: 11px; line-height: 1.6; white-space: pre-wrap; }
.asset-tags { display: flex; flex-wrap: wrap; gap: 5px; margin-bottom: 7px; }
.asset-tag { padding: 3px 8px; border-radius: 11px; color: var(--accent); background: var(--accent-light); font-size: 9px; font-weight: 700; }
@media (max-width: 820px) { .history-heading { align-items: stretch; flex-direction: column; } .history-heading .btn-neon { align-self: start; } .version-row { grid-template-columns: 52px minmax(0, 1fr); } .version-actions { grid-column: 2; justify-content: flex-start; } }
@media (max-width: 560px) { .resume-title-block h1 { font-size: 29px; } .rename-action span { display: none; } .history-heading .btn-neon { width: 100%; justify-content: center; } .history-toolbar { align-items: stretch; flex-direction: column; } .version-history-tabs { width: 100%; } .version-history-tabs button { flex: 1; } .version-row { align-items: start; grid-template-columns: 42px minmax(0, 1fr); gap: 10px; } .version-actions { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } .version-actions .btn-neon { width: 100%; justify-content: center; } }
</style>
