<script setup lang="ts">
import { useLocale } from '@/i18n'
const { t } = useLocale()
</script>

<template>
  <main class="auth-page">
    <section class="auth-panel">
      <p class="eyebrow">{{ t('notFound.eyebrow') }}</p>
      <h1>{{ t('notFound.title') }}</h1>
      <RouterLink to="/">{{ t('notFound.backHome') }}</RouterLink>
    </section>
  </main>
</template>
