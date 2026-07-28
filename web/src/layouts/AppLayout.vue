<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Activity, FilePenLine, FileText, LayoutDashboard, LogIn, LogOut, Menu, NotebookPen, Send, Sparkles, Target, UserRound, UserRoundPlus, X } from 'lucide-vue-next'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import LanguageSwitcher from '@/components/LanguageSwitcher.vue'
import NavDropdown from '@/components/NavDropdown.vue'
import { useLocale } from '@/i18n'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { t } = useLocale()
const mobileMenuOpen = ref(false)

interface NavItem { to: string; key: string; icon: any }

type GroupKey = 'career' | 'resume' | 'prepare' | 'applications'

const groups: Record<GroupKey, NavItem[]> = {
  career: [
    { to: '/', key: 'home', icon: LayoutDashboard },
    { to: '/career-materials', key: 'materials', icon: Sparkles },
    { to: '/resume-import', key: 'imports', icon: FileText },
  ],
  resume: [
    { to: '/resumes', key: 'resumes', icon: NotebookPen },
    { to: '/generate', key: 'generate', icon: Sparkles },
    { to: '/material-generation', key: 'materialGeneration', icon: Sparkles },
    { to: '/achievement-guidance', key: 'achievements', icon: Target },
  ],
  prepare: [
    { to: '/jobs', key: 'jobs', icon: FileText },
    { to: '/ats', key: 'ats', icon: Activity },
    { to: '/interviews', key: 'interviews', icon: Sparkles },
    { to: '/interview-assets', key: 'answerAssets', icon: NotebookPen },
  ],
  applications: [
    { to: '/communications', key: 'communications', icon: Send },
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

watch(() => route.fullPath, () => { mobileMenuOpen.value = false })

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
          >
            <component :is="item.icon" :size="15" />
            {{ t(`navGroups.${groupKey}.${item.key}`) ?? t(`navigation.${item.key}`) }}
          </RouterLink>
        </NavDropdown>
      </nav>

      <div v-if="auth.accessToken" class="header-actions">
        <RouterLink class="header-user" to="/account" :aria-label="t('account.title')" :title="t('account.title')">
          <UserRound :size="18" />
          <span>{{ auth.currentUser?.displayName || auth.currentUser?.username || t('account.eyebrow') }}</span>
        </RouterLink>
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
      <button
        class="mobile-menu-toggle"
        type="button"
        :aria-expanded="mobileMenuOpen"
        :aria-label="mobileMenuOpen ? t('navigation.closeMenu') : t('navigation.openMenu')"
        aria-controls="mobile-navigation"
        @click="mobileMenuOpen = !mobileMenuOpen"
      >
        <X v-if="mobileMenuOpen" :size="20" />
        <Menu v-else :size="20" />
      </button>
    </header>

    <div v-if="mobileMenuOpen" id="mobile-navigation" class="mobile-navigation-panel">
      <nav :aria-label="t('navigation.label')">
        <section v-for="groupKey in (Object.keys(groups) as GroupKey[])" :key="groupKey">
          <p>{{ t(`navGroups.${groupKey}.label`) }}</p>
          <RouterLink v-for="item in groups[groupKey]" :key="item.key" :to="item.to">
            <component :is="item.icon" :size="16" />
            <span>{{ t(`navGroups.${groupKey}.${item.key}`) ?? t(`navigation.${item.key}`) }}</span>
          </RouterLink>
        </section>
      </nav>

      <div class="mobile-account-actions">
        <template v-if="auth.accessToken">
          <RouterLink to="/account"><UserRound :size="17" /> {{ t('account.title') }}</RouterLink>
          <RouterLink to="/ai-consent"><Sparkles :size="17" /> {{ t('actions.aiConsent') }}</RouterLink>
          <button type="button" @click="signOut"><LogOut :size="17" /> {{ t('actions.signOut') }}</button>
        </template>
        <template v-else>
          <RouterLink to="/login"><LogIn :size="17" /> {{ t('actions.signIn') }}</RouterLink>
          <RouterLink to="/register"><UserRoundPlus :size="17" /> {{ t('actions.signUp') }}</RouterLink>
        </template>
      </div>
    </div>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
