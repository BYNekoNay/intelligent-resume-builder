<script setup lang="ts">
import { isAxiosError } from 'axios'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { generateMaterialAssociation, generateResumeFromAssociation, generateResumeFromMaterial, type MaterialAssociationResponse, type MaterialGenerationResponse } from '@/api/materialGeneration'
import { createResume } from '@/api/resume'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const raw = ref(''); const result = ref<MaterialGenerationResponse | null>(null); const association = ref<MaterialAssociationResponse | null>(null); const error = ref(''); const loading = ref(false); const associating = ref(false); const title = ref('From raw materials'); const saving = ref(false)
const router = useRouter()

async function generate() {
  error.value = ''; result.value = null
  if (!raw.value.trim()) { error.value = t('materialGeneration.errorEmpty'); return }
  loading.value = true
  try { result.value = (await generateResumeFromMaterial(raw.value)).data.data }
  catch (cause) { error.value = generationError(cause, 'errorGenerate') }
  finally { loading.value = false }
}

async function associate() {
  error.value = ''; association.value = null
  if (!raw.value.trim()) { error.value = t('materialGeneration.errorEmpty'); return }
  associating.value = true
  try { association.value = (await generateMaterialAssociation(raw.value)).data.data }
  catch (cause) { error.value = generationError(cause, 'errorAssociation') }
  finally { associating.value = false }
}

async function generateFromAssociation() {
  if (!association.value?.expandedMaterial.trim()) return
  error.value = ''; result.value = null; loading.value = true
  try { result.value = (await generateResumeFromAssociation(raw.value, association.value.expandedMaterial)).data.data }
  catch (cause) { error.value = generationError(cause, 'errorGenerate') }
  finally { loading.value = false }
}

async function createDraft() {
  if (!result.value || !title.value.trim()) return
  saving.value = true; error.value = ''
  try {
    const resume = (await createResume(title.value.trim(), result.value.generatedResumeJson)).data.data
    await router.push({ name: 'resume-detail', params: { id: resume.id } })
  } catch { error.value = t('materialGeneration.errorCreate') }
  finally { saving.value = false }
}

function generationError(cause: unknown, fallbackKey: 'errorGenerate' | 'errorAssociation') {
  if (!isAxiosError(cause) || !cause.response) return t('materialGeneration.errorNetwork')
  return cause.response.data?.message || t(`materialGeneration.${fallbackKey}`)
}

onMounted(() => {
  const imported = sessionStorage.getItem('resume-import-text')
  if (imported) { raw.value = imported; sessionStorage.removeItem('resume-import-text') }
})
</script>

<template>
  <section class="workspace-page">
    <p class="eyebrow">{{ t('materialGeneration.eyebrow') }}</p>
    <h1>{{ t('materialGeneration.title') }}</h1>
    <p>{{ t('materialGeneration.subtitle') }}</p>
    <form class="workspace-card" @submit.prevent="generate">
      <label>{{ t('materialGeneration.label') }}<textarea v-model="raw" rows="12" maxlength="30000" :placeholder="t('materialGeneration.placeholder')" /></label>
      <div class="material-actions">
        <button class="btn-neon btn-primary" :disabled="loading || associating">{{ loading ? t('materialGeneration.generating') : t('materialGeneration.generateButton') }}</button>
        <button class="btn-neon btn-secondary" type="button" :disabled="loading || associating" @click="associate">{{ associating ? t('materialGeneration.associating') : t('materialGeneration.associateButton') }}</button>
      </div>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <article v-if="association" class="workspace-card association-result">
      <p class="disclaimer">{{ association.disclaimer || t('materialGeneration.associationDisclaimer') }}</p>
      <h2>{{ t('materialGeneration.associationTitle') }}</h2>
      <textarea v-model="association.expandedMaterial" rows="12" :aria-label="t('materialGeneration.associationTitle')" />
      <button class="btn-neon btn-primary" type="button" :disabled="loading || !association.expandedMaterial.trim()" @click="generateFromAssociation">{{ loading ? t('materialGeneration.generating') : t('materialGeneration.generateFromAssociation') }}</button>
      <h3 v-if="association.verificationQuestions.length">{{ t('materialGeneration.verifyQuestionsTitle') }}</h3>
      <ul v-if="association.verificationQuestions.length"><li v-for="question in association.verificationQuestions" :key="question">{{ question }}</li></ul>
    </article>
    <article v-if="result" class="workspace-card">
      <p class="disclaimer">{{ t('materialGeneration.disclaimer') }}</p>
      <h2>{{ t('materialGeneration.suggestionsTitle') }}</h2>
      <ul><li v-for="suggestion in result.suggestions" :key="suggestion">{{ suggestion }}</li></ul>
      <label>{{ t('materialGeneration.resumeTitle') }}<input v-model.trim="title" maxlength="128" /></label>
      <button class="btn-neon btn-secondary" type="button" :disabled="saving || !title.trim()" @click="createDraft">{{ saving ? t('materialGeneration.creating') : t('materialGeneration.createButton') }}</button>
      <details><summary>{{ t('materialGeneration.draftPreview') }}</summary><pre>{{ JSON.stringify(result.generatedResumeJson, null, 2) }}</pre></details>
    </article>
  </section>
</template>
