<script setup lang="ts">
import { computed } from 'vue'
import { FilePenLine, LayoutDashboard, Sparkles, FileText, NotebookPen, UserRoundPlus, LogIn, LogOut, Send, Activity, Target } from 'lucide-vue-next'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import NavDropdown from '@/components/NavDropdown.vue'
import { useLocale } from '@/i18n'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { t } = useLocale()

interface NavItem { to: string; key: string; icon: any }

type GroupKey = 'accumulate' | 'refine' | 'match' | 'deliver'

const groups: Record<GroupKey, NavItem[]> = {
  accumulate: [
    { to: '/', key: 'home', icon: LayoutDashboard },
    { to: '/career-materials', key: 'materials', icon: Sparkles },
    { to: '/resumes', key: 'resumes', icon: NotebookPen },
    { to: '/resume-import', key: 'imports', icon: FileText },
  ],
  refine: [
    { to: '/generate', key: 'generate', icon: Sparkles },
    { to: '/material-generation', key: 'materialGeneration', icon: Sparkles },
    { to: '/achievement-guidance', key: 'achievements', icon: Target },
    { to: '/communications', key: 'communications', icon: Send },
  ],
  match: [
    { to: '/jobs', key: 'jobs', icon: FileText },
    { to: '/ats', key: 'ats', icon: Activity },
    { to: '/interviews', key: 'interviews', icon: Sparkles },
    { to: '/interview-assets', key: 'answerAssets', icon: NotebookPen },
  ],
  deliver: [
    { to: '/applications', key: 'applications', icon: Send },
  ],
}

const routeToGroup = computed<Record<string, GroupKey>>(() => {
  const map: Record<string, GroupKey> = {}
  for (const [group, items] of Object.entries(groups)) {
    for (const item of items) map[item.to] = group as GroupKey
  }
  return map
})

const activeGroup = computed<GroupKey | null>(() => routeToGroup.value[route.path] ?? null)

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
        <NavDropdown
          v-for="(groupKey, idx) in (Object.keys(groups) as GroupKey[])"
          :key="groupKey"
          :label="t(`navGroups.${groupKey}.label`)"
          :active="activeGroup === groupKey"
        >
          <RouterLink
            v-for="item in groups[groupKey]"
            :key="item.key"
            :to="item.to"
            @click.stop
          >
            <component :is="item.icon" :size="15" />
            {{ t(`navGroups.${groupKey}.${item.key}`) ?? t(`navigation.${item.key}`) }}
          </RouterLink>
        </NavDropdown>
      </nav>

      <div v-if="auth.accessToken" class="header-actions">
        <RouterLink class="icon-action" to="/ai-consent" :aria-label="t('actions.aiConsent')" :title="t('actions.aiConsent')">
          <Sparkles :size="18" />
        </RouterLink>
        <button class="icon-action" :aria-label="t('actions.signOut')" :title="t('actions.signOut')" @click="signOut">
          <LogOut :size="18" />
        </button>
      </div>
      <div v-else class="header-actions">
        <RouterLink class="icon-action" to="/login" :aria-label="t('actions.signIn')" :title="t('actions.signIn')">
          <LogIn :size="18" />
        </RouterLink>
        <RouterLink class="icon-action" to="/register" :aria-label="t('actions.signUp')" :title="t('actions.signUp')">
          <UserRoundPlus :size="18" />
        </RouterLink>
      </div>
      <LanguageSwitcher />
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
