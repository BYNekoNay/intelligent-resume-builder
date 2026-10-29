<script setup lang="ts">
/**
 * 骨架屏（2026-10-29）。
 *
 * 背景：全仓此前 `skeleton` 命中 **0 处** —— 加载态普遍是一行文字（"正在加载投递记录..."）
 * 或干脆什么都没有（AccountView / ResumeDetailView 连 loading 分支都没有）。
 * 在动辄 10 分钟级 AI 任务的系统里，用户看到的是空白或单行文本。
 *
 * 设计取舍：不引入任何图标/骨架库，用纯 CSS + `aria-busy`。
 * 无障碍上必须同时给 role="status" 与可读文本（`.sr-only`），否则屏幕阅读器只知道"忙"。
 */
import { computed } from 'vue'
import { useLocale } from '@/i18n'

const props = withDefaults(defineProps<{
  /** 占位行数（卡片列表场景给 3-5） */
  rows?: number
  /** 自定义无障碍标签，默认「加载中」 */
  label?: string
}>(), { rows: 3 })

const { t } = useLocale()
const ariaLabel = computed(() => props.label ?? t('common.loading'))
</script>

<template>
  <div class="loading-block" role="status" aria-busy="true" :aria-label="ariaLabel">
    <span class="sr-only">{{ ariaLabel }}</span>
    <span
      v-for="row in rows"
      :key="row"
      class="loading-block__line"
      :style="{ width: `${100 - (row % 3) * 12}%` }"
      aria-hidden="true"
    />
  </div>
</template>

<style scoped>
.loading-block {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 16px;
  min-height: 64px;
}

.loading-block__line {
  display: block;
  height: 12px;
  border-radius: var(--radius-sm);
  background: linear-gradient(90deg, var(--border-soft) 25%, var(--bg-page) 37%, var(--border-soft) 63%);
  background-size: 400% 100%;
  animation: loading-block-shimmer 1.4s ease infinite;
}

@keyframes loading-block-shimmer {
  0% { background-position: 100% 50%; }
  100% { background-position: 0 50%; }
}

/* 尊重「减少动态效果」偏好：骨架屏不是必须动画的装饰 */
@media (prefers-reduced-motion: reduce) {
  .loading-block__line { animation: none; }
}
</style>
