<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { generateForJob, getConsent } from '@/api/ai'
import { createJob, deleteJob, parseJob, type JobDescription, updateJob } from '@/api/jobDescription'
import { useAiTaskStore } from '@/stores/aiTask'
import { useCareerMaterialStore } from '@/stores/careerMaterial'
import { useJobDescriptionStore } from '@/stores/jobDescription'
import { useResumeStore } from '@/stores/resume'

const store = useJobDescriptionStore()
const resumeStore = useResumeStore()
const materialStore = useCareerMaterialStore()
const taskStore = useAiTaskStore()
const router = useRouter()
const route = useRoute()
const title = ref('')
const companyName = ref('')
const jdText = ref('')
const editingId = ref<number | null>(null)
const targetResumeId = ref<number | null>(null)
const preferences = ref<Record<number, 'included' | 'preferred' | 'excluded'>>({})
const saving = ref(false)
const creatingFor = ref<number | null>(null)
const parsedResult = ref<JobDescription | null>(null)
const error = ref('')

onMounted(async () => {
  await Promise.all([store.load(), resumeStore.load(), materialStore.load()])
  targetResumeId.value = resumeStore.items[0]?.id ?? null
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

function preferenceIds(preference: 'included' | 'preferred' | 'excluded') {
  return Object.entries(preferences.value)
    .filter(([, value]) => value === preference)
    .map(([id]) => Number(id))
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
    error.value = 'Unable to save the job description. Check the required fields and try again.'
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
    error.value = 'Unable to parse this job description. Please try again.'
  }
}

async function remove(id: number) {
  if (!window.confirm('Delete this job description?')) return
  error.value = ''
  try {
    await deleteJob(id)
    if (editingId.value === id) resetForm()
    await store.load()
  } catch {
    error.value = 'Unable to delete this job description. It may already be in use.'
  }
}

async function generate(jobId: number) {
  if (targetResumeId.value === null) {
    error.value = 'Create and select a target resume before generating a tailored draft.'
    return
  }
  creatingFor.value = jobId
  error.value = ''
  try {
    const consent = (await getConsent()).data.data
    if (consent?.eventType !== 'GRANTED') {
      await router.push({ name: 'ai-consent', query: { redirect: route.fullPath } })
      return
    }
    const response = await generateForJob({
      targetResumeId: targetResumeId.value,
      jobDescriptionId: jobId,
      includedMaterialIds: preferenceIds('included'),
      preferredMaterialIds: preferenceIds('preferred'),
      excludedMaterialIds: preferenceIds('excluded'),
    }, `job-generation-${jobId}-${Date.now()}`)
    taskStore.remember(response.data.data.id)
    await router.push({ name: 'job-generation-confirm', params: { jobId }, query: { taskId: response.data.data.id } })
  } catch {
    error.value = 'Unable to start generation. Confirm AI consent and review the selected resume and materials.'
  } finally {
    creatingFor.value = null
  }
}
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">Job descriptions</p>
    <h1>Target Jobs</h1>
    <p>Save a job description, select a resume and material preferences, then generate a reviewable tailored draft.</p>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <form class="workspace-card job-form" @submit.prevent="save">
      <h2>{{ editingId === null ? 'New job description' : 'Edit job description' }}</h2>
      <label>Role title<input v-model.trim="title" required maxlength="255" /></label>
      <label>Company<input v-model.trim="companyName" maxlength="255" /></label>
      <label class="wide-field">Job description<textarea v-model.trim="jdText" required maxlength="5000" rows="6" /><small class="field-count">{{ jdText.length }}/5000</small></label>
      <div class="job-actions">
        <button class="btn-neon btn-primary" :disabled="saving">{{ saving ? 'Saving...' : editingId === null ? 'Save job' : 'Save changes' }}</button>
        <button v-if="editingId !== null" class="btn-neon btn-ghost" type="button" :disabled="saving" @click="resetForm">Cancel</button>
      </div>
    </form>

    <div v-if="parsedResult" class="workspace-card"><h2>{{ parsedResult.title }} parsing result</h2><pre class="json-preview">{{ JSON.stringify(parsedResult.parsedKeywordsJson, null, 2) }}</pre></div>

    <section class="generation-config workspace-card">
      <h2>Tailored draft setup</h2>
      <label>Target resume<select v-model="targetResumeId"><option :value="null">Select a resume</option><option v-for="resume in resumeStore.items" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <RouterLink class="text-link" to="/ai-consent">Manage AI consent</RouterLink>
      <div v-if="materialStore.items.length" class="material-preferences">
        <h3>Material preferences</h3>
        <label v-for="material in materialStore.items" :key="material.id" class="preference-row"><span><strong>{{ material.title }}</strong><small>{{ material.materialType }}</small></span><select v-model="preferences[material.id]"><option :value="undefined">Use material default</option><option value="included">Always include</option><option value="preferred">Prefer</option><option value="excluded">Exclude</option></select></label>
      </div>
    </section>

    <p v-if="store.loading">Loading job descriptions...</p>
    <p v-else-if="!store.items.length" class="empty-state">No job descriptions yet.</p>
    <div v-else class="job-list">
      <article v-for="job in store.items" :key="job.id" class="workspace-card job-card">
        <div><h2>{{ job.title }}</h2><p>{{ job.companyName || 'No company specified' }} · {{ job.parsedAt ? 'Parsed' : 'Not parsed' }}</p></div>
        <div class="job-actions"><button class="btn-neon btn-ghost" type="button" @click="edit(job)">Edit</button><button class="btn-neon btn-ghost" type="button" @click="parse(job.id)">Parse</button><button class="btn-neon btn-primary" type="button" :disabled="creatingFor !== null" @click="generate(job.id)">{{ creatingFor === job.id ? 'Starting...' : 'Generate draft' }}</button><button class="danger-action" type="button" title="Delete job description" @click="remove(job.id)">Delete</button></div>
      </article>
    </div>
  </section>
</template>
