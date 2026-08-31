<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { IdCard, KeyRound, Mail, ShieldCheck, UserRound, X } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { changeEmail, changePassword } from '@/api/auth'
import { useLocale } from '@/i18n'

const auth = useAuthStore()
const { t } = useLocale()
const router = useRouter()
const displayName = ref(auth.currentUser?.displayName ?? auth.currentUser?.username ?? '')
const saving = ref(false)
const message = ref('')
const email = ref(auth.currentUser?.email ?? '')
const emailPassword = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const credentialMessage = ref('')
const changingCredential = ref(false)
const activeCredentialPanel = ref<'email' | 'password' | null>(null)
const emailChangeButton = ref<HTMLButtonElement | null>(null)
const passwordChangeButton = ref<HTMLButtonElement | null>(null)
const emailInput = ref<HTMLInputElement | null>(null)
const passwordInput = ref<HTMLInputElement | null>(null)

watch(() => auth.currentUser, (user) => {
  if (user) displayName.value = user.displayName ?? user.username
  if (user) email.value = user.email
}, { immediate: true })

const user = computed(() => auth.currentUser)
const userInitial = computed(() => (user.value?.displayName ?? user.value?.username ?? '?').trim().slice(0, 1).toUpperCase())
const maskedEmail = computed(() => {
  const value = user.value?.email ?? ''
  const at = value.indexOf('@')
  if (at < 1) return value || '-'
  const local = value.slice(0, at)
  const visible = local.slice(0, Math.min(2, local.length))
  return `${visible}${'*'.repeat(Math.max(3, local.length - visible.length))}${value.slice(at)}`
})

async function save() {
  if (!displayName.value.trim()) return
  saving.value = true
  message.value = ''
  try {
    await auth.updateCurrentUser(displayName.value.trim())
    message.value = t('account.saveSuccess')
  } catch {
    message.value = t('account.saveError')
  } finally {
    saving.value = false
  }
}

async function finishCredentialChange(action: () => Promise<unknown>) {
  changingCredential.value = true
  credentialMessage.value = ''
  try {
    await action()
    await auth.signOut()
    await router.replace({ name: 'login', query: { changed: '1' } })
  } catch (error: any) {
    credentialMessage.value = error?.response?.data?.message || t('account.credentialError')
  } finally {
    changingCredential.value = false
  }
}

function saveEmail() {
  void finishCredentialChange(() => changeEmail({ email: email.value.trim(), currentPassword: emailPassword.value }))
}

function savePassword() {
  if (newPassword.value !== confirmPassword.value) {
    credentialMessage.value = t('account.passwordMismatch')
    return
  }
  void finishCredentialChange(() => changePassword({ currentPassword: currentPassword.value, newPassword: newPassword.value }))
}

async function openCredentialPanel(panel: 'email' | 'password') {
  credentialMessage.value = ''
  activeCredentialPanel.value = panel
  await nextTick()
  ;(panel === 'email' ? emailInput.value : passwordInput.value)?.focus()
}

async function closeCredentialPanel() {
  const panel = activeCredentialPanel.value
  activeCredentialPanel.value = null
  credentialMessage.value = ''
  emailPassword.value = ''
  currentPassword.value = ''
  newPassword.value = ''
  confirmPassword.value = ''
  await nextTick()
  ;(panel === 'email' ? emailChangeButton.value : passwordChangeButton.value)?.focus()
}
</script>

