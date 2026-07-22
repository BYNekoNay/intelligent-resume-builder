<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { parseResumeFile, type ResumeImportResponse } from '@/api/resumeImport'

const selected = ref<File | null>(null)
const result = ref<ResumeImportResponse | null>(null)
const extractedText = ref('')
const loading = ref(false)
const error = ref('')
const router = useRouter()

function choose(event: Event) {
  selected.value = (event.target as HTMLInputElement).files?.[0] ?? null
  result.value = null
  extractedText.value = ''
  error.value = ''
}

async function parse() {
  if (!selected.value) {
    error.value = 'Select a PDF, DOCX, or TXT file first.'
    return
  }
  loading.value = true
  error.value = ''
  try {
    result.value = (await parseResumeFile(selected.value)).data.data
    extractedText.value = result.value.extractedText
  } catch {
    error.value = 'Unable to parse this file. Check its format, size, and content.'
  } finally {
    loading.value = false
  }
}

async function continueToGeneration() {
  if (!result.value || !extractedText.value.trim()) {
    error.value = 'Review the extracted text before continuing.'
    return
  }
  sessionStorage.setItem('resume-import-text', extractedText.value.trim())
  await router.push({ name: 'material-generation' })
}
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">Resume import</p>
    <h1>Import an Existing Resume</h1>
    <p>Upload a PDF, DOCX, or TXT file up to 5 MB. The source file is parsed but not stored.</p>

    <form class="workspace-card" @submit.prevent="parse">
      <label>Resume file<input type="file" accept=".pdf,.docx,.txt" @change="choose" /></label>
      <button class="btn-neon btn-primary" :disabled="loading">{{ loading ? 'Parsing...' : 'Parse text' }}</button>
    </form>

    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <article v-if="result" class="workspace-card">
      <p class="disclaimer">Original file stored: {{ result.originalFileStored ? 'yes' : 'no' }}. Review and correct the text before using it for generation.</p>
      <label>Extracted text<textarea v-model="extractedText" rows="16" maxlength="30000" /></label>
      <div class="job-actions"><button class="btn-neon btn-secondary" type="button" @click="continueToGeneration">Use corrected text</button></div>
      <details><summary>Normalized input preview</summary><pre>{{ JSON.stringify(result.normalizedResumeInput, null, 2) }}</pre></details>
    </article>
  </section>
</template>
