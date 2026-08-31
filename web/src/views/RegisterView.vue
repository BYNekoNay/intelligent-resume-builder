<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertCircle, Eye, EyeOff, LockKeyhole, Mail, UserRound, UserRoundPlus } from 'lucide-vue-next'
import AuthShell from '@/components/AuthShell.vue'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/i18n'

const username = ref('')
const email = ref('')
const password = ref('')
const showPassword = ref(false)
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
  <AuthShell mode="register">
    <section class="auth-panel">
      <div class="auth-panel-heading">
        <span><UserRoundPlus :size="20" /></span>
        <p>{{ t('auth.account') }}</p>
      </div>
      <h1>{{ t('auth.register') }}</h1>
      <p class="auth-panel-intro">{{ t('auth.registerIntro') }}</p>
      <form class="auth-form" :aria-busy="loading" @submit.prevent="submit">
        <label>
          <span>{{ t('auth.username') }}</span>
          <span class="auth-input-wrap"><UserRound :size="17" /><input v-model.trim="username" autocomplete="username" autocapitalize="none" spellcheck="false" required minlength="3" maxlength="64" pattern="[a-zA-Z0-9_.-]+" /></span>
          <small>{{ t('auth.usernameHint') }}</small>
        </label>
        <label>
          <span>{{ t('auth.email') }}</span>
          <span class="auth-input-wrap"><Mail :size="17" /><input v-model.trim="email" type="email" autocomplete="email" required maxlength="128" /></span>
        </label>
        <label>
          <span>{{ t('auth.password') }}</span>
          <span class="auth-input-wrap"><LockKeyhole :size="17" /><input v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="new-password" required minlength="8" maxlength="128" /><button type="button" :aria-label="showPassword ? t('auth.hidePassword') : t('auth.showPassword')" @click="showPassword = !showPassword"><EyeOff v-if="showPassword" :size="17" /><Eye v-else :size="17" /></button></span>
          <small>{{ t('auth.passwordHint') }}</small>
        </label>
        <p v-if="error" class="form-error" role="alert"><AlertCircle :size="16" /> {{ error }}</p>
        <button class="btn-neon btn-primary" type="submit" :disabled="loading">{{ loading ? t('auth.registering') : t('auth.register') }}</button>
      </form>
      <p class="auth-switch">{{ t('auth.hasAccount') }} <RouterLink to="/login">{{ t('auth.login') }}</RouterLink></p>
    </section>
  </AuthShell>
</template>
