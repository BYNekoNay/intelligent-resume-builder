<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { LockKeyhole } from 'lucide-vue-next'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/i18n'

const username = ref('')
const password = ref('')
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { t } = useLocale()
const redirect = computed(() => typeof route.query.redirect === 'string' ? route.query.redirect : '/')

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await auth.signIn({ username: username.value, password: password.value })
    await router.push(redirect.value)
  } catch {
    error.value = t('auth.loginError')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="auth-page">
    <section class="auth-panel">
      <LockKeyhole :size="28" />
      <p class="eyebrow">{{ t('auth.account') }}</p>
      <h1>{{ t('auth.login') }}</h1>
      <form class="auth-form" @submit.prevent="submit">
        <label>{{ t('auth.username') }}<input v-model.trim="username" autocomplete="username" required maxlength="128" /></label>
        <label>{{ t('auth.password') }}<input v-model="password" type="password" autocomplete="current-password" required minlength="8" maxlength="128" /></label>
        <p v-if="error" class="form-error" role="alert">{{ error }}</p>
        <button class="btn-neon btn-primary" type="submit" :disabled="loading">{{ loading ? t('auth.loggingIn') : t('auth.login') }}</button>
      </form>
      <p>{{ t('auth.noAccount') }}<RouterLink to="/register">{{ t('auth.register') }}</RouterLink></p>
      <RouterLink to="/">{{ t('auth.returnHome') }}</RouterLink>
    </section>
  </main>
</template>
