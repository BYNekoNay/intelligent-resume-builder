import { defineStore } from 'pinia'
import { ref } from 'vue'
import { scoreMatch, getMatchResult, type MatchResponse } from '@/api/scoring'

export const useMatchResultStore = defineStore('match-result', () => {
  const current = ref<MatchResponse | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function score(resumeVersionId: number, jobDescriptionId: number) {
    loading.value = true
    error.value = null
    try {
      const res = await scoreMatch(resumeVersionId, jobDescriptionId)
      current.value = res.data.data
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '评分结果无法获取'
    } finally {
      loading.value = false
    }
  }

  async function load(id: number) {
    loading.value = true
    error.value = null
    try {
      const res = await getMatchResult(id)
      current.value = res.data.data
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '评分结果无法获取'
    } finally {
      loading.value = false
    }
  }

  return { current, loading, error, score, load }
})
