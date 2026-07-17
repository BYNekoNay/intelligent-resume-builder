<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { UserRoundPlus } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'

const username = ref('')
const email = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await auth.signUp({ username: username.value, email: email.value, password: password.value })
    await router.push({ name: 'career-materials' })
  } catch {
    error.value = '注册失败。用户名或邮箱可能已被使用，请检查后重试。'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-panel">
      <UserRoundPlus :size="28" />
      <p class="eyebrow">账户</p>
      <h1>创建账户</h1>
      <form class="auth-form" @submit.prevent="submit">
        <label>用户名<input v-model.trim="username" autocomplete="username" required minlength="3" maxlength="64" pattern="[a-zA-Z0-9_.-]+" /></label>
        <label>邮箱<input v-model.trim="email" type="email" autocomplete="email" required maxlength="128" /></label>
        <label>密码<input v-model="password" type="password" autocomplete="new-password" required minlength="8" maxlength="128" /></label>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="btn-neon btn-primary" type="submit" :disabled="loading">{{ loading ? '正在创建…' : '创建账户' }}</button>
      </form>
      <p>已有账户？<RouterLink to="/login">登录</RouterLink></p>
      <RouterLink to="/">返回工作台</RouterLink>
    </section>
  </main>
</template>
