<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { generateCommunication, type CommunicationType } from '@/api/communication'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'

const router = useRouter()
const resumeVersionId = ref('')
const jobId = ref('')
const type = ref<CommunicationType>('COVER_LETTER')
const draft = ref('')
const draftContext = ref<{ resumeVersionId: number; jobDescriptionId: number; type: CommunicationType } | null>(null)
const error = ref('')
const copyStatus = ref('')
const loading = ref(false)
const {
  resumes, jobs, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load, loadVersions,
} = useResumeJobOptions()

async function generate() {
  if (!resumeVersionId.value || !jobId.value) {
    error.value = 'Select a resume version and target job first.'
    return
  }
  loading.value = true
  error.value = ''
  copyStatus.value = ''
  draft.value = ''
  draftContext.value = null
  try {
    draft.value = (await generateCommunication(Number(resumeVersionId.value), Number(jobId.value), type.value)).data.data.draft
    draftContext.value = {
      resumeVersionId: Number(resumeVersionId.value),
      jobDescriptionId: Number(jobId.value),
      type: type.value,
    }
  } catch {
    error.value = 'Unable to generate a draft. Check AI consent and the selected resources.'
  } finally {
    loading.value = false
  }
}

async function copyDraft() {
  try {
    await navigator.clipboard.writeText(draft.value)
    copyStatus.value = 'Draft copied.'
  } catch {
    copyStatus.value = 'Clipboard access is unavailable. Select the text and copy it manually.'
  }
}

async function useInApplication() {
  if (draftContext.value === null) return
  sessionStorage.setItem('application-draft', JSON.stringify({
    ...draftContext.value,
    text: draft.value,
  }))
  await router.push({ name: 'applications' })
}

onMounted(() => { void load() })
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">Communication drafts</p>
    <h1>Job communication</h1>
    <p>Create editable application messages. The system never sends them to an external platform.</p>

    <form class="workspace-card compact-form" @submit.prevent="generate">
      <label>Resume
        <select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions">
          <option :value="null" disabled>Select a resume</option>
          <option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option>
        </select>
      </label>
      <label>Resume version
        <select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required>
          <option value="" disabled>Select a version</option>
          <option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option>
        </select>
      </label>
      <label>Job description
        <select v-model="jobId" :disabled="optionsLoading" required>
          <option value="" disabled>Select a job</option>
          <option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option>
        </select>
      </label>
      <label>Draft type
        <select v-model="type"><option value="COVER_LETTER">Cover letter</option><option value="EMAIL">Email body</option><option value="OPENING_MESSAGE">Opening message</option></select>
      </label>
      <button class="btn-neon btn-primary" :disabled="loading || optionsLoading">{{ loading ? 'Generating...' : 'Generate draft' }}</button>
    </form>

    <p v-if="optionsError || error" class="form-error" role="alert">{{ error || optionsError }}</p>
    <article v-if="draft" class="workspace-card">
      <p class="disclaimer">Verify the facts before copying or associating this draft with an application.</p>
      <label>Editable draft<textarea v-model="draft" rows="14" /></label>
      <div class="job-actions"><button class="btn-neon btn-secondary" type="button" @click="copyDraft">Copy</button><button class="btn-neon btn-primary" type="button" @click="useInApplication">Use in application</button></div>
      <p v-if="copyStatus" class="disclaimer">{{ copyStatus }}</p>
    </article>
  </section>
</template>
