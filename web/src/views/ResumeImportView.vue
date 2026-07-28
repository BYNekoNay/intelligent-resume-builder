<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Check, FileCheck2, FileText, ScanText, ShieldCheck, Upload } from 'lucide-vue-next'
import { parseResumeFile, type ResumeImportResponse } from '@/api/resumeImport'
import { useLocale } from '@/i18n'

const selected = ref<File | null>(null)
const result = ref<ResumeImportResponse | null>(null)
const extractedText = ref('')
const loading = ref(false)
const error = ref('')
const router = useRouter()
const { t } = useLocale()

const activeStep = computed(() => result.value ? 3 : selected.value ? 2 : 1)

function formatFileSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  return `${(bytes / 1024 / 1024).toFixed(2)} MB`
}

function choose(event: Event) {
  selected.value = (event.target as HTMLInputElement).files?.[0] ?? null
  result.value = null
  extractedText.value = ''
  error.value = ''
}

async function parse() {
  if (!selected.value) {
    error.value = t('import.fileError')
    return
  }
  loading.value = true
  error.value = ''
  try {
    result.value = (await parseResumeFile(selected.value)).data.data
    extractedText.value = result.value.extractedText
  } catch {
    error.value = t('import.parseError')
  } finally {
    loading.value = false
  }
}

async function continueToGeneration() {
  if (!result.value || !extractedText.value.trim()) {
    error.value = t('import.reviewError')
    return
  }
  sessionStorage.setItem('resume-import-text', extractedText.value.trim())
  await router.push({ name: 'material-generation' })
}
</script>

<template>
  <section class="workspace-page resume-import-page">
    <header class="import-heading">
      <p class="eyebrow"><Upload :size="14" /> {{ t('import.eyebrow') }}</p>
      <h1>{{ t('import.title') }}</h1>
      <p class="page-lead">{{ t('import.subtitle') }}</p>
    </header>

    <ol class="import-steps" :aria-label="t('import.progressLabel')">
      <li v-for="step in 4" :key="step" :class="{ active: activeStep === step, complete: activeStep > step }">
        <span><Check v-if="activeStep > step" :size="13" /><template v-else>{{ step }}</template></span>
        {{ t(`import.step${step}`) }}
      </li>
    </ol>

    <section class="import-surface" aria-labelledby="file-selection-title">
      <div class="import-section-heading">
        <span><FileText :size="19" /></span>
        <div><p class="section-kicker">{{ t('import.sourceEyebrow') }}</p><h2 id="file-selection-title">{{ t('import.sourceTitle') }}</h2></div>
      </div>
      <form class="file-selection-form" @submit.prevent="parse">
        <label class="file-drop-zone" :class="{ selected }">
          <input type="file" accept=".pdf,.docx,.txt" @change="choose" />
          <FileCheck2 v-if="selected" :size="28" />
          <Upload v-else :size="28" />
          <strong>{{ selected ? selected.name : t('import.chooseFile') }}</strong>
          <small>{{ selected ? formatFileSize(selected.size) : t('import.fileHint') }}</small>
          <span>{{ selected ? t('import.changeFile') : t('import.browseFile') }}</span>
        </label>
        <div class="import-privacy-note"><ShieldCheck :size="17" /><p><strong>{{ t('import.privacyTitle') }}</strong><small>{{ t('import.privacyDescription') }}</small></p></div>
        <button class="btn-neon btn-primary" :disabled="loading || !selected">
          <ScanText :size="16" /> {{ loading ? t('import.parsing') : t('import.parse') }}
        </button>
      </form>
    </section>

    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <section v-if="result" class="import-review-surface" aria-labelledby="import-review-title">
      <header class="review-heading">
        <div class="import-section-heading">
          <span><ScanText :size="19" /></span>
          <div><p class="section-kicker">{{ t('import.reviewEyebrow') }}</p><h2 id="import-review-title">{{ t('import.reviewTitle') }}</h2></div>
        </div>
        <span class="storage-status"><Check :size="13" /> {{ t('import.stored') }}: {{ result.originalFileStored ? t('import.yes') : t('import.no') }}</span>
      </header>
      <p class="review-guidance">{{ t('import.review') }}</p>
      <label class="extracted-editor">{{ t('import.extracted') }}<textarea v-model="extractedText" rows="16" maxlength="30000" /></label>
      <footer class="review-actions">
        <details><summary>{{ t('import.preview') }}</summary><pre>{{ JSON.stringify(result.normalizedResumeInput, null, 2) }}</pre></details>
        <button class="btn-neon btn-secondary" type="button" @click="continueToGeneration">
          {{ t('import.use') }} <ArrowRight :size="16" />
        </button>
      </footer>
    </section>
  </section>
</template>

