<script setup lang="ts">
/**
 * 统一按钮（2026-10-29 设计系统最小层）。
 *
 * 为什么要有它：`.btn-primary` 在 21 个 SFC 的 scoped style 里被**各自重定义**一遍，
 * `.btn-secondary` 另有 19 处。每加一个页面就再抄一份，改动永远是局部修复。
 *
 * 刻意**不重新实现样式**：直接复用 `styles/main.css` 里已有的 `.btn-*` 类。
 * 这样迁移是纯替换、零视觉风险；等所有调用点都收敛后，再统一收口样式本身。
 */
import { computed } from 'vue'

const props = withDefaults(defineProps<{
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger'
  size?: 'sm' | 'md'
  type?: 'button' | 'submit'
  loading?: boolean
  disabled?: boolean
  block?: boolean
}>(), {
  variant: 'primary',
  size: 'md',
  type: 'button',
  loading: false,
  disabled: false,
  block: false,
})

const classes = computed(() => [
  'btn',
  `btn-${props.variant}`,
  props.size === 'sm' ? 'btn-sm' : '',
  props.block ? 'btn-block' : '',
])
</script>

<template>
  <button
    :type="type"
    :class="classes"
    :disabled="disabled || loading"
    :aria-busy="loading || undefined"
  >
    <span v-if="loading" class="app-button__spinner" aria-hidden="true" />
    <slot />
  </button>
</template>

<style scoped>
.app-button__spinner {
  width: 12px;
  height: 12px;
  margin-right: 6px;
  border: 2px solid currentColor;
  border-right-color: transparent;
  border-radius: var(--radius-full);
  display: inline-block;
  vertical-align: -1px;
  animation: app-button-spin 0.7s linear infinite;
}

@keyframes app-button-spin {
  to { transform: rotate(360deg); }
}
</style>
