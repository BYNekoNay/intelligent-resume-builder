<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { generateCommunication, type CommunicationType } from '@/api/communication'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'
import { useLocale } from '@/i18n'

const router = useRouter()
const resumeVersionId = ref('')
const jobId = ref('')
const type = ref<CommunicationType>('COVER_LETTER')
const draft = ref('')
const draftContext = ref<{ resumeVersionId: number; jobDescriptionId: number; type: CommunicationType } | null>(null)
const error = ref('')
const copyStatus = ref('')
const loading = ref(false)
const { t } = useLocale()
const {
  resumes, jobs, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load, loadVersions,
} = useResumeJobOptions()

async function generate() {
  if (!resumeVersionId.value || !jobId.value) {
    error.value = t('communication.selectError')
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
    error.value = t('communication.generateError')
  } finally {
    loading.value = false
  }
}

async function copyDraft() {
  try {
    await navigator.clipboard.writeText(draft.value)
    copyStatus.value = t('communication.copied')
  } catch {
    copyStatus.value = t('communication.clipboardError')
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
    <p class="eyebrow">{{ t('communication.eyebrow') }}</p><h1>{{ t('communication.title') }}</h1><p>{{ t('communication.subtitle') }}</p>

    <form class="workspace-card compact-form" @submit.prevent="generate">
      <label>{{ t('communication.resume') }}
        <select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions">
          <option :value="null" disabled>{{ t('communication.selectResume') }}</option>
          <option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option>
        </select>
      </label>
      <label>{{ t('communication.version') }}
        <select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required>
          <option value="" disabled>{{ t('communication.selectVersion') }}</option>
          <option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option>
        </select>
      </label>
      <label>{{ t('communication.job') }}
        <select v-model="jobId" :disabled="optionsLoading" required>
          <option value="" disabled>{{ t('communication.selectJob') }}</option>
          <option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option>
        </select>
      </label>
      <label>{{ t('communication.type') }}
        <select v-model="type"><option value="COVER_LETTER">{{ t('communication.cover') }}</option><option value="EMAIL">{{ t('communication.email') }}</option><option value="OPENING_MESSAGE">{{ t('communication.opening') }}</option></select>
      </label>
      <button class="btn-neon btn-primary" :disabled="loading || optionsLoading">{{ loading ? t('communication.generating') : t('communication.generate') }}</button>
    </form>

    <p v-if="optionsError || error" class="form-error" role="alert">{{ error || optionsError }}</p>
    <article v-if="draft" class="workspace-card">
      <p class="disclaimer">{{ t('communication.verify') }}</p><label>{{ t('communication.draft') }}<textarea v-model="draft" rows="14" /></label><div class="job-actions"><button class="btn-neon btn-secondary" type="button" @click="copyDraft">{{ t('communication.copy') }}</button><button class="btn-neon btn-primary" type="button" @click="useInApplication">{{ t('communication.use') }}</button></div>
      <p v-if="copyStatus" class="disclaimer">{{ copyStatus }}</p>
    </article>
  </section>
</template>
