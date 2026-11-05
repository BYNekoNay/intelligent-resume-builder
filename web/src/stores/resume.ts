/**
 * 简历列表的共享状态（简历库、生成后跳转等处复用）。
 * 边界：只保存摘要数组与 loading；load() 失败会抛给调用方，页面自行决定是否提示。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listResumes, type ResumeSummary } from '@/api/resume'

export const useResumeStore = defineStore('resume', () => {
  const items = ref<ResumeSummary[]>([])
  const loading = ref(false)

  async function load() {
    loading.value = true
    try {
      const res = await listResumes()
      items.value = res
    } finally {
      loading.value = false
    }
  }

  return { items, loading, load }
})