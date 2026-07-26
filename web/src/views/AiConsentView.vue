<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ShieldCheck, ShieldOff } from 'lucide-vue-next'
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
  <section class="workspace-page narrow-page">
    <p class="eyebrow"><ShieldCheck :size="14" /> {{ t('aiConsent.eyebrow') }}</p>
    <h1>{{ t('aiConsent.title') }}</h1>
    <p class="page-lead">{{ t('aiConsent.subtitle') }}</p>
    <article class="workspace-card consent-card">
      <h2>{{ t('aiConsent.scopeTitle') }}</h2>
      <ul>
        <li>{{ t('aiConsent.scope1') }}</li>
        <li>{{ t('aiConsent.scope2') }}</li>
        <li>{{ t('aiConsent.scope3') }}</li>
      </ul>
      <p class="status-line" :class="{ success: granted }" role="status">{{ message }}</p>
      <p v-if="consent" class="status-line">{{ t('aiConsent.currentEvent') }}：{{ consent.status }} · {{ t('aiConsent.provider') }}：{{ consent.providerCode }} · {{ t('aiConsent.scope') }}：{{ consent.taskScopes.join(', ') || '—' }}</p>
      <button v-if="!granted" class="btn-neon btn-primary" :disabled="loading" @click="grant">
        <ShieldCheck :size="16" /> {{ loading ? t('aiConsent.granting') : t('aiConsent.grantButton') }}
      </button>
      <button v-else class="btn-neon btn-ghost" :disabled="loading" @click="withdraw">
        <ShieldOff :size="16" /> {{ loading ? t('aiConsent.withdrawing') : t('aiConsent.withdrawButton') }}
      </button>
    </article>
  </section>
</template>
