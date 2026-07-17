<script setup lang="ts">
import { onMounted } from 'vue'
import { useMatchResultStore } from '@/stores/matchResult'

const props = defineProps<{ matchResultId: string }>()
const store = useMatchResultStore()

onMounted(() => store.load(Number(props.matchResultId)))
</script>

<template>
  <section class="workspace-page">
    <h1>JD 规则覆盖度</h1>
    <p v-if="store.loading">正在加载规则覆盖度…</p>
    <p v-else-if="store.error" class="form-error" role="alert">评分结果无法加载，请返回简历版本后重试。</p>
    <p v-if="store.current" class="disclaimer">{{ store.current.explanation.disclaimer }}</p>
    <div v-if="store.current" class="workspace-card">
      <div class="score-grid">
        <p><strong>总分</strong>{{ store.current.totalScore }}</p>
        <p><strong>关键词</strong>{{ store.current.keywordScore }}</p>
        <p><strong>技能</strong>{{ store.current.skillScore }}</p>
        <p><strong>经历</strong>{{ store.current.experienceScore }}</p>
      </div>
      <p>规则版本: {{ store.current.ruleVersion }}</p>
      <details><summary>完全匹配</summary>
        <ul><li v-for="x in store.current.explanation.matched" :key="x">{{ x }}</li></ul>
      </details>
      <details><summary>同义词命中</summary>
        <ul><li v-for="x in store.current.explanation.partialMatched" :key="x">{{ x }}</li></ul>
      </details>
      <details><summary>缺失</summary>
        <ul><li v-for="x in store.current.explanation.missing" :key="x">{{ x }}</li></ul>
      </details>
      <details><summary>改进建议</summary>
        <ul><li v-for="x in store.current.explanation.suggestions" :key="x">{{ x }}</li></ul>
      </details>
    </div>
    <p v-else-if="!store.loading && !store.error" class="empty-state">暂无可展示的规则覆盖度结果。</p>
  </section>
</template>
