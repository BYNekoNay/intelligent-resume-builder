<script setup lang="ts">
import { FilePenLine, FileText, LayoutDashboard, LogIn, LogOut, NotebookPen, Sparkles, UserRoundPlus, Activity, Send, Target } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import { useLocale } from '@/i18n'

const auth = useAuthStore()
const router = useRouter()
const { t } = useLocale()

const navigation = [
  { to: '/', key: 'home', icon: LayoutDashboard },
  { to: '/resumes', key: 'resumes', icon: NotebookPen },
  { to: '/career-materials', key: 'materials', icon: Sparkles },
  { to: '/material-generation', key: 'materialGeneration', icon: Sparkles },
  { to: '/jobs', key: 'jobs', icon: FileText },
  { to: '/ats', key: 'ats', icon: Activity },
  { to: '/applications', key: 'applications', icon: Send },
  { to: '/interviews', key: 'interviews', icon: Sparkles },
  { to: '/resume-import', key: 'imports', icon: FileText },
  { to: '/communications', key: 'communications', icon: Send },
  { to: '/interview-assets', key: 'answerAssets', icon: NotebookPen },
  { to: '/achievement-guidance', key: 'achievements', icon: Target },
]

async function signOut() {
  await auth.signOut()
  await router.push({ name: 'home' })
}
</script>

<template>
  <div class="app-shell">
    <header class="app-header">
      <RouterLink class="brand" to="/">
        <span class="brand-mark"><FilePenLine :size="20" /></span>
        <span>{{ t('brand') }}</span>
      </RouterLink>
      <nav :aria-label="t('navigation.label')">
        <RouterLink v-for="item in navigation" :key="item.key" :to="item.to" class="nav-link">
          <component :is="item.icon" :size="16" />{{ t(`navigation.${item.key}`) }}
        </RouterLink>
      </nav>
      <div v-if="auth.accessToken" class="header-actions">
        <RouterLink class="icon-action" to="/ai-consent" :aria-label="t('actions.aiConsent')" :title="t('actions.aiConsent')"><Sparkles :size="18" /></RouterLink>
        <button class="icon-action" :aria-label="t('actions.signOut')" :title="t('actions.signOut')" @click="signOut"><LogOut :size="18" /></button>
      </div>
      <div v-else class="header-actions">
        <RouterLink class="icon-action" to="/login" :aria-label="t('actions.signIn')" :title="t('actions.signIn')"><LogIn :size="18" /></RouterLink>
        <RouterLink class="icon-action" to="/register" :aria-label="t('actions.signUp')" :title="t('actions.signUp')"><UserRoundPlus :size="18" /></RouterLink>
      </div>
      <LanguageSwitcher />
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
