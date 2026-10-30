<script setup lang="ts">
import { useLocale } from '@/i18n'
import { ArrowLeft, ShieldCheck } from 'lucide-vue-next'
const { t } = useLocale()
</script>

<template>
  <main class="privacy-page">
    <section class="privacy-panel">
      <ShieldCheck :size="28" />
      <p class="eyebrow">{{ t('privacy.eyebrow') }}</p>
      <h1>{{ t('privacy.title') }}</h1>
      <p class="updated">{{ t('privacy.updated') }}</p>

      <!-- 五节显式展开：i18n 门禁要求 t() 的键是完整字面量（不可用循环拼接），可追溯性也更好 -->
      <section class="privacy-section">
        <h2>{{ t('privacy.sections.scope.title') }}</h2>
        <p class="body">{{ t('privacy.sections.scope.body') }}</p>
      </section>
      <section class="privacy-section">
        <h2>{{ t('privacy.sections.ai.title') }}</h2>
        <p class="body">{{ t('privacy.sections.ai.body') }}</p>
      </section>
      <section class="privacy-section">
        <h2>{{ t('privacy.sections.retention.title') }}</h2>
        <p class="body">{{ t('privacy.sections.retention.body') }}</p>
      </section>
      <section class="privacy-section">
        <h2>{{ t('privacy.sections.rights.title') }}</h2>
        <p class="body">{{ t('privacy.sections.rights.body') }}</p>
      </section>
      <section class="privacy-section">
        <h2>{{ t('privacy.sections.boundary.title') }}</h2>
        <p class="body">{{ t('privacy.sections.boundary.body') }}</p>
      </section>

      <RouterLink class="btn-neon btn-primary" to="/"><ArrowLeft :size="16" />{{ t('privacy.backHome') }}</RouterLink>
    </section>
  </main>
</template>

<style scoped>
.privacy-page { display: grid; min-height: 100vh; place-items: start center; padding: 24px; background: var(--bg-page); }
.privacy-panel { display: grid; justify-items: start; gap: 10px; width: min(100%, 720px); padding: 30px 0; border-block: 1px solid var(--border); }
.privacy-panel > svg { color: var(--accent); }
.privacy-panel h1 { margin: 0; font-family: var(--font-display); font-size: 36px; letter-spacing: 0; }
.privacy-panel .updated { margin: 0; color: var(--text-secondary); font-size: 12px; }
.privacy-section { display: grid; gap: 6px; margin-top: 14px; }
.privacy-section h2 { margin: 0; font-size: 16px; }
.privacy-section .body { margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.8; white-space: pre-line; }
.privacy-panel a { margin-top: 14px; text-decoration: none; }
</style>
