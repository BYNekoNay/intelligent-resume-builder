/**
 * JD 列表的共享状态（JD 页、生成工作台、面试准备等处复用）。
 *
 * 给第一天来的你：JD = Job Description（职位描述），用户把目标岗位的招聘要求贴进来，
 * 后面所有「按岗位定制」「匹配打分」「面试」都以它为基准。
 *
 * 使用方：JobDescriptionView（JD 管理页）、GenerationWorkbenchView（生成工作台选岗位）。
 *
 * 持久化：仅内存，页面刷新即空，下次进入重新 load()。
 *
 * 边界：只做「取列表 + 维护 loading」，不吞异常也不保存错误文案——load() 失败会抛给调用方，
 * 由页面决定提示方式（与 careerMaterial/matchResult store 吞错成 error 文案的风格不同，别搞混）。
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
