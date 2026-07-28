<script setup lang="ts">
import { isAxiosError } from 'axios'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Check, FileInput, Lightbulb, ShieldCheck, Sparkles } from 'lucide-vue-next'
import { generateMaterialAssociation, generateResumeFromAssociation, generateResumeFromMaterial, type MaterialAssociationResponse, type MaterialGenerationResponse } from '@/api/materialGeneration'
import { createResume } from '@/api/resume'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const raw = ref(''); const result = ref<MaterialGenerationResponse | null>(null); const association = ref<MaterialAssociationResponse | null>(null); const error = ref(''); const consentRequired = ref(false); const loading = ref(false); const associating = ref(false); const title = ref('From raw materials'); const saving = ref(false)
const router = useRouter()

async function generate() {
  error.value = ''; consentRequired.value = false; result.value = null
  if (!raw.value.trim()) { error.value = t('materialGeneration.errorEmpty'); return }
  loading.value = true
  try { result.value = (await generateResumeFromMaterial(raw.value)).data.data }
  catch (cause) { showGenerationError(cause, 'errorGenerate') }
  finally { loading.value = false }
}

async function associate() {
  error.value = ''; consentRequired.value = false; association.value = null
  if (!raw.value.trim()) { error.value = t('materialGeneration.errorEmpty'); return }
  associating.value = true
  try { association.value = (await generateMaterialAssociation(raw.value)).data.data }
  catch (cause) { showGenerationError(cause, 'errorAssociation') }
  finally { associating.value = false }
}