<style scoped>
.resume-import-page { width: min(100%, 960px); max-width: 960px; gap: 24px; }
.import-heading { max-width: 700px; }
.import-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 36px; letter-spacing: 0; }
.import-steps { display: grid; grid-template-columns: repeat(4, 1fr); margin: 4px 0 0; padding: 0; list-style: none; border-block: 1px solid var(--border); }
.import-steps li { display: flex; align-items: center; gap: 8px; min-width: 0; padding: 13px 8px; color: var(--text-tertiary); font-size: 11px; font-weight: 650; }
.import-steps li span { display: grid; width: 22px; height: 22px; flex: none; place-items: center; border: 1px solid var(--border); border-radius: 50%; font-family: var(--font-utility); font-size: 9px; }
.import-steps li.active { color: var(--accent); }
.import-steps li.active span { border-color: var(--accent); color: #fff; background: var(--accent); }
.import-steps li.complete { color: var(--text-primary); }
.import-steps li.complete span { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.import-surface, .import-review-surface { display: grid; gap: 20px; padding: 26px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.import-section-heading { display: grid; grid-template-columns: 40px minmax(0, 1fr); align-items: center; gap: 12px; }
.import-section-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--accent); background: var(--accent-light); }
.section-kicker { margin: 0 0 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 10px; font-weight: 700; }
.import-section-heading h2 { margin: 0; color: var(--text-primary); font-size: 17px; }
.file-selection-form { display: grid; grid-template-columns: minmax(0, 1fr) 230px; align-items: start; gap: 16px 20px; }
.file-drop-zone { position: relative; grid-row: span 2; display: grid; justify-items: center; align-content: center; min-height: 190px; padding: 22px; border: 1px dashed color-mix(in srgb, var(--accent) 45%, var(--border)); border-radius: 6px; color: var(--accent); background: color-mix(in srgb, var(--accent-light) 55%, var(--bg-surface)); text-align: center; cursor: pointer; }
.file-drop-zone:hover { border-style: solid; background: var(--accent-light); }
.file-drop-zone input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.file-drop-zone strong { max-width: 100%; margin-top: 12px; overflow-wrap: anywhere; color: var(--text-primary); font-size: 14px; }
.file-drop-zone small { margin-top: 4px; color: var(--text-secondary); font-size: 11px; }
.file-drop-zone span { margin-top: 14px; color: var(--accent); font-size: 12px; font-weight: 700; }
.file-drop-zone:focus-within { outline: 3px solid var(--accent-light); border-color: var(--accent); }
.import-privacy-note { display: grid; grid-template-columns: 20px minmax(0, 1fr); gap: 8px; padding: 13px; border: 1px solid var(--border-soft); border-radius: 6px; color: var(--info); background: var(--info-light); }
.import-privacy-note p { display: grid; gap: 3px; margin: 0; }
.import-privacy-note strong { color: var(--text-primary); font-size: 11px; }
.import-privacy-note small { color: var(--text-secondary); font-size: 10px; line-height: 1.5; }
.file-selection-form .btn-neon { justify-self: stretch; justify-content: center; }
.review-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.storage-status { display: inline-flex; align-items: center; gap: 6px; min-height: 28px; padding: 5px 9px; border: 1px solid color-mix(in srgb, var(--success) 26%, var(--border)); border-radius: 5px; color: var(--success); background: color-mix(in srgb, var(--success) 8%, #fff); font-size: 10px; font-weight: 700; }
.review-guidance { margin: -4px 0 0; color: var(--text-secondary); font-size: 12px; line-height: 1.6; }
.extracted-editor { display: grid; gap: 7px; color: var(--text-secondary); font-size: 12px; font-weight: 650; }
.extracted-editor textarea { width: 100%; min-height: 310px; resize: vertical; padding: 14px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font: 12px/1.7 var(--font-utility); }
.extracted-editor textarea:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.review-actions { display: flex; align-items: start; justify-content: space-between; gap: 16px; padding-top: 4px; }
.review-actions details { min-width: 0; color: var(--text-secondary); font-size: 11px; }
.review-actions summary { cursor: pointer; font-weight: 650; }
.review-actions pre { max-width: 620px; max-height: 280px; overflow: auto; padding: 12px; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-page); font: 10px/1.55 var(--font-utility); white-space: pre-wrap; }
@media (max-width: 680px) { .import-heading h1 { font-size: 30px; } .import-steps { grid-template-columns: 1fr 1fr; } .file-selection-form { grid-template-columns: 1fr; } .file-drop-zone { grid-row: auto; min-height: 170px; } .import-surface, .import-review-surface { padding: 20px 16px; } .review-heading, .review-actions { align-items: stretch; flex-direction: column; } .review-actions .btn-neon { justify-content: center; } }
</style>
