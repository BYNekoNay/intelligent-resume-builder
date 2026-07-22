<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { UserRoundPlus } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/i18n'

const username = ref('')
const email = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()
const { t } = useLocale()

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await auth.signUp({ username: username.value, email: email.value, password: password.value })
    await router.push({ name: 'career-materials' })
  } catch {
    error.value = t('auth.registerError')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-panel">
      <UserRoundPlus :size="28" />
      <p class="eyebrow">{{ t('auth.account') }}</p>
      <h1>{{ t('auth.register') }}</h1>
      <form class="auth-form" @submit.prevent="submit">
        <label>{{ t('auth.username') }}<input v-model.trim="username" autocomplete="username" required minlength="3" maxlength="64" pattern="[a-zA-Z0-9_.-]+" /></label>
        <label>{{ t('auth.email') }}<input v-model.trim="email" type="email" autocomplete="email" required maxlength="128" /></label>
        <label>{{ t('auth.password') }}<input v-model="password" type="password" autocomplete="new-password" required minlength="8" maxlength="128" /></label>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="btn-neon btn-primary" type="submit" :disabled="loading">{{ loading ? t('auth.registering') : t('auth.register') }}</button>
      </form>
      <p>{{ t('auth.hasAccount') }}<RouterLink to="/login">{{ t('auth.login') }}</RouterLink></p>
      <RouterLink to="/">{{ t('auth.returnHome') }}</RouterLink>
    </section>
  </main>
</template>
