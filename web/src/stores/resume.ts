/**
 * 简历列表的共享状态（简历库、生成后跳转等处复用）。
 *
 * 给第一天来的你：本平台的简历是「一份简历 + 多个版本」的结构——每次 AI 优化/手动保存
 * 都会生成新版本（api/resume.ts），列表条目里的 currentVersionId 指向当前生效的版本。
 *
 * 使用方：ResumeListView（简历库页）。
 *
 * 持久化：仅内存，页面刷新即空，下次进入重新 load()。
 *
 * 边界：只保存摘要数组与 loading（摘要不含简历正文 resumeJson）；load() 失败会抛给调用方，
 * 页面自行决定是否提示。
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