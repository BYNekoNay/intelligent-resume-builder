<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { DatabaseBackup, RotateCcw } from 'lucide-vue-next'
import { useLocale } from '@/i18n'
import { resolveApiError } from '@/utils/errorMessage'
import {
  createDesktopBackup,
  listDesktopBackups,
  restoreDesktopBackup,
  type DesktopBackupInfo,
  type DesktopRestoreResult,
} from '@/api/desktop'

const { locale, t } = useLocale()

const backups = ref<DesktopBackupInfo[]>([])
const loading = ref(false)
const creating = ref(false)
const restoringId = ref<string | null>(null)
const error = ref('')
const notice = ref('')
const pendingRestore = ref<DesktopBackupInfo | null>(null)

onMounted(() => { void load() })

async function load() {
  loading.value = true
  error.value = ''
  try {
    const response = await listDesktopBackups()
    backups.value = response.data.data ?? []
  } catch (cause) {
    error.value = resolveApiError(cause, 'desktop.backup.failed')
  } finally {
    loading.value = false
  }
}

async function createBackup() {
  creating.value = true
  error.value = ''
  notice.value = ''
  try {
    await createDesktopBackup()
    await load()
  } catch (cause) {
    error.value = resolveApiError(cause, 'desktop.backup.createFailed')
  } finally {
    creating.value = false
  }
}

function askRestore(backup: DesktopBackupInfo) {
  error.value = ''
  notice.value = ''
  pendingRestore.value = backup
}

function cancelRestore() {
  pendingRestore.value = null
}

async function confirmRestore() {
  const target = pendingRestore.value
  if (!target) return
  restoringId.value = target.id
  error.value = ''
  try {
    const response = await restoreDesktopBackup(target.id)
    const result = response.data.data
    if (result && !result.ok) {
      error.value = restoreFailureText(result)
      return
    }
    notice.value = t('desktop.backup.restoreDone')
    pendingRestore.value = null
    await load()
  } catch (cause) {
    error.value = restoreFailureText(restoreFailureOf(cause), cause)
  } finally {
    restoringId.value = null
  }
}

/**
 * 恢复失败时 HTTP 是 409，但信封 `code` 仍是 0 —— 真实结论在 `data.ok`。
 * 服务端的 `message` 是中文，只在中文界面下透出（英文界面用统一文案，避免混语言）。
 */
function restoreFailureText(result: DesktopRestoreResult | null, cause?: unknown): string {
  if (locale.value === 'zh-CN' && result?.message) return result.message
  return resolveApiError(cause, 'desktop.backup.restoreFailed')
}

function restoreFailureOf(cause: unknown): DesktopRestoreResult | null {
  const payload = (cause as { response?: { data?: { data?: DesktopRestoreResult } } })?.response?.data?.data
  return payload ?? null
}

function reloadWorkspace() {
  window.location.reload()
}

function formatSize(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
  if (bytes >= 1024) return `${Math.round(bytes / 1024)} KB`
  return `${bytes} B`
}

/** 后端给的是 `yyyyMMdd-HHmmss`（不是 ISO），这里只做可读拆分。 */
function formatCreatedAt(value: string): string {
  const match = /^(\d{4})(\d{2})(\d{2})-(\d{2})(\d{2})(\d{2})$/.exec(value)
  if (!match) return value
  const [, year, month, day, hour, minute, second] = match
  return `${year}-${month}-${day} ${hour}:${minute}:${second}`
}
</script>

