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
      items.value = res.data.data
    } finally {
      loading.value = false
    }
  }

  return { items, loading, load }
})