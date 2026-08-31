<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Archive, BookmarkPlus, Pencil, Search, Trash2 } from 'lucide-vue-next'
import {
  createInterviewAsset,
  deleteInterviewAsset,
  listInterviewAssets,
  type InterviewAsset,
  type InterviewAssetPayload,
  updateInterviewAsset,
} from '@/api/interviewAsset'
import { listJobs, type JobDescription } from '@/api/jobDescription'
import { listMaterials, type CareerMaterialSummary } from '@/api/careerMaterial'
import { SECTION_KEYS, type SectionKey } from '@/resume/sectionRegistry'
import { useLocale } from '@/i18n'

const assets = ref<InterviewAsset[]>([])
const question = ref('')
const original = ref('')
const suggestion = ref('')
const selectedSectionKeys = ref<SectionKey[]>([])
const selectedMaterialIds = ref<number[]>([])
const editingId = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const jobs = ref<JobDescription[]>([])
const materials = ref<CareerMaterialSummary[]>([])
const jobDescriptionId = ref<number | null>(null)
const sectionKeyFilter = ref<string>('')
const keyword = ref('')
const { t } = useLocale()

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

function sectionLabel(key: string) {
  return sectionLabels[key] ?? key
}

function resetForm() {
  editingId.value = null
  question.value = ''
  original.value = ''
  suggestion.value = ''
  selectedSectionKeys.value = []
  selectedMaterialIds.value = []
}

function toggleSectionKey(key: SectionKey) {
  if (selectedSectionKeys.value.includes(key)) {
    selectedSectionKeys.value = selectedSectionKeys.value.filter((item) => item !== key)
  } else {
    selectedSectionKeys.value = [...selectedSectionKeys.value, key]
  }
}

function toggleMaterial(id: number) {
  if (selectedMaterialIds.value.includes(id)) {
    selectedMaterialIds.value = selectedMaterialIds.value.filter((item) => item !== id)
  } else {
    selectedMaterialIds.value = [...selectedMaterialIds.value, id]
  }
}