<template>
  <section class="desktop-panel">
    <header class="desktop-panel-head">
      <div>
        <h2>{{ t('desktop.backup.title') }}</h2>
        <p>{{ t('desktop.backup.lead') }}</p>
      </div>
      <div class="desktop-panel-actions">
        <button type="button" class="btn-neon btn-ghost" :disabled="loading" @click="load">
          {{ t('desktop.backup.reload') }}
        </button>
        <button type="button" class="btn-neon btn-primary" :disabled="creating" @click="createBackup">
          <DatabaseBackup :size="16" />
          {{ creating ? t('desktop.backup.creating') : t('desktop.backup.create') }}
        </button>
      </div>
    </header>

    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="notice" class="status-line" role="status">
      {{ notice }}
      <button type="button" class="btn-neon btn-secondary" @click="reloadWorkspace">
        {{ t('desktop.backup.reloadWorkspace') }}
      </button>
    </p>

    <p v-if="loading" class="status-line" role="status">{{ t('desktop.backup.loading') }}</p>
    <p v-else-if="!backups.length" class="empty-state">{{ t('desktop.backup.empty') }}</p>
    <ul v-else class="desktop-backup-list">
      <li v-for="backup in backups" :key="backup.id" class="desktop-backup-item">
        <div class="desktop-backup-copy">
          <strong>{{ formatCreatedAt(backup.createdAt) }}</strong>
          <span class="desktop-backup-meta">
            {{ t('desktop.backup.createdAtLabel') }} · {{ t('desktop.backup.sizeLabel') }} {{ formatSize(backup.sizeBytes) }}
          </span>
          <span v-if="backup.safetyBackup" class="desktop-badge">{{ t('desktop.backup.safetyLabel') }}</span>
        </div>
        <button type="button" class="btn-neon btn-secondary"
          :disabled="restoringId !== null" @click="askRestore(backup)">
          <RotateCcw :size="15" />
          {{ restoringId === backup.id ? t('desktop.backup.restoring') : t('desktop.backup.restore') }}
        </button>
      </li>
    </ul>

    <Teleport to="body">
      <div v-if="pendingRestore" class="desktop-dialog-overlay" role="dialog" aria-modal="true"
        @click.self="cancelRestore" @keyup.esc="cancelRestore">
        <div class="desktop-dialog">
          <h3>{{ t('desktop.backup.restoreTitle') }}</h3>
          <p>{{ t('desktop.backup.restoreBody') }}</p>
          <p class="desktop-dialog-target">{{ formatCreatedAt(pendingRestore.createdAt) }}</p>
          <div class="desktop-dialog-actions">
            <button type="button" class="btn-neon btn-ghost" @click="cancelRestore">
              {{ t('desktop.backup.restoreCancel') }}
            </button>
            <button type="button" class="btn-neon btn-primary" :disabled="restoringId !== null" @click="confirmRestore">
              {{ t('desktop.backup.restoreConfirm') }}
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </section>
</template>

<style scoped>
.desktop-panel { display: grid; gap: 16px; padding: 20px; border: 1px solid var(--border); border-radius: 16px; background: var(--bg-surface); }
.desktop-panel-head { display: flex; gap: 16px; align-items: flex-start; justify-content: space-between; flex-wrap: wrap; }
.desktop-panel-head h2 { margin: 0 0 6px; font-size: 18px; }
.desktop-panel-head p { margin: 0; color: var(--text-secondary); }
.desktop-panel-actions { display: flex; gap: 8px; }
.desktop-panel-actions button { display: inline-flex; align-items: center; gap: 6px; }
.desktop-backup-list { display: grid; gap: 10px; margin: 0; padding: 0; list-style: none; }
.desktop-backup-item { display: flex; gap: 12px; align-items: center; justify-content: space-between; padding: 12px 14px; border: 1px solid var(--border); border-radius: 12px; }
.desktop-backup-copy { display: grid; gap: 2px; }
.desktop-backup-meta { color: var(--text-secondary); font-size: 12px; }
.desktop-backup-item button { display: inline-flex; align-items: center; gap: 6px; }
.desktop-badge { justify-self: start; padding: 1px 8px; border-radius: 999px; background: var(--bg-muted, rgba(0, 0, 0, .06)); font-size: 11px; }
.desktop-dialog-overlay { position: fixed; inset: 0; display: grid; place-items: center; background: rgba(12, 16, 24, .55); z-index: 60; }
.desktop-dialog { width: min(480px, 92vw); display: grid; gap: 12px; padding: 22px; border-radius: 16px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.desktop-dialog h3 { margin: 0; font-size: 17px; }
.desktop-dialog p { margin: 0; color: var(--text-secondary); }
.desktop-dialog-target { font-weight: 600; color: var(--text-primary, inherit); }
.desktop-dialog-actions { display: flex; justify-content: flex-end; gap: 8px; }
</style>
