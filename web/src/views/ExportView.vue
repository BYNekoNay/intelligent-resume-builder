<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, Clock3, Download, FileCheck2, LoaderCircle, RefreshCw } from 'lucide-vue-next'
import { getExportTask, downloadExport, retryExport, type ExportTask, type ExportTaskStatus } from '@/api/export'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const router = useRouter()
const props = defineProps<{ exportTaskId: string }>()
const task = ref<ExportTask | null>(null)
const error = ref('')
let timer: number | null = null
let attempt = 0

const STATUS_LABEL_KEYS: Record<ExportTaskStatus, string> = {
  PENDING: 'export.statusPending',
  RUNNING: 'export.statusRunning',
  SUCCESS: 'export.statusSuccess',
  FAILED: 'export.statusFailed',
  EXPIRED: 'export.statusExpired',
  UNKNOWN: 'export.statusUnknown',
}

function normalizeStatus(status: unknown): ExportTaskStatus {
  return status === 'PENDING' || status === 'RUNNING' || status === 'SUCCESS'
    || status === 'FAILED' || status === 'EXPIRED'
    ? status
    : 'UNKNOWN'
}

function statusLabel(status: ExportTaskStatus) {
  return t(STATUS_LABEL_KEYS[status])
}

function isProcessingStatus(status: ExportTaskStatus) {
  return status === 'PENDING' || status === 'RUNNING'
}

function statusDescription(status: ExportTaskStatus) {
  return status === 'SUCCESS' ? t('export.readyDescription')
    : isProcessingStatus(status) ? t('export.processingDescription')
      : t('export.unknownStatusDescription')
}

