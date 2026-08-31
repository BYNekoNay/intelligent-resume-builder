<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { Archive, BookmarkPlus, Pencil, Search, Trash2 } from 'lucide-vue-next'
import {
  createInterviewAsset,
  deleteInterviewAsset,
  listInterviewAssets,
  type InterviewAsset,
  updateInterviewAsset,
} from '@/api/interviewAsset'
import { listJobs, type JobDescription } from '@/api/jobDescription'
import { useLocale } from '@/i18n'

const assets = ref<InterviewAsset[]>([])
const question = ref('')
const original = ref('')
const suggestion = ref('')
const editingId = ref<number | null>(null)
const loading = ref(false)
const saving = ref(false)
const error = ref('')
const jobs = ref<JobDescription[]>([])
const jobDescriptionId = ref<number | null>(null)
const keyword = ref('')
const { t } = useLocale()

function resetForm() {
  editingId.value = null
  question.value = ''
  original.value = ''
  suggestion.value = ''
}

function edit(asset: InterviewAsset) {
  editingId.value = asset.id
  question.value = asset.questionText
  original.value = asset.originalAnswerText
  suggestion.value = asset.suggestedAnswerText ?? ''
  error.value = ''
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    assets.value = (await listInterviewAssets({ jobDescriptionId: jobDescriptionId.value ?? undefined, keyword: keyword.value.trim() || undefined })).data.data
  } catch {
    error.value = t('assets.loadError')
  } finally {
    loading.value = false
  }
}

async function save() {
  saving.value = true
  error.value = ''
  const payload = {
    questionText: question.value,
    originalAnswerText: original.value,
    suggestedAnswerText: suggestion.value || undefined,
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
  await Promise.all([load(), listJobs().then((response) => { jobs.value = response.data.data })])
})
</script>

<template>
  <section class="workspace-page assets-page">
    <header class="assets-heading"><div><p class="eyebrow"><Archive :size="14" />{{ t('assets.eyebrow') }}</p><h1>{{ t('assets.title') }}</h1><p class="page-lead">{{ t('assets.subtitle') }}</p></div><span class="asset-count"><strong>{{ assets.length }}</strong>{{ t('assets.assetCount') }}</span></header>

    <form class="asset-filters" @submit.prevent="load">
      <label>{{ t('assets.job') }}<select v-model="jobDescriptionId"><option :value="null">{{ t('assets.allJobs') }}</option><option v-for="job in jobs" :key="job.id" :value="job.id">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <label>{{ t('assets.keyword') }}<input v-model.trim="keyword" :placeholder="t('assets.searchPlaceholder')" /></label>
      <button class="btn-neon btn-ghost" :disabled="loading"><Search :size="16" />{{ t('assets.search') }}</button>
    </form>

    <form class="workspace-card asset-composer" @submit.prevent="save">
      <h2><Pencil :size="17" />{{ editingId === null ? t('assets.create') : t('assets.edit') }}</h2>
      <label>{{ t('assets.question') }}<textarea v-model="question" rows="2" required /></label>
      <label>{{ t('assets.original') }}<textarea v-model="original" rows="5" required /></label>
      <label>{{ t('assets.suggested') }}<textarea v-model="suggestion" rows="5" /></label>
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
.asset-filters { display: grid; grid-template-columns: 1fr 1fr auto; align-items: end; gap: 12px; padding: 14px 16px; border-block: 1px solid var(--border-soft); }.asset-filters label, .asset-composer label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }.asset-filters input, .asset-filters select, .asset-composer textarea { width: 100%; padding: 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 11px; }.asset-filters input:focus, .asset-filters select:focus, .asset-composer textarea:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.asset-composer { display: grid; gap: 14px; padding: 24px; border-left: 4px solid var(--info); }.asset-composer h2 { display: flex; align-items: center; gap: 8px; margin: 0; color: var(--text-primary); font-size: 16px; }.asset-composer .job-actions { justify-content: flex-end; }.asset-card { border-left: 3px solid transparent; }.asset-card:hover { border-left-color: var(--accent); }.asset-card h2 { font-size: 14px; }.asset-card p { color: var(--text-secondary); font-size: 11px; line-height: 1.6; white-space: pre-wrap; }.icon-asset-action { display: grid; width: 32px; height: 32px; place-items: center; padding: 0; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); cursor: pointer; }.icon-asset-action:hover { color: var(--accent); border-color: var(--accent); background: var(--accent-light); }.icon-asset-action.danger:hover { color: var(--danger); border-color: var(--danger); background: var(--danger-light); }
@media (max-width: 680px) { .assets-heading { align-items: stretch; flex-direction: column; }.asset-count { justify-items: start; }.asset-filters { grid-template-columns: 1fr; }.asset-filters .btn-neon { width: 100%; justify-content: center; }.asset-composer { padding: 20px 16px; } }
</style>
