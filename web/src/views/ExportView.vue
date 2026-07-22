<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { getExportTask, downloadExport, retryExport, type ExportTask } from '@/api/export'

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
  } catch {
    error.value = '导出任务状态无法获取，请检查网络后刷新。'
  }
}

onMounted(() => {
  attempt = 0
  void load()
})

onBeforeUnmount(() => { if (timer !== null) window.clearTimeout(timer) })

async function download() {
  try {
    const blob = (await downloadExport(Number(props.exportTaskId))).data
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = 'resume.pdf'
    link.click()
    URL.revokeObjectURL(url)
  } catch {
    error.value = 'PDF 下载失败，文件可能已过期。'
  }
}

async function retry() {
  try {
    error.value = ''
    task.value = (await retryExport(Number(props.exportTaskId))).data.data
    attempt = 0
    await load()
  } catch {
    error.value = '导出重试失败，请稍后再试。'
  }
}
</script>

<template>
  <section class="workspace-page">
    <h1>PDF 导出任务 #{{ props.exportTaskId }}</h1>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="task" class="workspace-card"><p>状态: {{ task.status }}</p><p v-if="task.status === 'FAILED'" class="form-error">{{ task.errorMessage || 'PDF 渲染失败。' }}</p><p v-else-if="task.status === 'EXPIRED'" class="form-error">文件已过期，请重新生成。</p><p v-if="task.expiresAt">过期时间: {{ task.expiresAt }}</p><button v-if="task.status === 'FAILED'" class="btn-neon btn-secondary" @click="retry">重试导出</button><button class="btn-neon btn-primary" :disabled="task.status !== 'SUCCESS'" @click="download">下载 PDF</button></div>
    <p v-else>正在获取导出任务…</p>
  </section>
</template>
