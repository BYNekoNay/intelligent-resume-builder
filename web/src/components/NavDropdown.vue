<script setup lang="ts">
import { ref, onMounted, onBeforeUnmount, useId } from 'vue'
import { ChevronDown } from 'lucide-vue-next'

defineProps<{
  label: string
  active?: boolean
}>()

const open = ref(false)
const dropdown = ref<HTMLElement | null>(null)
const groupId = useId()

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
      :aria-controls="groupId"
      @click="toggle"
    >
      <span>{{ label }}</span>
      <ChevronDown :size="14" class="chevron" :class="{ open }" />
    </button>
    <div v-if="open" :id="groupId" class="nav-group-menu" role="group" :aria-label="label" @click="close">
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
  min-height: 36px;
  padding: 6px 10px;
  border: none;
  border-radius: 6px;
  background: transparent;
  color: var(--text-secondary, #56645c);
  font-size: 13px;
  font-weight: 650;
  cursor: pointer;
  font-family: inherit;
  white-space: nowrap;
  transition: background 0.15s, color 0.15s;
}

.nav-group-trigger:hover {
  background: var(--bg-surface, #fff);
  color: var(--text-primary, #17221b);
}

.nav-group-trigger.active,
.nav-group-trigger[aria-expanded="true"] {
  color: var(--accent, #1f674d);
  background: var(--accent-light, #e6f1eb);
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
  top: calc(100% + 8px);
  left: 50%;
  transform: translateX(-50%);
  min-width: 190px;
  background: #ffffff;
  border: 1px solid var(--border, #d9e1db);
  border-radius: 8px;
  box-shadow: 0 18px 44px rgba(24, 43, 33, 0.13);
  padding: 7px;
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
  border-radius: 5px;
  color: var(--text-secondary, #56645c);
  font-size: 13px;
  font-weight: 500;
  text-decoration: none;
  white-space: nowrap;
  transition: background 0.12s, color 0.12s;
}

.nav-group-menu :deep(.nav-item-copy) {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.nav-group-menu :deep(.nav-item-copy small) {
  max-width: 260px;
  color: var(--text-tertiary, #79867e);
  font-size: 10px;
  font-weight: 450;
  line-height: 1.4;
  white-space: normal;
}

.nav-group-menu :deep(a:hover) {
  background: var(--bg-page, #f4f7f3);
  color: var(--text-primary, #17221b);
}

.nav-group-menu :deep(a.router-link-active) {
  background: var(--accent-light, #e6f1eb);
  color: var(--accent, #1f674d);
}

.nav-group-menu :deep(svg) {
  opacity: 0.5;
  flex-shrink: 0;
}
</style>
