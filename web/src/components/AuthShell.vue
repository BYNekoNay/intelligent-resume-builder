<script setup lang="ts">
import { ArrowLeft, FilePenLine, FolderKanban, Send, Sparkles } from 'lucide-vue-next'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import { useLocale } from '@/i18n'

defineProps<{ mode: 'login' | 'register' }>()

const { t } = useLocale()

const stages = [
  { icon: FolderKanban, titleKey: 'auth.stageEvidence', descKey: 'auth.stageEvidenceDesc' },
  { icon: Sparkles, titleKey: 'auth.stageTailor', descKey: 'auth.stageTailorDesc' },
  { icon: Send, titleKey: 'auth.stageApply', descKey: 'auth.stageApplyDesc' },
]
</script>

<template>
  <main class="auth-shell">
    <aside class="auth-context">
      <RouterLink class="auth-brand" to="/">
        <span><FilePenLine :size="20" /></span>
        <strong>{{ t('brand') }}</strong>
      </RouterLink>

      <div class="auth-context-copy">
        <p>{{ t(mode === 'login' ? 'auth.loginEyebrow' : 'auth.registerEyebrow') }}</p>
        <h2>{{ t(mode === 'login' ? 'auth.loginContextTitle' : 'auth.registerContextTitle') }}</h2>
        <span>{{ t(mode === 'login' ? 'auth.loginContextDesc' : 'auth.registerContextDesc') }}</span>
      </div>

      <ol class="auth-stage-rail" :aria-label="t('auth.stageLabel')">
        <li v-for="stage in stages" :key="stage.titleKey">
          <span><component :is="stage.icon" :size="16" /></span>
          <div>
            <strong>{{ t(stage.titleKey) }}</strong>
            <small>{{ t(stage.descKey) }}</small>
          </div>
        </li>
      </ol>

      <p class="auth-context-foot">{{ t('auth.contextFoot') }}</p>
    </aside>

    <section class="auth-surface">
      <header>
        <RouterLink to="/"><ArrowLeft :size="16" /> {{ t('auth.returnHome') }}</RouterLink>
        <LanguageSwitcher />
      </header>
      <div class="auth-content"><slot /></div>
      <footer>{{ t('auth.secureSession') }}</footer>
    </section>
  </main>
</template>
