<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { BriefcaseBusiness, CheckCircle2, Clock3, Pencil, Plus, Save, ScanSearch, Sparkles, Trash2 } from 'lucide-vue-next'
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
  <section class="workspace-page jobs-page">
    <header class="jobs-heading"><div><p class="eyebrow"><BriefcaseBusiness :size="14" />{{ t('jobs.eyebrow') }}</p><h1>{{ t('jobs.title') }}</h1><p class="page-lead">{{ t('jobs.subtitle') }}</p></div><span class="job-count"><strong>{{ store.items.length }}</strong>{{ t('jobs.savedCount') }}</span></header>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <form class="job-composer" @submit.prevent="save">
      <header class="job-section-heading"><span><Plus v-if="editingId === null" :size="19" /><Pencil v-else :size="19" /></span><div><p>{{ editingId === null ? t('jobs.createEyebrow') : t('jobs.editEyebrow') }}</p><h2>{{ editingId === null ? t('jobs.create') : t('jobs.edit') }}</h2><small>{{ t('jobs.formDescription') }}</small></div></header>
      <label>{{ t('jobs.roleTitle') }}<input v-model.trim="title" required maxlength="255" /></label>
      <label>{{ t('jobs.company') }}<input v-model.trim="companyName" maxlength="255" /></label>
      <label class="wide-field">{{ t('jobs.description') }}<textarea v-model.trim="jdText" required maxlength="5000" rows="6" /><small class="field-count">{{ jdText.length }}/5000</small></label>
      <div class="job-actions">
        <button class="btn-neon btn-primary" :disabled="saving"><Save :size="16" />{{ saving ? t('jobs.saving') : editingId === null ? t('jobs.save') : t('jobs.saveChanges') }}</button>
        <button v-if="editingId !== null" class="btn-neon btn-ghost" type="button" :disabled="saving" @click="resetForm">{{ t('jobs.cancel') }}</button>
      </div>
    </form>

    <div v-if="parsedResult" class="parse-result-panel"><header><ScanSearch :size="18" /><h2>{{ parsedResult.title }} {{ t('jobs.parseResult') }}</h2></header><pre class="json-preview">{{ JSON.stringify(parsedResult.parsedKeywordsJson, null, 2) }}</pre></div>

    <section class="jobs-library" aria-labelledby="jobs-library-title">
      <header><div><p class="section-kicker">{{ t('jobs.libraryEyebrow') }}</p><h2 id="jobs-library-title">{{ t('jobs.libraryTitle') }}</h2></div></header>
      <p v-if="store.loading" class="jobs-loading">{{ t('jobs.loading') }}</p>
      <div v-else-if="!store.items.length" class="jobs-empty"><BriefcaseBusiness :size="23" /><p>{{ t('jobs.empty') }}</p></div>
      <div v-else class="job-list">
        <article v-for="job in store.items" :key="job.id" class="job-card">
          <div class="job-copy"><div class="job-status" :class="{ parsed: job.parsedAt }"><CheckCircle2 v-if="job.parsedAt" :size="13" /><Clock3 v-else :size="13" />{{ job.parsedAt ? t('jobs.parsed') : t('jobs.notParsed') }}</div><h3>{{ job.title }}</h3><p>{{ job.companyName || t('jobs.noCompany') }}</p></div>
          <div class="job-actions"><button class="icon-job-action" type="button" :title="t('jobs.editAction')" :aria-label="t('jobs.editAction')" @click="edit(job)"><Pencil :size="15" /></button><button class="btn-neon btn-ghost" type="button" @click="parse(job.id)"><ScanSearch :size="15" />{{ t('jobs.parse') }}</button><button class="btn-neon btn-primary" type="button" @click="generate(job.id)"><Sparkles :size="15" />{{ t('jobs.generate') }}</button><button class="icon-job-action danger" type="button" :title="t('jobs.delete')" :aria-label="t('jobs.delete')" @click="remove(job.id)"><Trash2 :size="15" /></button></div>
        </article>
      </div>
    </section>
  </section>
</template>