<template>
  <section class="workspace-page account-page">
    <header class="account-page-heading">
      <div>
        <p class="eyebrow"><UserRound :size="14" /> {{ t('account.eyebrow') }}</p>
        <h1>{{ t('account.title') }}</h1>
        <p class="page-lead">{{ t('account.subtitle') }}</p>
      </div>
      <div class="account-identity">
        <span>{{ userInitial }}</span>
        <div><strong>{{ user?.displayName || user?.username || '-' }}</strong><small>@{{ user?.username ?? '-' }}</small></div>
      </div>
    </header>

    <div class="account-grid">
    <article class="account-panel account-profile-panel">
      <header><span><IdCard :size="18" /></span><div><h2>{{ t('account.profileTitle') }}</h2><p>{{ t('account.profileDescription') }}</p></div></header>
      <form @submit.prevent="save">
        <label>{{ t('account.displayName') }}<input v-model="displayName" :maxlength="128" required /></label>
        <div class="account-readonly"><span>{{ t('account.username') }}</span><strong>{{ user?.username ?? '-' }}</strong></div>
        <div class="account-readonly"><span>{{ t('account.email') }}</span><strong>{{ user?.email ?? '-' }}</strong></div>
        <p v-if="message" class="status-line" :class="{ success: message === t('account.saveSuccess') }" role="status">{{ message }}</p>
        <button class="btn-neon btn-primary" :disabled="saving || !displayName.trim()">{{ saving ? t('common.saving') : t('account.save') }}</button>
      </form>
    </article>

    <article class="account-panel account-security-panel">
      <header><span><ShieldCheck :size="18" /></span><div><h2>{{ t('account.securityTitle') }}</h2><p>{{ t('account.securityDescription') }}</p></div></header>
      <div class="account-security-list">
        <section class="account-security-item">
          <div class="account-security-icon" aria-hidden="true"><Mail :size="18" /></div>
          <div><h3>{{ t('account.email') }}</h3><strong>{{ maskedEmail }}</strong><p>{{ t('account.emailHint') }}</p></div>
          <button ref="emailChangeButton" class="btn-neon btn-ghost" type="button" @click="openCredentialPanel('email')">{{ t('account.changeEmail') }}</button>
        </section>
        <section class="account-security-item">
          <div class="account-security-icon" aria-hidden="true"><KeyRound :size="18" /></div>
          <div><h3>{{ t('account.password') }}</h3><strong>{{ t('account.passwordProtected') }}</strong><p>{{ t('account.passwordHint') }}</p></div>
          <button ref="passwordChangeButton" class="btn-neon btn-ghost" type="button" @click="openCredentialPanel('password')">{{ t('account.changePassword') }}</button>
        </section>
      </div>
      <small>{{ t('account.securityNotice') }}</small>
    </article>
    </div>

    <Teleport to="body">
      <div v-if="activeCredentialPanel" class="account-dialog-overlay" @click.self="closeCredentialPanel">
        <section class="account-dialog" role="dialog" aria-modal="true" :aria-labelledby="`account-${activeCredentialPanel}-title`" @keyup.esc="closeCredentialPanel">
          <header>
            <div>
              <p class="eyebrow">{{ t('account.securityTitle') }}</p>
              <h2 :id="`account-${activeCredentialPanel}-title`">{{ activeCredentialPanel === 'email' ? t('account.changeEmail') : t('account.changePassword') }}</h2>
              <p>{{ activeCredentialPanel === 'email' ? t('account.emailDialogDescription') : t('account.passwordDialogDescription') }}</p>
            </div>
            <button class="icon-button" type="button" :aria-label="t('common.close')" @click="closeCredentialPanel"><X :size="18" /></button>
          </header>
          <form v-if="activeCredentialPanel === 'email'" @submit.prevent="saveEmail">
            <label>{{ t('account.newEmail') }}<input ref="emailInput" v-model="email" type="email" autocomplete="email" required /></label>
            <label>{{ t('account.currentPassword') }}<input v-model="emailPassword" type="password" autocomplete="current-password" required /></label>
            <p v-if="credentialMessage" class="form-error" role="alert">{{ credentialMessage }}</p>
            <footer><button class="btn-neon btn-ghost" type="button" @click="closeCredentialPanel">{{ t('common.cancel') }}</button><button class="btn-neon btn-primary" :disabled="changingCredential">{{ changingCredential ? t('common.saving') : t('account.saveEmail') }}</button></footer>
          </form>
          <form v-else @submit.prevent="savePassword">
            <label>{{ t('account.currentPassword') }}<input ref="passwordInput" v-model="currentPassword" type="password" autocomplete="current-password" required /></label>
            <label>{{ t('account.newPassword') }}<input v-model="newPassword" type="password" minlength="8" autocomplete="new-password" required /></label>
            <label>{{ t('account.confirmPassword') }}<input v-model="confirmPassword" type="password" minlength="8" autocomplete="new-password" required /></label>
            <p v-if="credentialMessage" class="form-error" role="alert">{{ credentialMessage }}</p>
            <footer><button class="btn-neon btn-ghost" type="button" @click="closeCredentialPanel">{{ t('common.cancel') }}</button><button class="btn-neon btn-primary" :disabled="changingCredential">{{ changingCredential ? t('common.saving') : t('account.savePassword') }}</button></footer>
          </form>
        </section>
      </div>
    </Teleport>

    <article class="account-consent-band">
      <span><ShieldCheck :size="20" /></span>
      <div><h2>{{ t('account.privacyTitle') }}</h2><p>{{ t('account.privacyDescription') }}</p></div>
      <RouterLink class="btn-neon btn-ghost" to="/ai-consent"><ShieldCheck :size="16" /> {{ t('account.manageAiConsent') }}</RouterLink>
    </article>
  </section>
</template>
