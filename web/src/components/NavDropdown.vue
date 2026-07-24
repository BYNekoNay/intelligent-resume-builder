<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { ChevronDown } from 'lucide-vue-next'

defineProps<{
  label: string
  active?: boolean
}>()

const open = ref(false)
const dropdown = ref<HTMLElement | null>(null)

function toggle() { open.value = !open.value }
function close() { open.value = false }

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

function onClickOutside(e: MouseEvent) {
  if (dropdown.value && !dropdown.value.contains(e.target as Node)) close()
}

onMounted(() => {
  document.addEventListener('keydown', onKeydown)
  document.addEventListener('click', onClickOutside, true)
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', onKeydown)
  document.removeEventListener('click', onClickOutside, true)
})
</script>

<template>
  <div class="nav-group" ref="dropdown">
    <button
      type="button"
      class="nav-group-trigger"
      :class="{ active: active || open }"
      :aria-expanded="open"
      @click="toggle"
    >
      <span>{{ label }}</span>
      <ChevronDown :size="14" class="chevron" :class="{ open }" />
    </button>
    <div v-if="open" class="nav-group-menu" role="menu">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.nav-group {
  position: relative;
}

.nav-group-trigger {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 6px 12px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--text-secondary, #5a6679);
  font-size: 14px;
  font-weight: 500;
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
  transition: background 0.15s, color 0.15s;
}

.nav-group-trigger:hover {
  background: var(--surface-secondary, #f0f2f7);
  color: var(--text-primary, #1a1f36);
}

.nav-group-trigger.active,
.nav-group-trigger[aria-expanded="true"] {
  color: var(--accent-primary, #5444f0);
  background: color-mix(in srgb, var(--accent-primary, #5444f0) 8%, transparent);
}

.nav-group-trigger .chevron {
  transition: transform 0.2s;
  opacity: 0.6;
}

.nav-group-trigger .chevron.open {
  transform: rotate(180deg);
}

.nav-group-menu {
  position: absolute;
  top: calc(100% + 6px);
  left: 50%;
  transform: translateX(-50%);
  min-width: 160px;
  background: #ffffff;
  border: 1px solid var(--border-light, #e4e7ee);
  border-radius: 12px;
  box-shadow: 0 12px 32px rgba(0, 0, 0, 0.1), 0 2px 8px rgba(0, 0, 0, 0.05);
  padding: 6px;
  z-index: 100;
  animation: navMenuIn 0.15s ease-out;
}

@keyframes navMenuIn {
  from { opacity: 0; transform: translateX(-50%) translateY(-4px); }
  to { opacity: 1; transform: translateX(-50%) translateY(0); }
}

.nav-group-menu :deep(a) {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px;
  border-radius: 8px;
  color: var(--text-secondary, #5a6679);
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  white-space: nowrap;
  transition: background 0.12s, color 0.12s;
}

.nav-group-menu :deep(a:hover) {
  background: var(--surface-secondary, #f0f2f7);
  color: var(--text-primary, #1a1f36);
}

.nav-group-menu :deep(a.router-link-active) {
  background: color-mix(in srgb, var(--accent-primary, #5444f0) 10%, transparent);
  color: var(--accent-primary, #5444f0);
}

.nav-group-menu :deep(svg) {
  opacity: 0.5;
  flex-shrink: 0;
}
</style>
