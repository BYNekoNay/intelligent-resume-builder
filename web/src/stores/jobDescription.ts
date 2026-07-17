import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listJobs, type JobDescription } from '@/api/jobDescription'

export const useJobDescriptionStore = defineStore('job-description', () => {
  const items = ref<JobDescription[]>([])
  const loading = ref(false)

  async function load() {
    loading.value = true
    try {
      const res = await listJobs()
      items.value = res.data.data
    } finally {
      loading.value = false
    }
  }

  return { items, loading, load }
})