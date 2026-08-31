<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertCircle, Eye, EyeOff, LockKeyhole, UserRound } from 'lucide-vue-next'
import AuthShell from '@/components/AuthShell.vue'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/i18n'

const username = ref('')
const password = ref('')
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')
const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const { t } = useLocale()
const redirect = computed(() => typeof route.query.redirect === 'string' ? route.query.redirect : '/')
const credentialChanged = computed(() => route.query.changed === '1')

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
  <AuthShell mode="login">
    <section class="auth-panel">
      <div class="auth-panel-heading">
        <span><LockKeyhole :size="20" /></span>
        <p>{{ t('auth.account') }}</p>
      </div>
      <h1>{{ t('auth.login') }}</h1>
      <p class="auth-panel-intro">{{ t('auth.loginIntro') }}</p>
      <p v-if="credentialChanged" class="auth-notice success" role="status">{{ t('auth.credentialChanged') }}</p>
      <form class="auth-form" :aria-busy="loading" @submit.prevent="submit">
        <label>
          <span>{{ t('auth.username') }}</span>
          <span class="auth-input-wrap"><UserRound :size="17" /><input v-model.trim="username" autocomplete="username" autocapitalize="none" spellcheck="false" required maxlength="128" /></span>
        </label>
        <label>
          <span>{{ t('auth.password') }}</span>
          <span class="auth-input-wrap"><LockKeyhole :size="17" /><input v-model="password" :type="showPassword ? 'text' : 'password'" autocomplete="current-password" required minlength="8" maxlength="128" /><button type="button" :aria-label="showPassword ? t('auth.hidePassword') : t('auth.showPassword')" @click="showPassword = !showPassword"><EyeOff v-if="showPassword" :size="17" /><Eye v-else :size="17" /></button></span>
        </label>
        <p v-if="error" class="form-error" role="alert"><AlertCircle :size="16" /> {{ error }}</p>
        <button class="btn-neon btn-primary" type="submit" :disabled="loading">{{ loading ? t('auth.loggingIn') : t('auth.login') }}</button>
      </form>
      <p class="auth-switch">{{ t('auth.noAccount') }} <RouterLink to="/register">{{ t('auth.register') }}</RouterLink></p>
    </section>
  </AuthShell>
</template>
