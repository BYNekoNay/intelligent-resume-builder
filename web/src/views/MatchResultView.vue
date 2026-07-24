<script setup lang="ts">
import { onMounted } from 'vue'
import { useMatchResultStore } from '@/stores/matchResult'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const props = defineProps<{ matchResultId: string }>()
const store = useMatchResultStore()

onMounted(() => store.load(Number(props.matchResultId)))
</script>

<template>
  <section class="workspace-page">
    <h1>{{ t('matchResult.title') }}</h1>
    <p v-if="store.loading">{{ t('matchResult.loading') }}</p>
    <p v-else-if="store.error" class="form-error" role="alert">{{ t('matchResult.error') }}</p>
    <p v-if="store.current" class="disclaimer">{{ store.current.explanation.disclaimer }}</p>
    <div v-if="store.current" class="workspace-card">
      <div class="score-grid">
        <p><strong>{{ t('matchResult.totalScore') }}</strong>{{ store.current.totalScore }}</p>
        <p><strong>{{ t('matchResult.keywordScore') }}</strong>{{ store.current.keywordScore }}</p>
        <p><strong>{{ t('matchResult.skillScore') }}</strong>{{ store.current.skillScore }}</p>
        <p><strong>{{ t('matchResult.experienceScore') }}</strong>{{ store.current.experienceScore }}</p>
      </div>
      <p>{{ t('matchResult.ruleVersion') }}: {{ store.current.ruleVersion }}</p>
      <details><summary>{{ t('matchResult.matched') }}</summary>
        <ul><li v-for="x in store.current.explanation.matched" :key="x">{{ x }}</li></ul>
      </details>
      <details><summary>{{ t('matchResult.partialMatch') }}</summary>
        <ul><li v-for="x in store.current.explanation.partialMatched" :key="x">{{ x }}</li></ul>
      </details>
      <details><summary>{{ t('matchResult.missing') }}</summary>
        <ul><li v-for="x in store.current.explanation.missing" :key="x">{{ x }}</li></ul>
      </details>
      <details><summary>{{ t('matchResult.suggestions') }}</summary>
        <ul><li v-for="x in store.current.explanation.suggestions" :key="x">{{ x }}</li></ul>
      </details>
    </div>
    <p v-else-if="!store.loading && !store.error" class="empty-state">{{ t('matchResult.empty') }}</p>
  </section>
</template>
