<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { parseResumeFile, type ResumeImportResponse } from '@/api/resumeImport'
import { useLocale } from '@/i18n'

const selected = ref<File | null>(null)
const result = ref<ResumeImportResponse | null>(null)
const extractedText = ref('')
const loading = ref(false)
const error = ref('')
const router = useRouter()
const { t } = useLocale()

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
  <section class="workspace-page">
    <p class="eyebrow">{{ t('import.eyebrow') }}</p><h1>{{ t('import.title') }}</h1><p>{{ t('import.subtitle') }}</p>

    <form class="workspace-card" @submit.prevent="parse">
      <label>{{ t('import.file') }}<input type="file" accept=".pdf,.docx,.txt" @change="choose" /></label><button class="btn-neon btn-primary" :disabled="loading">{{ loading ? t('import.parsing') : t('import.parse') }}</button>
    </form>

    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <article v-if="result" class="workspace-card">
      <p class="disclaimer">{{ t('import.stored') }}: {{ result.originalFileStored ? t('import.yes') : t('import.no') }}. {{ t('import.review') }}</p><label>{{ t('import.extracted') }}<textarea v-model="extractedText" rows="16" maxlength="30000" /></label><div class="job-actions"><button class="btn-neon btn-secondary" type="button" @click="continueToGeneration">{{ t('import.use') }}</button></div><details><summary>{{ t('import.preview') }}</summary><pre>{{ JSON.stringify(result.normalizedResumeInput, null, 2) }}</pre></details>
    </article>
  </section>
</template>
