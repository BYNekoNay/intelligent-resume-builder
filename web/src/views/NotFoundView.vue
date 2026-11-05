<!--
  404 兜底页：匹配未定义路径（/:pathMatch(.*)*），静态引入、不依赖任何接口，保证路由解析失败时也能渲染。
  只有「回首页」一个出口，不放在 AppLayout 内，因此没有导航栏。
-->
<script setup lang="ts">
import { useLocale } from '@/i18n'
import { ArrowLeft, MapPinOff } from 'lucide-vue-next'
const { t } = useLocale()
</script>

<template>
  <main class="not-found-page">
    <section class="not-found-panel">
      <MapPinOff :size="28" />
      <p class="eyebrow">{{ t('notFound.eyebrow') }}</p>
      <h1>{{ t('notFound.title') }}</h1>
      <p>{{ t('notFound.subtitle') }}</p>
      <RouterLink class="btn-neon btn-primary" to="/"><ArrowLeft :size="16" />{{ t('notFound.backHome') }}</RouterLink>
    </section>
  </main>
</template>

<style scoped>
.not-found-page { display: grid; min-height: 100vh; place-items: center; padding: 24px; background: var(--bg-page); }.not-found-panel { display: grid; justify-items: start; gap: 10px; width: min(100%, 520px); padding: 30px 0; border-block: 1px solid var(--border); }.not-found-panel > svg { color: var(--accent); }.not-found-panel h1 { margin: 0; font-family: var(--font-display); font-size: 36px; letter-spacing: 0; }.not-found-panel p:not(.eyebrow) { margin: 0 0 10px; color: var(--text-secondary); font-size: 12px; }.not-found-panel a { text-decoration: none; }
</style>
