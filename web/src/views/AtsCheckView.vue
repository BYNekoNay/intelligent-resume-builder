<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { runAtsCheck, type AtsCheckResponse } from '@/api/ats'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'
import { useLocale } from '@/i18n'

const resumeVersionId = ref('')
const jobDescriptionId = ref('')
const result = ref<AtsCheckResponse | null>(null)
const error = ref('')
const loading = ref(false)
const { t } = useLocale()
const { resumes, jobs, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load, loadVersions } = useResumeJobOptions()

async function check() {
  if (!resumeVersionId.value || !jobDescriptionId.value) {
    error.value = t('ats.selectError')
    return
  }
  error.value = ''
  result.value = null
  loading.value = true
  try {
    result.value = (await runAtsCheck(Number(resumeVersionId.value), Number(jobDescriptionId.value))).data.data
  } catch {
    error.value = t('ats.runError')
  } finally {
    loading.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">{{ t('ats.eyebrow') }}</p>
    <h1>{{ t('ats.title') }}</h1>
    <p class="disclaimer">{{ t('ats.disclaimer') }}</p>
    <form class="workspace-card compact-form" @submit.prevent="check">
      <label>{{ t('ats.resume') }}<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>{{ t('ats.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <label>{{ t('ats.version') }}<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required><option value="" disabled>{{ t('ats.selectVersion') }}</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label>
      <label>{{ t('ats.job') }}<select v-model="jobDescriptionId" :disabled="optionsLoading" required><option value="" disabled>{{ t('ats.selectJob') }}</option><option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <button class="btn-neon btn-primary" :disabled="loading || optionsLoading">{{ loading ? t('ats.checking') : t('ats.run') }}</button>
    </form>
    <p v-if="optionsError || error" class="form-error" role="alert">{{ error || optionsError }}</p>
    <article v-if="result" class="workspace-card">
      <div class="score-grid"><p><strong>{{ t('ats.score') }}</strong>{{ result.totalScore }}</p><p><strong>{{ t('ats.structure') }}</strong>{{ result.checks.structure }}</p><p><strong>{{ t('ats.coverage') }}</strong>{{ result.checks.keywordCoverage }}</p></div>
      <h2>{{ t('ats.priorities') }}</h2><ol><li v-for="priority in result.priorities" :key="priority">{{ priority }}</li><li v-if="!result.priorities.length">{{ t('ats.noPriorities') }}</li></ol>
      <h2>{{ t('ats.passed') }}</h2><ul><li v-for="passed in result.passedChecks" :key="passed">{{ passed }}</li></ul>
      <h2>{{ t('ats.risks') }}</h2><ul><li v-for="risk in result.risks" :key="risk">{{ risk }}</li><li v-if="!result.risks.length">{{ t('ats.noRisks') }}</li></ul>
      <small class="disclaimer">{{ result.disclaimer }}</small>
    </article>
  </section>
</template>
