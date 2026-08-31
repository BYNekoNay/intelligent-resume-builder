<script setup lang="ts">
import { computed } from 'vue'
import { RefreshCw, RotateCcw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { useLocale } from '@/i18n'
import { lazyChunkRetryKey } from '@/router/lazyChunkRecovery'

const route = useRoute()
const router = useRouter()
const { t } = useLocale()

const retryPath = computed(() => {
  const value = Array.isArray(route.query.retry) ? route.query.retry[0] : route.query.retry
  return typeof value === 'string' && value.startsWith('/') && !value.startsWith('//') ? value : '/'
})

function retry() {
  try { sessionStorage.removeItem(lazyChunkRetryKey(retryPath.value)) } catch { /* storage is optional */ }
  void router.replace(retryPath.value)
}
</script>

<template>
  <section class="workspace-page route-load-error">
    <p class="eyebrow"><RefreshCw :size="14" />{{ t('routeLoadError.eyebrow') }}</p>
    <h1>{{ t('routeLoadError.title') }}</h1>
    <p class="route-load-error__description">{{ t('routeLoadError.description') }}</p>
    <div class="route-load-error__actions">
      <button class="btn-neon btn-primary" type="button" @click="retry"><RotateCcw :size="16" />{{ t('routeLoadError.retry') }}</button>
      <RouterLink class="btn-neon btn-ghost" to="/">{{ t('routeLoadError.home') }}</RouterLink>
    </div>
  </section>
</template>

<style scoped>
.route-load-error { width: min(100%, 620px); min-height: min(58vh, 500px); align-content: center; gap: 12px; }
.route-load-error h1 { margin: 0; font-family: var(--font-display); font-size: 32px; letter-spacing: 0; }
.route-load-error__description { max-width: 500px; margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.7; }
.route-load-error__actions { display: flex; flex-wrap: wrap; gap: 9px; margin-top: 10px; }
@media (max-width: 560px) { .route-load-error { min-height: 46vh; }.route-load-error h1 { font-size: 27px; }.route-load-error__actions { display: grid; grid-template-columns: 1fr; }.route-load-error__actions :deep(.btn-neon) { justify-content: center; width: 100%; } }
</style>
