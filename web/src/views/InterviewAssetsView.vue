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
    error.value = 'Unable to load answer assets. Please try again.'
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
    error.value = 'Unable to save this answer asset. Check the required fields and try again.'
  } finally {
    saving.value = false
  }
}

async function remove(id: number) {
  if (!window.confirm('Delete this answer asset?')) return
  error.value = ''
  try {
    await deleteInterviewAsset(id)
    assets.value = assets.value.filter((asset) => asset.id !== id)
    if (editingId.value === id) resetForm()
  } catch {
    error.value = 'Unable to delete this answer asset. Please try again.'
  }
}

onMounted(async () => {
  await Promise.all([load(), listJobs().then((response) => { jobs.value = response.data.data })])
})
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">Answer library</p>
    <h1>Interview Answer Assets</h1>
    <p>Keep your original response and AI suggestion separate for honest interview review.</p>

    <form class="workspace-card compact-form" @submit.prevent="load">
      <label>Job<select v-model="jobDescriptionId"><option :value="null">All jobs</option><option v-for="job in jobs" :key="job.id" :value="job.id">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <label>Keyword<input v-model.trim="keyword" placeholder="Search question, suggestion, or feedback" /></label>
      <button class="btn-neon btn-ghost" :disabled="loading">Search</button>
    </form>

    <form class="workspace-card" @submit.prevent="save">
      <h2>{{ editingId === null ? 'New answer asset' : 'Edit answer asset' }}</h2>
      <label>Question<textarea v-model="question" rows="2" required /></label>
      <label>Original answer<textarea v-model="original" rows="5" required /></label>
      <label>Suggested answer<textarea v-model="suggestion" rows="5" /></label>
      <div class="job-actions">
        <button class="btn-neon btn-primary" :disabled="saving">{{ saving ? 'Saving...' : editingId === null ? 'Save asset' : 'Save changes' }}</button>
        <button v-if="editingId !== null" class="btn-neon btn-ghost" type="button" :disabled="saving" @click="resetForm">Cancel</button>
      </div>
    </form>

    <p v-if="error" class="form-error">{{ error }}</p>
    <p v-if="loading" class="empty-state">Loading answer assets...</p>
    <p v-else-if="assets.length === 0" class="empty-state">No answer assets yet.</p>

    <div v-else class="workspace-list">
      <article v-for="asset in assets" :key="asset.id" class="workspace-card">
        <div class="job-actions">
          <h2>{{ asset.questionText }}</h2>
          <div class="job-actions">
            <button class="btn-neon btn-ghost" type="button" @click="edit(asset)">Edit</button>
            <button class="danger-action" type="button" title="Delete answer asset" @click="remove(asset.id)">Delete</button>
          </div>
        </div>
        <h3>Original answer</h3>
        <p>{{ asset.originalAnswerText }}</p>
        <template v-if="asset.suggestedAnswerText">
          <h3>AI suggestion</h3>
          <p>{{ asset.suggestedAnswerText }}</p>
        </template>
      </article>
    </div>
  </section>
</template>
