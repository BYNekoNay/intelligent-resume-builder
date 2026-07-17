<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LockKeyhole } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const redirect = computed(() => typeof route.query.redirect === 'string' ? route.query.redirect : '/')

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await auth.signIn({ username: username.value, password: password.value })
    await router.push(redirect.value)
  } catch {
    error.value = '登录失败，请检查账号和密码后重试。'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-panel">
      <LockKeyhole :size="28" />
      <p class="eyebrow">账户</p>
      <h1>登录</h1>
      <form class="auth-form" @submit.prevent="submit">
        <label>用户名<input v-model.trim="username" autocomplete="username" required maxlength="128" /></label>
        <label>密码<input v-model="password" type="password" autocomplete="current-password" required minlength="8" maxlength="128" /></label>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="btn-neon btn-primary" type="submit" :disabled="loading">{{ loading ? '正在登录…' : '登录' }}</button>
      </form>
      <p>还没有账户？<RouterLink to="/register">创建账户</RouterLink></p>
      <RouterLink to="/">返回工作台</RouterLink>
    </section>
  </main>
</template>
