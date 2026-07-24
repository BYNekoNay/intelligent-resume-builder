<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { getExportTask, downloadExport, retryExport, type ExportTask } from '@/api/export'
import { useLocale } from '@/i18n'

const { t } = useLocale()
const props = defineProps<{ exportTaskId: string }>()
const task = ref<ExportTask | null>(null)
const error = ref('')
let timer: number | null = null
let attempt = 0

async function load() {
  try {
    const res = await getExportTask(Number(props.exportTaskId))
    task.value = res.data.data
    if (task.value.status === 'PENDING' || task.value.status === 'RUNNING') {
      const delay = [1000, 2000, 4000, 5000][Math.min(attempt, 3)]
      attempt += 1
      timer = window.setTimeout(load, delay)
    }
  } catch { error.value = t('export.error') }
}

onMounted(() => { attempt = 0; void load() })
onBeforeUnmount(() => { if (timer !== null) window.clearTimeout(timer) })

async function download() {
  try {
    const blob = (await downloadExport(Number(props.exportTaskId))).data
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url; link.download = 'resume.pdf'; link.click()
    URL.revokeObjectURL(url)
  } catch { error.value = t('export.downloadError') }
}

async function retry() {
  try {
    error.value = ''
    task.value = (await retryExport(Number(props.exportTaskId))).data.data
    attempt = 0; await load()
  } catch { error.value = t('export.retryError') }
}
</script>

<template>
  <section class="workspace-page">
    <h1>{{ t('export.title') }} #{{ props.exportTaskId }}</h1>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="task" class="workspace-card">
      <p>{{ t('export.status') }}: {{ task.status }}</p>
      <p v-if="task.status === 'FAILED'" class="form-error">{{ task.errorMessage || t('export.pdfRenderFailed') }}</p>
      <p v-else-if="task.status === 'EXPIRED'" class="form-error">{{ t('export.expired') }}</p>
      <p v-if="task.expiresAt">{{ t('export.expiresAt') }}: {{ task.expiresAt }}</p>
      <button v-if="task.status === 'FAILED'" class="btn-neon btn-secondary" @click="retry">{{ t('export.retry') }}</button>
      <button class="btn-neon btn-primary" :disabled="task.status !== 'SUCCESS'" @click="download">{{ t('export.download') }}</button>
    </div>
    <p v-else>{{ t('export.loading') }}</p>
  </section>
</template>
