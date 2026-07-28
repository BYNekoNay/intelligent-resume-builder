<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Clipboard, FileText, Mail, Send, Sparkles } from 'lucide-vue-next'
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
const typeLabel = computed(() => ({ COVER_LETTER: t('communication.cover'), EMAIL: t('communication.email'), OPENING_MESSAGE: t('communication.opening') }[type.value]))
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
  <section class="workspace-page communication-page">
    <header class="communication-heading"><p class="eyebrow"><Mail :size="14" />{{ t('communication.eyebrow') }}</p><h1>{{ t('communication.title') }}</h1><p class="page-lead">{{ t('communication.subtitle') }}</p></header>

    <form class="workspace-card compact-form communication-setup" @submit.prevent="generate">
      <header class="communication-section-heading"><span><Sparkles :size="19" /></span><div><p>{{ t('communication.setupEyebrow') }}</p><h2>{{ t('communication.setupTitle') }}</h2><small>{{ t('communication.setupDescription') }}</small></div></header>
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
      <button class="btn-neon btn-primary" :disabled="loading || optionsLoading"><Sparkles :size="16" />{{ loading ? t('communication.generating') : t('communication.generate') }}</button>
    </form>

    <p v-if="optionsError || error" class="form-error" role="alert">{{ error || optionsError }}</p>
    <article v-if="draft" class="workspace-card communication-draft">
      <header><div><p class="section-kicker">{{ t('communication.draftEyebrow') }}</p><h2>{{ t('communication.draft') }}</h2></div><span><FileText :size="15" />{{ typeLabel }}</span></header>
      <p class="disclaimer">{{ t('communication.verify') }}</p><label>{{ t('communication.draft') }}<textarea v-model="draft" rows="14" /></label><div class="job-actions"><button class="btn-neon btn-secondary" type="button" @click="copyDraft"><Clipboard :size="16" />{{ t('communication.copy') }}</button><button class="btn-neon btn-primary" type="button" @click="useInApplication"><Send :size="16" />{{ t('communication.use') }}</button></div>
      <p v-if="copyStatus" class="disclaimer">{{ copyStatus }}</p>
    </article>
  </section>
</template>

<style scoped>
.communication-page { width: min(100%, 920px); max-width: 920px; gap: 24px; }.communication-heading { padding-bottom: 22px; border-bottom: 1px solid var(--border); }.communication-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }.communication-heading .page-lead { max-width: 650px; font-size: 12px; }.communication-setup { display: grid; grid-template-columns: 1fr 1fr; gap: 14px 16px; padding: 24px; border-left: 4px solid var(--info); }.communication-section-heading { grid-column: 1 / -1; display: grid; grid-template-columns: 40px 1fr; gap: 12px; padding-bottom: 18px; border-bottom: 1px solid var(--border-soft); }.communication-section-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--info); background: var(--info-light); }.communication-section-heading p, .communication-section-heading h2, .communication-section-heading small { display: block; margin: 0; }.communication-section-heading p, .section-kicker { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }.communication-section-heading h2 { margin-top: 3px; font-size: 16px; }.communication-section-heading small { margin-top: 5px; color: var(--text-secondary); font-size: 10px; }.communication-setup .btn-neon { grid-column: 1 / -1; justify-self: end; }.communication-draft { display: grid; gap: 14px; padding: 24px; border-left: 4px solid var(--accent); }.communication-draft > header { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding-bottom: 14px; border-bottom: 1px solid var(--border-soft); }.communication-draft h2 { margin: 3px 0 0; font-size: 16px; }.communication-draft header > span { display: inline-flex; align-items: center; gap: 5px; color: var(--text-secondary); font-size: 10px; }.communication-draft label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }.communication-draft textarea { width: 100%; padding: 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-input); color: var(--text-primary); font: inherit; font-size: 12px; line-height: 1.6; resize: vertical; }.communication-draft .job-actions { justify-content: flex-end; }
@media (max-width: 680px) { .communication-heading h1 { font-size: 29px; }.communication-setup { grid-template-columns: 1fr; padding: 20px 16px; }.communication-section-heading, .communication-setup .btn-neon { grid-column: auto; }.communication-setup .btn-neon { width: 100%; justify-content: center; }.communication-draft { padding: 20px 16px; }.communication-draft .job-actions .btn-neon { flex: 1; justify-content: center; } }
</style>
