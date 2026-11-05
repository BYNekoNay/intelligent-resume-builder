/**
 * JD 列表的共享状态（JD 页、生成工作台、面试准备等处复用）。
 * 边界：只做「取列表 + 维护 loading」，不吞异常也不保存错误文案——load() 失败会抛给调用方，由页面决定提示方式。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { listJobs, type JobDescriptionSummary } from '@/api/jobDescription'

export const useJobDescriptionStore = defineStore('job-description', () => {
  const items = ref<JobDescriptionSummary[]>([])
  const loading = ref(false)

  async function load() {
    loading.value = true
    try {
      const res = await listJobs()
      items.value = res
    } finally {
      loading.value = false
    }
  }

  return { items, loading, load }
})