<style scoped>
.jobs-page { width: min(100%, 1040px); max-width: 1040px; gap: 26px; }
.jobs-heading { display: flex; align-items: end; justify-content: space-between; gap: 24px; padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.jobs-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.jobs-heading .page-lead { max-width: 700px; font-size: 12px; }
.job-count { display: grid; justify-items: end; gap: 1px; color: var(--text-tertiary); font-size: 9px; }
.job-count strong { color: var(--accent); font-family: var(--font-utility); font-size: 22px; }
.job-composer { display: grid; grid-template-columns: 1fr 1fr; gap: 14px 16px; padding: 24px; border: 1px solid var(--border); border-left: 4px solid var(--highlight); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.job-section-heading { grid-column: 1 / -1; display: grid; grid-template-columns: 40px minmax(0, 1fr); gap: 12px; padding-bottom: 18px; border-bottom: 1px solid var(--border-soft); }
.job-section-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--accent); background: var(--accent-light); }
.job-section-heading p, .job-section-heading h2, .job-section-heading small { display: block; margin: 0; }
.job-section-heading p { margin-bottom: 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.job-section-heading h2 { color: var(--text-primary); font-size: 16px; }
.job-section-heading small { margin-top: 5px; color: var(--text-secondary); font-size: 10px; }
.job-composer > label { position: relative; display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.job-composer input, .job-composer textarea { width: 100%; padding: 10px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font: inherit; font-size: 12px; resize: vertical; }
.job-composer input:focus, .job-composer textarea:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.job-composer .wide-field { grid-column: 1 / -1; }
.job-composer .field-count { position: absolute; right: 8px; bottom: 8px; padding: 2px 4px; color: var(--text-tertiary); background: var(--bg-input); font-family: var(--font-utility); font-size: 8px; }
.job-composer > .job-actions { grid-column: 1 / -1; justify-content: flex-end; }
.parse-result-panel { padding: 20px; border: 1px solid color-mix(in srgb, var(--info) 25%, var(--border)); border-radius: 7px; background: var(--info-light); }
.parse-result-panel header { display: flex; align-items: center; gap: 8px; color: var(--info); }
.parse-result-panel h2 { margin: 0; color: var(--text-primary); font-size: 13px; }
.jobs-library { display: grid; gap: 0; }
.jobs-library > header { padding-bottom: 12px; border-bottom: 1px solid var(--border); }
.section-kicker { margin: 0 0 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.jobs-library h2 { margin: 0; color: var(--text-primary); font-size: 16px; }
.jobs-loading, .jobs-empty { margin: 0; padding: 26px 4px; color: var(--text-secondary); font-size: 11px; }
.jobs-empty { display: flex; align-items: center; gap: 10px; color: var(--accent); }
.jobs-empty p { margin: 0; color: var(--text-secondary); }
.job-list { gap: 0; }
.job-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 18px; min-height: 92px; padding: 14px 4px; border-bottom: 1px solid var(--border); }
.job-copy { min-width: 0; }
.job-copy h3, .job-copy p { margin: 0; }
.job-copy h3 { margin-top: 6px; color: var(--text-primary); font-size: 13px; }
.job-copy p { margin-top: 3px; color: var(--text-secondary); font-size: 10px; }
.job-status { display: inline-flex; align-items: center; gap: 4px; color: var(--warning); font-size: 9px; font-weight: 700; }
.job-status.parsed { color: var(--success); }
.job-card .btn-neon { min-height: 32px; padding: 0 9px; font-size: 9px; }
.icon-job-action { display: grid; width: 32px; height: 32px; place-items: center; padding: 0; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); cursor: pointer; }
.icon-job-action:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.icon-job-action.danger:hover { border-color: var(--danger); color: var(--danger); background: var(--danger-light); }
@media (max-width: 720px) { .jobs-heading { align-items: stretch; flex-direction: column; } .job-count { justify-items: start; } .job-composer { grid-template-columns: 1fr; padding: 20px 16px; } .job-section-heading, .job-composer .wide-field, .job-composer > .job-actions { grid-column: auto; } .job-card { align-items: start; grid-template-columns: 1fr; } .job-card .job-actions { justify-content: flex-start; } }
@media (max-width: 480px) { .jobs-heading h1 { font-size: 29px; } .job-card .job-actions { display: grid; grid-template-columns: 32px minmax(0, 1fr) minmax(0, 1fr) 32px; width: 100%; } .job-card .btn-neon { width: 100%; min-width: 0; justify-content: center; padding: 0 5px; } }
</style>
