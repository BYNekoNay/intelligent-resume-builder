<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { CheckCircle2, Database, Eye, RotateCcw, ShieldCheck, ShieldOff } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { getConsent, grantConsent, hasJobGenerationConsent, JOB_GENERATION_DATA_CATEGORIES, JOB_GENERATION_POLICY_VERSION, withdrawConsent, type ConsentResponse } from '@/api/ai'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const route = useRoute()
const router = useRouter()
const consent = ref<ConsentResponse | null>(null)
const loading = ref(true)
const message = ref('')
const granted = computed(() => hasJobGenerationConsent(consent.value))
const consentStatus = computed(() => granted.value ? t('aiConsent.statusGranted') : consent.value?.status === 'WITHDRAWN' ? t('aiConsent.statusWithdrawn') : t('aiConsent.statusPending'))
const scopeCount = computed(() => consent.value?.taskScopes.length ?? 0)
const redirectAfterConsent = computed(() => {
  const redirect = route.query.redirect
  return typeof redirect === 'string' && redirect.startsWith('/') && !redirect.startsWith('//') ? redirect : null
})

onMounted(async () => {
  try {
    consent.value = (await getConsent()).data.data
    if (granted.value) message.value = t('aiConsent.granted')
    else if (consent.value?.status === 'WITHDRAWN') message.value = t('aiConsent.withdrawn')
    else message.value = t('aiConsent.defaultMessage')
  } catch { message.value = t('aiConsent.loadError') }
  finally { loading.value = false }
})

async function grant() {
  loading.value = true
  try {
    await grantConsent({
      policyVersion: JOB_GENERATION_POLICY_VERSION, providerCode: 'bailian',
      taskScopes: ['JOB_MATERIAL_SELECTION', 'JOB_GENERATION', 'RESUME_OPTIMIZE', 'ACHIEVEMENT_GUIDANCE', 'COMMUNICATION_GENERATE', 'MATERIAL_IMPORT', 'INLINE_OPTIMIZE'],
      dataCategories: ['RESUME', ...JOB_GENERATION_DATA_CATEGORIES],
      noticeHash: 'personal-profile-selection-v1.1.0',
    })
    consent.value = (await getConsent()).data.data
    message.value = t('aiConsent.grantSuccess')
    if (redirectAfterConsent.value) await router.replace(redirectAfterConsent.value)
  } catch { message.value = t('aiConsent.grantError') }
  finally { loading.value = false }
}

async function withdraw() {
  loading.value = true
  try {
    await withdrawConsent()
    consent.value = (await getConsent()).data.data
    message.value = t('aiConsent.withdrawSuccess')
  } catch { message.value = t('aiConsent.withdrawError') }
  finally { loading.value = false }
}
</script>

<template>
  <section class="workspace-page narrow-page consent-page">
    <header class="consent-heading"><p class="eyebrow"><ShieldCheck :size="14" />{{ t('aiConsent.eyebrow') }}</p><h1>{{ t('aiConsent.title') }}</h1><p class="page-lead">{{ t('aiConsent.subtitle') }}</p></header>
    <article class="workspace-card consent-card">
      <header><div><p class="section-kicker">{{ t('aiConsent.controlEyebrow') }}</p><h2>{{ t('aiConsent.scopeTitle') }}</h2></div><span class="consent-status" :class="{ granted }"><CheckCircle2 v-if="granted" :size="14" /><ShieldOff v-else :size="14" />{{ consentStatus }}</span></header>
      <div class="consent-scope"><section><Database :size="18" /><div><h3>{{ t('aiConsent.dataTitle') }}</h3><p>{{ t('aiConsent.scope1') }}</p></div></section><section><Eye :size="18" /><div><h3>{{ t('aiConsent.reviewTitle') }}</h3><p>{{ t('aiConsent.scope2') }}</p></div></section><section><RotateCcw :size="18" /><div><h3>{{ t('aiConsent.controlTitle') }}</h3><p>{{ t('aiConsent.scope3') }}</p></div></section></div>
      <p class="consent-message" role="status">{{ message }}</p>
      <dl v-if="consent" class="consent-metadata"><div><dt>{{ t('aiConsent.currentEvent') }}</dt><dd>{{ consentStatus }}</dd></div><div><dt>{{ t('aiConsent.provider') }}</dt><dd>{{ consent.providerCode }}</dd></div><div><dt>{{ t('aiConsent.scope') }}</dt><dd>{{ scopeCount }}</dd></div></dl>
      <button v-if="!granted" class="btn-neon btn-primary" :disabled="loading" @click="grant">
        <ShieldCheck :size="16" /> {{ loading ? t('aiConsent.granting') : t('aiConsent.grantButton') }}
      </button>
      <button v-else class="btn-neon btn-ghost" :disabled="loading" @click="withdraw">
        <ShieldOff :size="16" /> {{ loading ? t('aiConsent.withdrawing') : t('aiConsent.withdrawButton') }}
      </button>
    </article>
  </section>
</template>

<style scoped>
.consent-page { width: min(100%, 820px); max-width: 820px; gap: 24px; }.consent-heading { padding-bottom: 22px; border-bottom: 1px solid var(--border); }.consent-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }.consent-heading .page-lead { max-width: 650px; font-size: 12px; }.consent-card { display: grid; gap: 18px; padding: 24px; border-left: 4px solid var(--info); }.consent-card > header { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding-bottom: 14px; border-bottom: 1px solid var(--border-soft); }.section-kicker { margin: 0 0 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }.consent-card h2 { margin: 0; font-size: 16px; }.consent-status { display: inline-flex; align-items: center; gap: 5px; padding: 6px 8px; border-radius: 5px; color: var(--warning); background: var(--warning-light); font-size: 9px; font-weight: 700; }.consent-status.granted { color: var(--success); background: var(--success-light); }.consent-scope { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px; }.consent-scope section { display: grid; align-content: start; gap: 10px; padding: 14px; border: 1px solid var(--border-soft); border-radius: 6px; background: var(--bg-page); color: var(--info); }.consent-scope h3 { margin: 0 0 5px; color: var(--text-primary); font-size: 11px; }.consent-scope p { margin: 0; color: var(--text-secondary); font-size: 9px; line-height: 1.55; }.consent-message { margin: 0; padding: 11px 13px; border-left: 2px solid var(--accent); color: var(--text-secondary); background: var(--accent-light); font-size: 10px; line-height: 1.55; }.consent-metadata { display: grid; grid-template-columns: repeat(3, 1fr); margin: 0; border-block: 1px solid var(--border-soft); }.consent-metadata div { padding: 10px; border-right: 1px solid var(--border-soft); }.consent-metadata div:last-child { border-right: 0; }.consent-metadata dt { color: var(--text-tertiary); font-size: 8px; }.consent-metadata dd { margin: 4px 0 0; color: var(--text-primary); font-family: var(--font-utility); font-size: 10px; font-weight: 700; }.consent-card > .btn-neon { justify-self: start; }
@media (max-width: 680px) { .consent-heading h1 { font-size: 29px; }.consent-card { padding: 20px 16px; }.consent-card > header { align-items: start; flex-direction: column; }.consent-scope { grid-template-columns: 1fr; }.consent-metadata { grid-template-columns: 1fr; }.consent-metadata div { border-right: 0; border-bottom: 1px solid var(--border-soft); }.consent-metadata div:last-child { border-bottom: 0; }.consent-card > .btn-neon { width: 100%; justify-content: center; } }
</style>