async function generateFromAssociation() {
  if (!association.value?.expandedMaterial.trim()) return
  error.value = ''; consentRequired.value = false; result.value = null; loading.value = true
  try { result.value = (await generateResumeFromAssociation(raw.value, association.value.expandedMaterial)).data.data }
  catch (cause) { showGenerationError(cause, 'errorGenerate') }
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

function showGenerationError(cause: unknown, fallbackKey: 'errorGenerate' | 'errorAssociation') {
  consentRequired.value = isAxiosError(cause) && cause.response?.data?.code === 40302
  error.value = consentRequired.value ? t('materialGeneration.errorConsent') : generationError(cause, fallbackKey)
}

function manageConsent() {
  router.push({ name: 'ai-consent', query: { redirect: '/material-generation' } })
}

onMounted(() => {
  const imported = sessionStorage.getItem('resume-import-text')
  if (imported) { raw.value = imported; sessionStorage.removeItem('resume-import-text') }
})
</script>

<template>
  <section class="workspace-page material-generation-page">
    <header class="material-generation-heading">
      <p class="eyebrow"><FileInput :size="14" /> {{ t('materialGeneration.eyebrow') }}</p>
      <h1>{{ t('materialGeneration.title') }}</h1>
      <p class="page-lead">{{ t('materialGeneration.subtitle') }}</p>
      <div class="material-generation-route" aria-hidden="true"><span class="active">{{ t('materialGeneration.routeSource') }}</span><i></i><span>{{ t('materialGeneration.routeStructure') }}</span><i></i><span>{{ t('materialGeneration.routeCreate') }}</span></div>
    </header>
    <form class="material-source-panel" @submit.prevent="generate">
      <header class="generation-section-heading"><span><FileInput :size="19" /></span><div><p>{{ t('materialGeneration.sourceEyebrow') }}</p><h2>{{ t('materialGeneration.sourceTitle') }}</h2><small>{{ t('materialGeneration.sourceDescription') }}</small></div></header>
      <label class="raw-material-field">{{ t('materialGeneration.label') }}<textarea v-model="raw" rows="12" maxlength="30000" :placeholder="t('materialGeneration.placeholder')" /><small>{{ raw.length }} / 30000</small></label>
      <div class="material-actions">
        <button class="btn-neon btn-primary" :disabled="loading || associating"><Sparkles :size="16" />{{ loading ? t('materialGeneration.generating') : t('materialGeneration.generateButton') }}</button>
        <button class="btn-neon btn-secondary" type="button" :disabled="loading || associating" @click="associate"><Lightbulb :size="16" />{{ associating ? t('materialGeneration.associating') : t('materialGeneration.associateButton') }}</button>
      </div>
    </form>
    <div v-if="error" class="form-error material-error" role="alert">
      <span>{{ error }}</span>
      <button v-if="consentRequired" class="btn-neon btn-secondary" type="button" @click="manageConsent">{{ t('materialGeneration.goToConsent') }}</button>
    </div>
    <article v-if="association" class="generation-result-panel association-result">
      <header class="generation-section-heading"><span><Lightbulb :size="19" /></span><div><p>{{ t('materialGeneration.associationEyebrow') }}</p><h2>{{ t('materialGeneration.associationTitle') }}</h2><small>{{ t('materialGeneration.associationDisclaimer') }}</small></div></header>
      <textarea v-model="association.expandedMaterial" rows="12" :aria-label="t('materialGeneration.associationTitle')" />
      <button class="btn-neon btn-primary" type="button" :disabled="loading || !association.expandedMaterial.trim()" @click="generateFromAssociation"><Sparkles :size="16" />{{ loading ? t('materialGeneration.generating') : t('materialGeneration.generateFromAssociation') }}</button>
      <h3 v-if="association.verificationQuestions.length">{{ t('materialGeneration.verifyQuestionsTitle') }}</h3>
      <ul v-if="association.verificationQuestions.length"><li v-for="question in association.verificationQuestions" :key="question">{{ question }}</li></ul>
    </article>
    <article v-if="result" class="generation-result-panel draft-result">
      <header class="generation-section-heading"><span><Check :size="19" /></span><div><p>{{ t('materialGeneration.draftEyebrow') }}</p><h2>{{ t('materialGeneration.draftTitle') }}</h2><small>{{ t('materialGeneration.disclaimer') }}</small></div></header>
      <h3>{{ t('materialGeneration.suggestionsTitle') }}</h3>
      <ul><li v-for="suggestion in result.suggestions" :key="suggestion">{{ suggestion }}</li></ul>
      <label>{{ t('materialGeneration.resumeTitle') }}<input v-model.trim="title" maxlength="128" /></label>
      <details><summary>{{ t('materialGeneration.draftPreview') }}</summary><pre>{{ JSON.stringify(result.generatedResumeJson, null, 2) }}</pre></details>
      <footer><span><ShieldCheck :size="15" />{{ t('materialGeneration.createHint') }}</span><button class="btn-neon btn-secondary" type="button" :disabled="saving || !title.trim()" @click="createDraft">{{ saving ? t('materialGeneration.creating') : t('materialGeneration.createButton') }}<ArrowRight v-if="!saving" :size="16" /></button></footer>
    </article>
  </section>
</template>

<style scoped>
.material-generation-page { width: min(100%, 920px); max-width: 920px; gap: 24px; }
.material-generation-heading { display: grid; gap: 0; padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.material-generation-heading .eyebrow { justify-self: start; }
.material-generation-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.material-generation-heading .page-lead { max-width: 700px; font-size: 12px; }
.material-generation-route { display: grid; grid-template-columns: auto 34px auto 34px auto; align-items: center; justify-content: end; gap: 7px; margin-top: 20px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.material-generation-route i { height: 1px; background: var(--border); }
.material-generation-route .active { color: var(--accent); }
.material-source-panel, .generation-result-panel { display: grid; gap: 20px; padding: 24px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.material-source-panel { border-left: 4px solid var(--accent); }
.generation-section-heading { display: grid; grid-template-columns: 40px minmax(0, 1fr); align-items: start; gap: 12px; padding-bottom: 18px; border-bottom: 1px solid var(--border-soft); }
.generation-section-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--accent); background: var(--accent-light); }
.generation-section-heading p, .generation-section-heading h2, .generation-section-heading small { display: block; margin: 0; }
.generation-section-heading p { margin-bottom: 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.generation-section-heading h2 { color: var(--text-primary); font-size: 16px; }
.generation-section-heading small { margin-top: 5px; color: var(--text-secondary); font-size: 10px; line-height: 1.55; }
.raw-material-field, .generation-result-panel > label { position: relative; display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.raw-material-field textarea, .generation-result-panel textarea, .generation-result-panel input { width: 100%; padding: 12px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font: inherit; font-size: 12px; resize: vertical; }
.raw-material-field textarea:focus, .generation-result-panel textarea:focus, .generation-result-panel input:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.raw-material-field > small { position: absolute; right: 9px; bottom: 8px; padding: 2px 5px; color: var(--text-tertiary); background: var(--bg-input); font-family: var(--font-utility); font-size: 8px; }
.material-actions { display: flex; justify-content: flex-end; gap: 8px; }
.association-result { border-left: 4px solid var(--info); }
.association-result .generation-section-heading > span { color: var(--info); background: var(--info-light); }
.draft-result { border-left: 4px solid var(--success); }
.draft-result .generation-section-heading > span { color: var(--success); background: var(--success-light); }
.generation-result-panel h3 { margin: 0; color: var(--text-primary); font-size: 12px; }
.generation-result-panel ul { display: grid; gap: 6px; margin: -7px 0 0; padding-left: 20px; color: var(--text-secondary); font-size: 10px; line-height: 1.55; }
.generation-result-panel details { color: var(--text-secondary); font-size: 10px; }
.generation-result-panel summary { cursor: pointer; font-weight: 650; }
.generation-result-panel pre { max-height: 320px; overflow: auto; padding: 12px; border: 1px solid var(--border); border-radius: 5px; background: var(--bg-page); font: 9px/1.55 var(--font-utility); white-space: pre-wrap; }
.generation-result-panel footer { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-top: 15px; border-top: 1px solid var(--border-soft); }
.generation-result-panel footer > span { display: inline-flex; align-items: center; gap: 6px; color: var(--text-tertiary); font-size: 9px; }
@media (max-width: 620px) { .material-generation-heading h1 { font-size: 29px; } .material-generation-route { grid-template-columns: auto 15px auto 15px auto; justify-content: stretch; font-size: 8px; } .material-source-panel, .generation-result-panel { padding: 19px 15px; } .material-actions, .generation-result-panel footer { align-items: stretch; flex-direction: column; } .material-actions .btn-neon, .generation-result-panel footer .btn-neon { width: 100%; justify-content: center; } }
</style>
