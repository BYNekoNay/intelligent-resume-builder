<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { createJob, deleteJob, parseJob, type JobDescription, updateJob } from '@/api/jobDescription'
import { useJobDescriptionStore } from '@/stores/jobDescription'
import { useLocale } from '@/i18n'

const store = useJobDescriptionStore()
const router = useRouter()
const title = ref('')
const companyName = ref('')
const jdText = ref('')
const editingId = ref<number | null>(null)
const saving = ref(false)
const parsedResult = ref<JobDescription | null>(null)
const error = ref('')
const { t } = useLocale()

onMounted(async () => {
  await store.load()
})

function resetForm() {
  editingId.value = null
  title.value = ''
  companyName.value = ''
  jdText.value = ''
}

function edit(job: JobDescription) {
  editingId.value = job.id
  title.value = job.title
  companyName.value = job.companyName ?? ''
  jdText.value = job.jdText
  error.value = ''
}

async function save() {
  saving.value = true
  error.value = ''
  const payload = { title: title.value, companyName: companyName.value || undefined, jdText: jdText.value }
  try {
    if (editingId.value === null) await createJob(payload)
    else await updateJob(editingId.value, payload)
    resetForm()
    await store.load()
  } catch {
    error.value = t('jobs.saveError')
  } finally {
    saving.value = false
  }
}

async function parse(id: number) {
  error.value = ''
  try {
    parsedResult.value = (await parseJob(id)).data.data
    await store.load()
  } catch {
    error.value = t('jobs.parseError')
  }
}

async function remove(id: number) {
  if (!window.confirm(t('jobs.confirmDelete'))) return
  error.value = ''
  try {
    await deleteJob(id)
    if (editingId.value === id) resetForm()
    await store.load()
  } catch {
    error.value = t('jobs.deleteError')
  }
}

function generate(jobId: number) {
  void router.push({ name: 'generate', query: { jdId: String(jobId) } })
}
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">{{ t('jobs.eyebrow') }}</p>
    <h1>{{ t('jobs.title') }}</h1>
    <p>{{ t('jobs.subtitle') }}</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <form class="workspace-card job-form" @submit.prevent="save">
      <h2>{{ editingId === null ? t('jobs.create') : t('jobs.edit') }}</h2>
      <label>{{ t('jobs.roleTitle') }}<input v-model.trim="title" required maxlength="255" /></label>
      <label>{{ t('jobs.company') }}<input v-model.trim="companyName" maxlength="255" /></label>
      <label class="wide-field">{{ t('jobs.description') }}<textarea v-model.trim="jdText" required maxlength="5000" rows="6" /><small class="field-count">{{ jdText.length }}/5000</small></label>
      <div class="job-actions">
        <button class="btn-neon btn-primary" :disabled="saving">{{ saving ? t('jobs.saving') : editingId === null ? t('jobs.save') : t('jobs.saveChanges') }}</button>
        <button v-if="editingId !== null" class="btn-neon btn-ghost" type="button" :disabled="saving" @click="resetForm">{{ t('jobs.cancel') }}</button>
      </div>
    </form>

    <div v-if="parsedResult" class="workspace-card"><h2>{{ parsedResult.title }} {{ t('jobs.parseResult') }}</h2><pre class="json-preview">{{ JSON.stringify(parsedResult.parsedKeywordsJson, null, 2) }}</pre></div>

    <p v-if="store.loading">{{ t('jobs.loading') }}</p>
    <p v-else-if="!store.items.length" class="empty-state">{{ t('jobs.empty') }}</p>
    <div v-else class="job-list">
      <article v-for="job in store.items" :key="job.id" class="workspace-card job-card">
        <div><h2>{{ job.title }}</h2><p>{{ job.companyName || t('jobs.noCompany') }} · {{ job.parsedAt ? t('jobs.parsed') : t('jobs.notParsed') }}</p></div>
        <div class="job-actions"><button class="btn-neon btn-ghost" type="button" @click="edit(job)">{{ t('jobs.editAction') }}</button><button class="btn-neon btn-ghost" type="button" @click="parse(job.id)">{{ t('jobs.parse') }}</button><button class="btn-neon btn-primary" type="button" @click="generate(job.id)">{{ t('jobs.generate') }}</button><button class="danger-action" type="button" :title="t('jobs.delete')" @click="remove(job.id)">{{ t('jobs.delete') }}</button></div>
      </article>
    </div>
  </section>
</template>
