<script setup lang="ts">
/**
 * 空态（2026-10-29）。
 *
 * 背景：InterviewView 与 GenerationConfirmView 此前**没有任何空态分支**
 * （`empty` 关键词命中 0）；其余页面的空态各写一套 `<p class="empty">`，文案与留白都不统一。
 *
 * 结构：可选图标插槽 + 标题 + 说明 + 主操作插槽。刻意不带任何业务语义，
 * 便于在"没有资料/没有投递/没有面试/没有版本"等场景复用。
 */
defineProps<{
  title: string
  description?: string
}>()
</script>

<template>
  <div class="empty-state" role="status">
    <div v-if="$slots.icon" class="empty-state__icon" aria-hidden="true">
      <slot name="icon" />
    </div>
    <p class="empty-state__title">{{ title }}</p>
    <p v-if="description" class="empty-state__description">{{ description }}</p>
    <div v-if="$slots.action" class="empty-state__action">
      <slot name="action" />
    </div>
  </div>
</template>

<style scoped>
.empty-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 32px 16px;
  text-align: center;
  color: var(--text-secondary);
}

.empty-state__icon {
  color: var(--text-tertiary);
  margin-bottom: 2px;
}

.empty-state__title {
  margin: 0;
  font-size: 14px;
  font-weight: 600;
  color: var(--text-primary);
}

.empty-state__description {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.6;
  max-width: 42ch;
  color: var(--text-secondary);
}

.empty-state__action {
  margin-top: 6px;
}
</style>
