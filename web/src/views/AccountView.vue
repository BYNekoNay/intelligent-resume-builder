<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Download, IdCard, KeyRound, Mail, ShieldAlert, ShieldCheck, Trash2, UserRound, X } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { changeEmail, changePassword, deleteAccount, exportAccountData } from '@/api/auth'
import { clearAiTaskHistory } from '@/api/ai'
import { useLocale } from '@/i18n'
import { resolveApiError } from '@/utils/errorMessage'
import { clearUserLocalData } from '@/utils/localUserData'

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
/** #26：清空 AI 任务历史的进行态与结果提示。 */
const clearingHistory = ref(false)
const historyMessage = ref('')
/** #7：数据导出与删号。 */
const exporting = ref(false)
const exportMessage = ref('')
const deleteDialogOpen = ref(false)
const deleteConfirmName = ref('')
const deleting = ref(false)
const deleteMessage = ref('')
const deleteConfirmInput = ref<HTMLInputElement | null>(null)
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
/** #7：删号二次确认——输入的用户名必须与当前账号一致。 */
const deleteConfirmMatched = computed(() => deleteConfirmName.value.trim() === (user.value?.username ?? ''))
const deleteDialogDescription = computed(() => t('account.deleteDialogDescription').replace('{username}', user.value?.username ?? ''))

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
  } catch (error) {
    credentialMessage.value = resolveApiError(error, 'account.credentialError')
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

/** #26：清空本人已完成的 AI 任务历史（进行中与待确认任务由服务端保留）。 */
async function clearAiHistory() {
  if (!window.confirm(t('account.clearAiHistoryConfirm'))) return
  clearingHistory.value = true
  historyMessage.value = ''
  try {
    const response = await clearAiTaskHistory()
    historyMessage.value = t('account.clearAiHistoryDone').replace('{count}', String(response.data.data ?? 0))
  } catch (error) {
    historyMessage.value = resolveApiError(error, 'account.clearAiHistoryError')
  } finally {
    clearingHistory.value = false
  }
}

/** #7：导出个人数据——服务端返回 JSON 文本，前端落成 .json 文件下载。 */
async function exportData() {
  exporting.value = true
  exportMessage.value = ''
  try {
    const json = await exportAccountData()
    const blob = new Blob([json], { type: 'application/json' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `intelligent-resume-export-${new Date().toISOString().slice(0, 10)}.json`
    document.body.appendChild(link)
    link.click()
    link.remove()
    URL.revokeObjectURL(url)
    exportMessage.value = t('account.exportDone')
  } catch (error) {
    exportMessage.value = resolveApiError(error, 'account.exportError')
  } finally {
    exporting.value = false
  }
}

async function openDeleteDialog() {
  deleteMessage.value = ''
  deleteConfirmName.value = ''
  deleteDialogOpen.value = true
  await nextTick()
  deleteConfirmInput.value?.focus()
}

async function closeDeleteDialog() {
  deleteDialogOpen.value = false
  deleteMessage.value = ''
  deleteConfirmName.value = ''
}

/** #7：删号——用户名二次确认后调 DELETE /api/auth/me；服务端已撤销全部会话并作废 refresh cookie，本地直接清会话跳登录。 */
async function confirmDeleteAccount() {
  if (!deleteConfirmMatched.value) return
  deleting.value = true
  deleteMessage.value = ''
  try {
    await deleteAccount()
    // 删号进入 7 天撤销窗口（D2 阶段 3）：服务端保留数据待恢复或清扫，但账号已停用、会话已全撤；
    // 浏览器本地同样不能留（完整简历草稿、导入原文、文案草稿等），否则共享设备上
    // 仍可读到该账号的 PII（docs/08 §9.6 口径）。窗口内恢复后这些本地数据不会自动回填。
    clearUserLocalData(auth.currentUser?.id)
    auth.setAccessToken(null)
    await router.replace({ name: 'login' })
  } catch (error) {
    deleteMessage.value = resolveApiError(error, 'account.deleteError')
  } finally {
    deleting.value = false
  }
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
      <div>
        <h2>{{ t('account.privacyTitle') }}</h2>
        <p>{{ t('account.privacyDescription') }}</p>
        <p class="consent-history-action">
          <button class="btn-neon btn-ghost" type="button" :disabled="clearingHistory" @click="clearAiHistory">
            <Trash2 :size="15" /> {{ clearingHistory ? t('account.clearingAiHistory') : t('account.clearAiHistory') }}
          </button>
          <span v-if="historyMessage" role="status">{{ historyMessage }}</span>
        </p>
      </div>
      <RouterLink class="btn-neon btn-ghost" to="/ai-consent"><ShieldCheck :size="16" /> {{ t('account.manageAiConsent') }}</RouterLink>
    </article>

    <article class="account-consent-band account-data-band">
      <span><ShieldAlert :size="20" /></span>
      <div>
        <h2>{{ t('account.dataTitle') }}</h2>
        <p>{{ t('account.dataDescription') }}</p>
        <p class="consent-history-action">
          <button class="btn-neon btn-ghost" type="button" :disabled="exporting" @click="exportData">
            <Download :size="15" /> {{ exporting ? t('account.exporting') : t('account.exportData') }}
          </button>
          <button class="btn-neon btn-ghost danger-action" type="button" @click="openDeleteDialog">
            <Trash2 :size="15" /> {{ t('account.deleteAccount') }}
          </button>
          <span v-if="exportMessage" role="status">{{ exportMessage }}</span>
        </p>
      </div>
    </article>

    <Teleport to="body">
      <div v-if="deleteDialogOpen" class="account-dialog-overlay" @click.self="closeDeleteDialog">
        <section class="account-dialog" role="dialog" aria-modal="true" aria-labelledby="account-delete-title" @keyup.esc="closeDeleteDialog">
          <header>
            <div>
              <p class="eyebrow">{{ t('account.dataTitle') }}</p>
              <h2 id="account-delete-title">{{ t('account.deleteAccount') }}</h2>
              <p>{{ deleteDialogDescription }}</p>
            </div>
            <button class="icon-button" type="button" :aria-label="t('common.close')" @click="closeDeleteDialog"><X :size="18" /></button>
          </header>
          <form @submit.prevent="confirmDeleteAccount">
            <label>{{ t('account.deleteConfirmLabel') }}<input ref="deleteConfirmInput" v-model="deleteConfirmName" autocomplete="off" required /></label>
            <p v-if="deleteMessage" class="form-error" role="alert">{{ deleteMessage }}</p>
            <footer>
              <button class="btn-neon btn-ghost" type="button" @click="closeDeleteDialog">{{ t('common.cancel') }}</button>
              <button class="btn-neon btn-ghost danger-action" :disabled="deleting || !deleteConfirmMatched">{{ deleting ? t('account.deleting') : t('account.deleteConfirmAction') }}</button>
            </footer>
          </form>
        </section>
      </div>
    </Teleport>
  </section>
</template>
