<script setup lang="ts">
import { onMounted } from 'vue'
import { AlertTriangle, CheckCircle2, Gauge, Lightbulb, MinusCircle } from 'lucide-vue-next'
import { useMatchResultStore } from '@/stores/matchResult'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const props = defineProps<{ matchResultId: string }>()
const store = useMatchResultStore()

onMounted(() => store.load(Number(props.matchResultId)))
</script>

<template>
  <section class="workspace-page match-page">
    <header class="match-heading"><p class="eyebrow"><Gauge :size="14" />{{ t('matchResult.eyebrow') }}</p><h1>{{ t('matchResult.title') }}</h1><p class="page-lead">{{ t('matchResult.subtitle') }}</p></header>
    <p v-if="store.loading">{{ t('matchResult.loading') }}</p>
    <p v-else-if="store.error" class="form-error" role="alert">{{ t('matchResult.error') }}</p>
    <div v-if="store.current" class="match-report">
      <header><div><p class="section-kicker">{{ t('matchResult.reportEyebrow') }}</p><h2>{{ t('matchResult.reportTitle') }}</h2></div><span>{{ t('matchResult.ruleVersion') }} {{ store.current.ruleVersion }}</span></header>
      <div class="score-grid">
        <p class="total"><strong>{{ t('matchResult.totalScore') }}</strong><span>{{ store.current.totalScore }}</span></p>
        <p><strong>{{ t('matchResult.keywordScore') }}</strong><span>{{ store.current.keywordScore }}</span></p>
        <p><strong>{{ t('matchResult.skillScore') }}</strong><span>{{ store.current.skillScore }}</span></p>
        <p><strong>{{ t('matchResult.experienceScore') }}</strong><span>{{ store.current.experienceScore }}</span></p>
      </div>
      <div class="match-findings"><details open class="matched"><summary><CheckCircle2 :size="15" />{{ t('matchResult.matched') }}<b>{{ store.current.explanation.matched.length }}</b></summary>
        <ul><li v-for="x in store.current.explanation.matched" :key="x">{{ x }}</li></ul>
      </details>
      <details class="partial"><summary><MinusCircle :size="15" />{{ t('matchResult.partialMatch') }}<b>{{ store.current.explanation.partialMatched.length }}</b></summary>
        <ul><li v-for="x in store.current.explanation.partialMatched" :key="x">{{ x }}</li></ul>
      </details>
      <details open class="missing"><summary><AlertTriangle :size="15" />{{ t('matchResult.missing') }}<b>{{ store.current.explanation.missing.length }}</b></summary>
        <ul><li v-for="x in store.current.explanation.missing" :key="x">{{ x }}</li></ul>
      </details>
      <details open class="suggestions"><summary><Lightbulb :size="15" />{{ t('matchResult.suggestions') }}<b>{{ store.current.explanation.suggestions.length }}</b></summary>
        <ul><li v-for="x in store.current.explanation.suggestions" :key="x">{{ x }}</li></ul>
      </details></div>
      <p class="match-disclaimer">{{ store.current.explanation.disclaimer }}</p>
    </div>
    <p v-else-if="!store.loading && !store.error" class="empty-state">{{ t('matchResult.empty') }}</p>
  </section>
</template>

<style scoped>
.match-page { width: min(100%, 920px); max-width: 920px; gap: 24px; }.match-heading { padding-bottom: 22px; border-bottom: 1px solid var(--border); }.match-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }.match-heading .page-lead { max-width: 650px; font-size: 12px; }.match-report { display: grid; gap: 18px; padding: 24px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }.match-report > header { display: flex; align-items: end; justify-content: space-between; gap: 16px; }.section-kicker { margin: 0 0 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }.match-report header h2 { margin: 0; font-size: 16px; }.match-report header > span { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; }.match-report .score-grid { grid-template-columns: repeat(4, 1fr); gap: 0; margin: 0; border-block: 1px solid var(--border); }.match-report .score-grid p { display: grid; gap: 5px; margin: 0; padding: 14px; border: 0; border-right: 1px solid var(--border-soft); border-radius: 0; background: transparent; }.match-report .score-grid p:last-child { border-right: 0; }.match-report .score-grid strong { color: var(--text-tertiary); font-size: 9px; }.match-report .score-grid span { color: var(--text-primary); font-family: var(--font-utility); font-size: 22px; font-weight: 700; }.match-report .score-grid .total span { color: var(--accent); }.match-findings { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }.match-findings details { border: 1px solid var(--border-soft); border-radius: 6px; background: var(--bg-page); }.match-findings summary { display: flex; align-items: center; gap: 7px; padding: 13px 15px; color: var(--text-primary); font-size: 11px; font-weight: 700; cursor: pointer; }.match-findings summary b { margin-left: auto; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; }.match-findings details[open] summary { border-bottom: 1px solid var(--border-soft); }.match-findings .matched summary { color: var(--success); }.match-findings .missing summary { color: var(--warning); }.match-findings .suggestions summary { color: var(--info); }.match-findings ul { display: grid; gap: 6px; margin: 0; padding: 13px 18px 15px 32px; color: var(--text-secondary); font-size: 10px; line-height: 1.5; }.match-disclaimer { margin: 0; color: var(--text-tertiary); font-size: 9px; line-height: 1.55; }
@media (max-width: 680px) { .match-heading h1 { font-size: 29px; }.match-report { padding: 20px 16px; }.match-report > header { align-items: start; flex-direction: column; }.match-report .score-grid { grid-template-columns: 1fr 1fr; }.match-report .score-grid p:nth-child(2) { border-right: 0; }.match-report .score-grid p:nth-child(-n+2) { border-bottom: 1px solid var(--border-soft); }.match-findings { grid-template-columns: 1fr; } }
</style>
