<script setup lang="ts">
import { onMounted } from 'vue'
import { useLocale } from '@/i18n'
import { useDesktopMode } from '@/composables/useDesktopMode'
import BackupPanel from '@/components/desktop/BackupPanel.vue'
import ImportPanel from '@/components/desktop/ImportPanel.vue'

const { t } = useLocale()
const { available, probe } = useDesktopMode()

// 路由守卫已经等过探测；这里再调一次是幂等的（共享同一个 Promise），
// 覆盖「用户直接刷新该页」与「守卫被跳过」的情况。
onMounted(() => { void probe() })
</script>

<template>
  <main class="workspace-page desktop-workspace">
    <header>
      <p class="eyebrow">{{ t('desktop.eyebrow') }}</p>
      <h1>{{ t('desktop.title') }}</h1>
      <p class="page-lead">{{ t('desktop.lead') }}</p>
    </header>

    <section v-if="!available" class="desktop-panel" role="alert">
      <h2>{{ t('desktop.unavailable.title') }}</h2>
      <p>{{ t('desktop.unavailable.body') }}</p>
    </section>

    <template v-else>
      <BackupPanel />
      <ImportPanel />
    </template>
  </main>
</template>

<style scoped>
.desktop-workspace { gap: 20px; }
.desktop-workspace h1 { margin: 0 0 8px; }
.desktop-panel { display: grid; gap: 8px; padding: 20px; border: 1px solid var(--border); border-radius: 16px; background: var(--bg-surface); }
.desktop-panel h2 { margin: 0; font-size: 18px; }
.desktop-panel p { margin: 0; color: var(--text-secondary); }
</style>
