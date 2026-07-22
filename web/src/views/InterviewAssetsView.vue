<script setup lang="ts">
import { onMounted, ref } from 'vue'
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
  <section class="workspace-page">
    <p class="eyebrow">{{ t('assets.eyebrow') }}</p>
    <h1>{{ t('assets.title') }}</h1>
    <p>{{ t('assets.subtitle') }}</p>

    <form class="workspace-card compact-form" @submit.prevent="load">
      <label>{{ t('assets.job') }}<select v-model="jobDescriptionId"><option :value="null">{{ t('assets.allJobs') }}</option><option v-for="job in jobs" :key="job.id" :value="job.id">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <label>{{ t('assets.keyword') }}<input v-model.trim="keyword" :placeholder="t('assets.searchPlaceholder')" /></label>
      <button class="btn-neon btn-ghost" :disabled="loading">{{ t('assets.search') }}</button>
    </form>

    <form class="workspace-card" @submit.prevent="save">
      <h2>{{ editingId === null ? t('assets.create') : t('assets.edit') }}</h2>
      <label>{{ t('assets.question') }}<textarea v-model="question" rows="2" required /></label>
      <label>{{ t('assets.original') }}<textarea v-model="original" rows="5" required /></label>
      <label>{{ t('assets.suggested') }}<textarea v-model="suggestion" rows="5" /></label>
      <div class="job-actions">
        <button class="btn-neon btn-primary" :disabled="saving">{{ saving ? t('assets.saving') : editingId === null ? t('assets.save') : t('assets.saveChanges') }}</button>
        <button v-if="editingId !== null" class="btn-neon btn-ghost" type="button" :disabled="saving" @click="resetForm">{{ t('assets.cancel') }}</button>
      </div>
    </form>

    <p v-if="error" class="form-error">{{ error }}</p>
    <p v-if="loading" class="empty-state">{{ t('assets.loading') }}</p>
    <p v-else-if="assets.length === 0" class="empty-state">{{ t('assets.empty') }}</p>

    <div v-else class="workspace-list">
      <article v-for="asset in assets" :key="asset.id" class="workspace-card">
        <div class="job-actions">
          <h2>{{ asset.questionText }}</h2>
          <div class="job-actions">
            <button class="btn-neon btn-ghost" type="button" @click="edit(asset)">{{ t('assets.editAction') }}</button>
            <button class="danger-action" type="button" :title="t('assets.delete')" @click="remove(asset.id)">{{ t('assets.delete') }}</button>
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
