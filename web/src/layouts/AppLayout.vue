<script setup lang="ts">
import { FilePenLine, FileText, LayoutDashboard, LogIn, LogOut, NotebookPen, Sparkles, UserRoundPlus, Activity, Send, Target } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const router = useRouter()

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
        <span>智历</span>
      </RouterLink>
      <nav aria-label="主导航">
        <RouterLink to="/" class="nav-link"><LayoutDashboard :size="16" />工作台</RouterLink>
        <RouterLink to="/resumes" class="nav-link"><NotebookPen :size="16" />简历</RouterLink>
        <RouterLink to="/career-materials" class="nav-link"><Sparkles :size="16" />资料库</RouterLink>
        <RouterLink to="/material-generation" class="nav-link"><Sparkles :size="16" />素材生成</RouterLink>
        <RouterLink to="/jobs" class="nav-link"><FileText :size="16" />JD</RouterLink>
        <RouterLink to="/ats" class="nav-link"><Activity :size="16" />体检</RouterLink>
        <RouterLink to="/applications" class="nav-link"><Send :size="16" />投递</RouterLink>
        <RouterLink to="/interviews" class="nav-link"><Sparkles :size="16" />面试</RouterLink>
        <RouterLink to="/resume-import" class="nav-link"><FileText :size="16" />导入</RouterLink>
        <RouterLink to="/communications" class="nav-link"><Send :size="16" />文案</RouterLink>
        <RouterLink to="/interview-assets" class="nav-link"><NotebookPen :size="16" />答案库</RouterLink>
        <RouterLink to="/achievement-guidance" class="nav-link"><Target :size="16" />成就引导</RouterLink>
      </nav>
      <div v-if="auth.accessToken" class="header-actions">
        <RouterLink class="icon-action" to="/ai-consent" aria-label="AI 数据处理同意" title="AI 数据处理同意"><Sparkles :size="18" /></RouterLink>
        <button class="icon-action" aria-label="退出登录" title="退出登录" @click="signOut"><LogOut :size="18" /></button>
      </div>
      <div v-else class="header-actions">
        <RouterLink class="icon-action" to="/login" aria-label="登录" title="登录"><LogIn :size="18" /></RouterLink>
        <RouterLink class="icon-action" to="/register" aria-label="注册" title="注册"><UserRoundPlus :size="18" /></RouterLink>
      </div>
    </header>
    <main class="app-main">
      <RouterView />
    </main>
  </div>
</template>