function edit(asset: InterviewAsset) {
  editingId.value = asset.id
  question.value = asset.questionText
  original.value = asset.originalAnswerText
  suggestion.value = asset.suggestedAnswerText ?? ''
  selectedSectionKeys.value = asset.sectionKeys.filter((key): key is SectionKey => (SECTION_KEYS as readonly string[]).includes(key))
  selectedMaterialIds.value = asset.materialIds ?? []
  error.value = ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    assets.value = (await listInterviewAssets({
      jobDescriptionId: jobDescriptionId.value ?? undefined,
      keyword: keyword.value.trim() || undefined,
      sectionKey: sectionKeyFilter.value || undefined,
    })).data.data
  } catch {
    error.value = t('assets.loadError')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  const payload: InterviewAssetPayload = {
    questionText: question.value,
    originalAnswerText: original.value,
    suggestedAnswerText: suggestion.value || undefined,
    sectionKeys: selectedSectionKeys.value.length ? selectedSectionKeys.value : undefined,
    materialIds: selectedMaterialIds.value.length ? selectedMaterialIds.value : undefined,
  }
  try {
    if (editingId.value === null) {
      const item = (await createInterviewAsset(payload)).data.data
      assets.value.unshift(item)
    } else {
      const item = (await updateInterviewAsset(editingId.value, payload)).data.data
      assets.value = assets.value.map((asset) => asset.id === item.id ? item : asset)
    }
    resetForm()
  } catch {
    error.value = t('assets.saveError')
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  if (!window.confirm(t('assets.confirmDelete'))) return
  error.value = ''
  try {
    await deleteInterviewAsset(id)
    assets.value = assets.value.filter((asset) => asset.id !== id)
    if (editingId.value === id) resetForm()
  } catch {
    error.value = t('assets.deleteError')
  }
}

onMounted(async () => {
  await Promise.all([
    load(),
    listJobs().then((response) => { jobs.value = response.data.data }),
    listMaterials().then((response) => { materials.value = response.data.data }).catch(() => { materials.value = [] }),
  ])
})
</script>

<template>
  <section class="workspace-page assets-page">
    <header class="assets-heading"><div><p class="eyebrow"><Archive :size="14" />{{ t('assets.eyebrow') }}</p><h1>{{ t('assets.title') }}</h1><p class="page-lead">{{ t('assets.subtitle') }}</p></div><span class="asset-count"><strong>{{ assets.length }}</strong>{{ t('assets.assetCount') }}</span></header>

    <form class="asset-filters" @submit.prevent="load">
      <label>{{ t('assets.job') }}<select v-model="jobDescriptionId"><option :value="null">{{ t('assets.allJobs') }}</option><option v-for="job in jobs" :key="job.id" :value="job.id">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <label>{{ t('assets.sectionFilter') }}<select v-model="sectionKeyFilter"><option value="">{{ t('assets.allSections') }}</option><option v-for="key in SECTION_KEYS" :key="key" :value="key">{{ sectionLabel(key) }}</option></select></label>
      <label>{{ t('assets.keyword') }}<input v-model.trim="keyword" :placeholder="t('assets.searchPlaceholder')" /></label>
      <button class="btn-neon btn-ghost" :disabled="loading"><Search :size="16" />{{ t('assets.search') }}</button>
    </form>

    <form class="workspace-card asset-composer" @submit.prevent="save">
      <h2><Pencil :size="17" />{{ editingId === null ? t('assets.create') : t('assets.edit') }}</h2>
      <label>{{ t('assets.question') }}<textarea v-model="question" rows="2" required /></label>
      <label>{{ t('assets.original') }}<textarea v-model="original" rows="5" required /></label>
      <label>{{ t('assets.suggested') }}<textarea v-model="suggestion" rows="5" /></label>
      <fieldset class="section-picker">
        <legend>{{ t('assets.relatedSections') }}</legend>
        <div class="section-options">
          <label v-for="key in SECTION_KEYS" :key="key" class="section-option">
            <input type="checkbox" :checked="selectedSectionKeys.includes(key)" @change="toggleSectionKey(key)" />{{ sectionLabel(key) }}
          </label>
        </div>
      </fieldset>
      <fieldset class="section-picker">
        <legend>{{ t('assets.relatedMaterials') }}</legend>
        <div class="section-options">
          <label v-for="material in materials" :key="material.id" class="section-option">
            <input type="checkbox" :checked="selectedMaterialIds.includes(material.id)" @change="toggleMaterial(material.id)" />{{ material.title }}
          </label>
          <p v-if="!materials.length" class="picker-empty">{{ t('assets.noMaterials') }}</p>
        </div>
      </fieldset>
      <div class="job-actions">
        <button class="btn-neon btn-primary" :disabled="saving"><BookmarkPlus :size="16" />{{ saving ? t('assets.saving') : editingId === null ? t('assets.save') : t('assets.saveChanges') }}</button>
        <button v-if="editingId !== null" class="btn-neon btn-ghost" type="button" :disabled="saving" @click="resetForm">{{ t('assets.cancel') }}</button>
      </div>
    </form>

    <p v-if="error" class="form-error">{{ error }}</p>
    <p v-if="loading" class="empty-state">{{ t('assets.loading') }}</p>
    <p v-else-if="assets.length === 0" class="empty-state">{{ t('assets.empty') }}</p>

    <div v-else class="workspace-list">
      <article v-for="asset in assets" :key="asset.id" class="workspace-card asset-card">
        <div class="job-actions">
          <h2>{{ asset.questionText }}</h2>
          <div class="job-actions">
            <button class="icon-asset-action" type="button" :title="t('assets.editAction')" :aria-label="t('assets.editAction')" @click="edit(asset)"><Pencil :size="15" /></button>
            <button class="icon-asset-action danger" type="button" :title="t('assets.delete')" :aria-label="t('assets.delete')" @click="remove(asset.id)"><Trash2 :size="15" /></button>
          </div>
        </div>
        <div v-if="asset.sectionKeys.length" class="asset-tags">
          <span v-for="key in asset.sectionKeys" :key="key" class="asset-tag">{{ sectionLabel(key) }}</span>
        </div>
        <h3>{{ t('assets.original') }}</h3>
        <p>{{ asset.originalAnswerText }}</p>
        <template v-if="asset.suggestedAnswerText">
          <h3>{{ t('assets.suggested') }}</h3>
          <p>{{ asset.suggestedAnswerText }}</p>
        </template>
      </article>
    </div>
  </section>
</template>

<style scoped>
.assets-page { width: min(100%, 980px); max-width: 980px; gap: 24px; }.assets-heading { display: flex; align-items: end; justify-content: space-between; gap: 24px; padding-bottom: 22px; border-bottom: 1px solid var(--border); }.assets-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }.assets-heading .page-lead { max-width: 650px; font-size: 12px; }.asset-count { display: grid; justify-items: end; color: var(--text-tertiary); font-size: 9px; }.asset-count strong { color: var(--accent); font-family: var(--font-utility); font-size: 22px; }
.asset-filters { display: grid; grid-template-columns: 1fr 1fr 1fr auto; align-items: end; gap: 12px; padding: 14px 16px; border-block: 1px solid var(--border-soft); }.asset-filters label, .asset-composer label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }.asset-filters input, .asset-filters select, .asset-composer textarea { width: 100%; padding: 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 11px; }.asset-filters input:focus, .asset-filters select:focus, .asset-composer textarea:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.asset-composer { display: grid; gap: 14px; padding: 24px; border-left: 4px solid var(--info); }.asset-composer h2 { display: flex; align-items: center; gap: 8px; margin: 0; color: var(--text-primary); font-size: 16px; }.asset-composer .job-actions { justify-content: flex-end; }.section-picker { display: grid; gap: 8px; margin: 0; padding: 10px 12px; border: 1px solid var(--border-soft); border-radius: 6px; }.section-picker legend { padding: 0 6px; color: var(--text-secondary); font-size: 10px; font-weight: 700; }.section-options { display: flex; flex-wrap: wrap; gap: 6px; }.section-option { display: inline-flex; align-items: center; gap: 5px; padding: 4px 8px; border: 1px solid var(--border); border-radius: 13px; color: var(--text-secondary); font-size: 10px; cursor: pointer; }.section-option input { accent-color: var(--accent); }.picker-empty { margin: 0; color: var(--text-tertiary); font-size: 10px; }
.asset-card { border-left: 3px solid transparent; }.asset-card:hover { border-left-color: var(--accent); }.asset-card h2 { font-size: 14px; }.asset-card p { color: var(--text-secondary); font-size: 11px; line-height: 1.6; white-space: pre-wrap; }.asset-tags { display: flex; flex-wrap: wrap; gap: 5px; margin: 8px 0; }.asset-tag { padding: 3px 8px; border-radius: 11px; color: var(--accent); background: var(--accent-light); font-size: 9px; font-weight: 700; }.icon-asset-action { display: grid; width: 32px; height: 32px; place-items: center; padding: 0; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); cursor: pointer; }.icon-asset-action:hover { color: var(--accent); border-color: var(--accent); background: var(--accent-light); }.icon-asset-action.danger:hover { color: var(--danger); border-color: var(--danger); background: var(--danger-light); }
@media (max-width: 680px) { .assets-heading { align-items: stretch; flex-direction: column; }.asset-count { justify-items: start; }.asset-filters { grid-template-columns: 1fr; }.asset-filters .btn-neon { width: 100%; justify-content: center; }.asset-composer { padding: 20px 16px; } }
</style>
