<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { AlertTriangle, CheckCircle2, ListChecks, SearchCheck } from 'lucide-vue-next'
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
  <section class="workspace-page ats-page">
    <header class="ats-heading"><p class="eyebrow"><SearchCheck :size="14" />{{ t('ats.eyebrow') }}</p><h1>{{ t('ats.title') }}</h1><p class="page-lead">{{ t('ats.titleDesc') }}</p><div class="ats-disclaimer"><AlertTriangle :size="15" /><span>{{ t('ats.disclaimer') }}</span></div></header>
    <form class="ats-config" @submit.prevent="check">
      <header class="ats-section-heading"><span><ListChecks :size="19" /></span><div><p>{{ t('ats.setupEyebrow') }}</p><h2>{{ t('ats.setupTitle') }}</h2><small>{{ t('ats.setupDescription') }}</small></div></header>
      <label>{{ t('ats.resume') }}<select v-model.number="selectedResumeId" :disabled="optionsLoading" @change="loadVersions"><option :value="null" disabled>{{ t('ats.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
      <label>{{ t('ats.version') }}<select v-model="resumeVersionId" :disabled="optionsLoading || !hasVersions" required><option value="" disabled>{{ t('ats.selectVersion') }}</option><option v-for="version in versions" :key="version.id" :value="String(version.id)">v{{ version.versionNo }} · {{ version.sourceType }}</option></select></label>
      <label>{{ t('ats.job') }}<select v-model="jobDescriptionId" :disabled="optionsLoading" required><option value="" disabled>{{ t('ats.selectJob') }}</option><option v-for="job in jobs" :key="job.id" :value="String(job.id)">{{ job.title }}{{ job.companyName ? ` · ${job.companyName}` : '' }}</option></select></label>
      <button class="btn-neon btn-primary" :disabled="loading || optionsLoading"><SearchCheck :size="16" />{{ loading ? t('ats.checking') : t('ats.run') }}</button>
    </form>
    <p v-if="optionsError || error" class="form-error" role="alert">{{ error || optionsError }}</p>
    <article v-if="result" class="ats-result">
      <header><p class="section-kicker">{{ t('ats.resultEyebrow') }}</p><h2>{{ t('ats.resultTitle') }}</h2></header>
      <div class="score-grid"><p><strong>{{ t('ats.score') }}</strong><span>{{ result.totalScore }}</span></p><p><strong>{{ t('ats.structure') }}</strong><span>{{ result.checks.structure }}</span></p><p><strong>{{ t('ats.coverage') }}</strong><span>{{ result.checks.keywordCoverage }}</span></p></div>
      <div class="ats-findings"><section class="priority-findings"><h3><AlertTriangle :size="15" />{{ t('ats.priorities') }}</h3><ol><li v-for="priority in result.priorities" :key="priority">{{ priority }}</li><li v-if="!result.priorities.length">{{ t('ats.noPriorities') }}</li></ol></section><section class="passed-findings"><h3><CheckCircle2 :size="15" />{{ t('ats.passed') }}</h3><ul><li v-for="passed in result.passedChecks" :key="passed">{{ passed }}</li></ul></section><section class="risk-findings"><h3><AlertTriangle :size="15" />{{ t('ats.risks') }}</h3><ul><li v-for="risk in result.risks" :key="risk">{{ risk }}</li><li v-if="!result.risks.length">{{ t('ats.noRisks') }}</li></ul></section></div>
      <small class="result-disclaimer">{{ result.disclaimer }}</small>
    </article>
  </section>
</template>

<style scoped>
.ats-page { width: min(100%, 920px); max-width: 920px; gap: 24px; }
.ats-heading { display: grid; gap: 0; padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.ats-heading .eyebrow { justify-self: start; }
.ats-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.ats-heading .page-lead { max-width: 650px; font-size: 12px; }
.ats-disclaimer { display: inline-flex; align-items: center; gap: 7px; justify-self: start; margin-top: 15px; padding: 8px 10px; border: 1px solid color-mix(in srgb, var(--warning) 25%, var(--border)); border-radius: 5px; color: var(--warning); background: var(--warning-light); font-size: 9px; }
.ats-config { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; padding: 24px; border: 1px solid var(--border); border-left: 4px solid var(--info); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.ats-section-heading { grid-column: 1 / -1; display: grid; grid-template-columns: 40px minmax(0, 1fr); gap: 12px; padding-bottom: 18px; border-bottom: 1px solid var(--border-soft); }
.ats-section-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--info); background: var(--info-light); }
.ats-section-heading p, .ats-section-heading h2, .ats-section-heading small { display: block; margin: 0; }
.ats-section-heading p, .section-kicker { margin-bottom: 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.ats-section-heading h2 { color: var(--text-primary); font-size: 16px; }
.ats-section-heading small { margin-top: 5px; color: var(--text-secondary); font-size: 10px; }
.ats-config label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.ats-config select { width: 100%; min-height: 40px; padding: 8px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font-size: 11px; }
.ats-config select:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.ats-config .btn-neon { grid-column: 1 / -1; justify-self: end; }
.ats-result { display: grid; gap: 18px; padding: 24px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.ats-result > header h2 { margin: 0; color: var(--text-primary); font-size: 16px; }
.score-grid { grid-template-columns: repeat(3, 1fr); gap: 0; margin: 0; border-block: 1px solid var(--border); }
.score-grid p { display: grid; gap: 5px; margin: 0; padding: 14px; border: 0; border-right: 1px solid var(--border-soft); border-radius: 0; background: transparent; }
.score-grid p:last-child { border-right: 0; }
.score-grid strong { margin: 0; color: var(--text-tertiary); font-size: 9px; }
.score-grid span { color: var(--accent); font-family: var(--font-utility); font-size: 22px; font-weight: 700; }
.ats-findings { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.ats-findings section { padding: 15px 17px; border: 1px solid var(--border-soft); border-radius: 6px; background: var(--bg-page); }
.ats-findings .risk-findings { grid-column: 1 / -1; }
.ats-findings h3 { display: flex; align-items: center; gap: 6px; margin: 0 0 9px; color: var(--text-primary); font-size: 11px; }
.priority-findings h3, .risk-findings h3 { color: var(--warning); }
.passed-findings h3 { color: var(--success); }
.ats-findings ol, .ats-findings ul { display: grid; gap: 6px; margin: 0; padding-left: 18px; color: var(--text-secondary); font-size: 10px; line-height: 1.5; }
.result-disclaimer { color: var(--text-tertiary); font-size: 9px; line-height: 1.55; }
@media (max-width: 680px) { .ats-heading h1 { font-size: 29px; } .ats-config { grid-template-columns: 1fr; padding: 20px 16px; } .ats-section-heading, .ats-config .btn-neon { grid-column: auto; } .ats-config .btn-neon { width: 100%; justify-content: center; } .ats-result { padding: 20px 16px; } .ats-findings { grid-template-columns: 1fr; } .ats-findings .risk-findings { grid-column: auto; } }
</style>
