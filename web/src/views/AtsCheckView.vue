<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { runAtsCheck, type AtsCheckResponse } from '@/api/ats'
import { useResumeJobOptions } from '@/composables/useResumeJobOptions'

const resumeVersionId = ref('')
const jobDescriptionId = ref('')
const result = ref<AtsCheckResponse | null>(null)
const error = ref('')
const loading = ref(false)
const { resumes, jobs, versions, selectedResumeId, loading: optionsLoading, error: optionsError, hasVersions, load, loadVersions } = useResumeJobOptions()

async function check() {
  if (!resumeVersionId.value || !jobDescriptionId.value) {
    error.value = 'Select a resume version and target job first.'
    return
  }
  error.value = ''
  result.value = null
  loading.value = true
  try {
    result.value = (await runAtsCheck(Number(resumeVersionId.value), Number(jobDescriptionId.value))).data.data
  } catch {
    error.value = 'Unable to run the resume health check. Confirm that the selected resources belong to you.'
  } finally {
    loading.value = false
  }
}

onMounted(() => { void load() })
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">Resume health</p>
    <h1>ATS rule check</h1>
    <p class="disclaimer">This is a rules-based resume health check, not an enterprise ATS result or hiring prediction.</p>
    <form class="workspace-card compact-form" @submit.prevent="check">
      <label>Resume<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>Select a resume</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <label>Resume version<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required><option value="" disabled>Select a version</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label>
      <label>Target job<select v-model="jobDescriptionId" :disabled="optionsLoading" required><option value="" disabled>Select a job</option><option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <button class="btn-neon btn-primary" :disabled="loading || optionsLoading">{{ loading ? 'Checking...' : 'Run check' }}</button>
    </form>
    <p v-if="optionsError || error" class="form-error" role="alert">{{ error || optionsError }}</p>
    <article v-if="result" class="workspace-card">
      <div class="score-grid"><p><strong>Health score</strong>{{ result.totalScore }}</p><p><strong>Structure</strong>{{ result.checks.structure }}</p><p><strong>Keyword coverage</strong>{{ result.checks.keywordCoverage }}</p></div>
      <h2>Priority changes</h2><ol><li v-for="priority in result.priorities" :key="priority">{{ priority }}</li><li v-if="!result.priorities.length">No urgent structural changes found.</li></ol>
      <h2>Passed checks</h2><ul><li v-for="passed in result.passedChecks" :key="passed">{{ passed }}</li></ul>
      <h2>Risks and evidence</h2><ul><li v-for="risk in result.risks" :key="risk">{{ risk }}</li><li v-if="!result.risks.length">No structural risks found.</li></ul>
      <small class="disclaimer">{{ result.disclaimer }}</small>
    </article>
  </section>
</template>
