/**
 * 匹配评分结果的共享状态：current 保存最近一次评分（新建或回读）。
 * 边界：score() 每次都会重新计算并覆盖 current，load() 只回读已存结果；两者都把失败收敛成 error 文案，不向外抛。
 */
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
      current.value = res
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
      current.value = res
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '评分结果无法获取'
    } finally {
      loading.value = false
    }
  }

  return { current, loading, error, score, load }
})