function formatDate(value: string) {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat(undefined, { year: 'numeric', month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit' }).format(date)
}

function formatFileSize(bytes: number | null) {
  if (!bytes) return ''
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${(bytes / 1024).toFixed(1)} KB`
}

async function load() {
  try {
    const res = await getExportTask(Number(props.exportTaskId))
    task.value = { ...res.data.data, status: normalizeStatus(res.data.data.status) }
    if (isProcessingStatus(task.value.status)) {
      const delay = [1000, 2000, 4000, 5000][Math.min(attempt, 3)]
      attempt += 1
      timer = window.setTimeout(load, delay)
    }
  } catch {
    task.value = null
    error.value = t('export.error')
  }
}

async function retryLoad() {
  if (timer !== null) window.clearTimeout(timer)
  timer = null
  attempt = 0
  task.value = null
  error.value = ''
  await load()
}

function backToWorkspace() {
  void router.push('/')
}

onMounted(() => { attempt = 0; void load() })
onBeforeUnmount(() => { if (timer !== null) window.clearTimeout(timer) })

async function download() {
  try {
    const blob = (await downloadExport(Number(props.exportTaskId))).data
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'resume.pdf'
    document.body.appendChild(link)
    link.click()
    window.setTimeout(() => {
      link.remove()
      URL.revokeObjectURL(url)
    }, 1000)
  } catch { error.value = t('export.downloadError') }
}

async function retry() {
  try {
    error.value = ''
    const retried = (await retryExport(Number(props.exportTaskId))).data.data
    task.value = { ...retried, status: normalizeStatus(retried.status) }
    attempt = 0; await load()
  } catch { error.value = t('export.retryError') }
}
</script>

<template>
  <section class="workspace-page export-page">
    <header class="export-heading">
      <p class="eyebrow"><Download :size="14" /> {{ t('export.eyebrow') }}</p>
      <h1>{{ t('export.title') }}</h1>
      <p class="page-lead">{{ t('export.subtitle') }}</p>
    </header>
    <p v-if="error && task" class="form-error" role="alert">{{ error }}</p>
    <div v-if="task" :class="['workspace-card', 'export-status-panel', `status-${task.status.toLowerCase()}`]">
      <div class="status-symbol">
        <FileCheck2 v-if="task.status === 'SUCCESS'" :size="28" />
        <AlertTriangle v-else-if="task.status === 'FAILED' || task.status === 'EXPIRED' || task.status === 'UNKNOWN'" :size="28" />
        <LoaderCircle v-else :size="28" />
      </div>
      <div class="status-copy">
        <p class="section-kicker">{{ t('export.status') }} · #{{ props.exportTaskId }}</p>
        <h2>{{ statusLabel(task.status) }}</h2>
        <p v-if="task.status === 'FAILED'" class="status-error">{{ task.errorMessage || t('export.pdfRenderFailed') }}</p>
        <p v-else-if="task.status === 'EXPIRED'" class="status-error">{{ t('export.expired') }}</p>
        <p v-else-if="task.status === 'UNKNOWN'" class="status-error">{{ t('export.unknownStatusDescription') }}</p>
        <p v-else>{{ statusDescription(task.status) }}</p>
      </div>
      <dl class="export-meta">
        <div><dt>{{ t('export.format') }}</dt><dd>PDF</dd></div>
        <div v-if="task.fileSizeBytes"><dt>{{ t('export.fileSize') }}</dt><dd>{{ formatFileSize(task.fileSizeBytes) }}</dd></div>
        <div v-if="task.expiresAt"><dt>{{ t('export.expiresAt') }}</dt><dd>{{ formatDate(task.expiresAt) }}</dd></div>
      </dl>
      <footer class="export-actions">
        <span v-if="isProcessingStatus(task.status)"><Clock3 :size="14" />{{ t('export.autoRefresh') }}</span>
        <button v-if="task.status === 'UNKNOWN'" class="btn-neon btn-secondary" @click="retryLoad"><RefreshCw :size="15" />{{ t('export.retryStatus') }}</button>
        <button v-if="task.status === 'FAILED'" class="btn-neon btn-secondary" @click="retry"><RefreshCw :size="15" />{{ t('export.retry') }}</button>
        <button class="btn-neon btn-primary" :disabled="task.status !== 'SUCCESS'" @click="download"><Download :size="15" />{{ t('export.download') }}</button>
      </footer>
    </div>
    <div v-else-if="error" class="export-error-state" role="alert">
      <AlertTriangle :size="26" />
      <p>{{ error }}</p>
      <div class="job-actions">
        <button class="btn-neon btn-secondary" type="button" @click="retryLoad"><RefreshCw :size="15" />{{ t('export.retryStatus') }}</button>
        <button class="btn-neon btn-ghost" type="button" @click="backToWorkspace">{{ t('export.backToWorkspace') }}</button>
      </div>
    </div>
    <div v-else class="export-loading" role="status"><LoaderCircle :size="26" /><p>{{ t('export.loading') }}</p></div>
  </section>
</template>

<style scoped>
.export-page { width: min(100%, 760px); max-width: 760px; gap: 24px; }
.export-heading { padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.export-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 34px; letter-spacing: 0; }
.export-heading .page-lead { max-width: 620px; font-size: 12px; }
.export-status-panel { display: grid; grid-template-columns: 48px minmax(0, 1fr); gap: 18px; padding: 26px; border-radius: 7px; box-shadow: var(--shadow-sm); }
.status-symbol { display: grid; width: 48px; height: 48px; place-items: center; border-radius: 6px; color: var(--accent); background: var(--accent-light); }
.status-running .status-symbol, .status-pending .status-symbol { color: var(--info); background: var(--info-light); }
.status-failed .status-symbol, .status-expired .status-symbol { color: var(--danger); background: var(--danger-light); }
.status-running .status-symbol svg, .status-pending .status-symbol svg, .export-loading svg { animation: export-spin 1.1s linear infinite; }
.status-copy { min-width: 0; }
.section-kicker { margin: 0 0 4px !important; color: var(--text-tertiary) !important; font-family: var(--font-utility); font-size: 9px !important; font-weight: 700; }
.status-copy h2 { margin: 0; color: var(--text-primary); font-family: var(--font-display); font-size: 22px; }
.status-copy > p:last-child { margin: 6px 0 0; color: var(--text-secondary); font-size: 11px; line-height: 1.55; }
.status-copy .status-error { color: var(--danger); }
.export-meta { grid-column: 1 / -1; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 4px 0 0; padding: 0; border-block: 1px solid var(--border-soft); }
.export-meta div { padding: 13px 10px; border-right: 1px solid var(--border-soft); }
.export-meta div:last-child { border-right: 0; }
.export-meta dt { color: var(--text-tertiary); font-size: 9px; }
.export-meta dd { margin: 4px 0 0; overflow-wrap: anywhere; color: var(--text-primary); font-family: var(--font-utility); font-size: 10px; font-weight: 700; }
.export-actions { grid-column: 1 / -1; display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
.export-actions > span { display: inline-flex; align-items: center; gap: 6px; margin-right: auto; color: var(--text-tertiary); font-size: 9px; }
.export-loading { display: grid; justify-items: center; gap: 10px; padding: 45px; border: 1px solid var(--border); border-radius: 7px; color: var(--info); background: var(--bg-surface); }
.export-loading p { margin: 0; color: var(--text-secondary); font-size: 11px; }
.export-error-state { display: grid; justify-items: center; gap: 12px; padding: 38px 24px; border: 1px solid var(--danger); border-radius: 7px; color: var(--danger); background: var(--danger-light); text-align: center; }
.export-error-state p { margin: 0; color: var(--text-primary); font-size: 12px; }
.export-error-state .job-actions { justify-content: center; }
@keyframes export-spin { to { transform: rotate(360deg); } }
@media (max-width: 560px) { .export-heading h1 { font-size: 29px; } .export-status-panel { grid-template-columns: 42px minmax(0, 1fr); padding: 20px 16px; } .status-symbol { width: 42px; height: 42px; } .export-meta { grid-template-columns: 1fr; } .export-meta div { border-right: 0; border-bottom: 1px solid var(--border-soft); } .export-meta div:last-child { border-bottom: 0; } .export-actions { align-items: stretch; flex-direction: column; } .export-actions > span { margin: 0; } .export-actions .btn-neon { width: 100%; justify-content: center; } }
@media (prefers-reduced-motion: reduce) { .status-running .status-symbol svg, .status-pending .status-symbol svg, .export-loading svg { animation-duration: 2.4s; } }
</style>
