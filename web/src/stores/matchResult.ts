/**
 * 匹配评分结果的共享状态：current 保存最近一次评分（新建或回读）。
 *
 * 给第一天来的你：「匹配评分」回答的问题是——我的简历（某个版本）和某个 JD 有多匹配？
 * 后端按关键词/技能/经历三个维度打分并给出解释（api/scoring.ts 的 MatchResponse）。
 *
 * 使用方：MatchResultView（匹配结果页）。
 *
 * 持久化：仅内存。已保存的评分在后端有记录（getMatchResult 按 id 回读），但本 store 刷新即空。
 *
 * 边界：score() 每次都会重新计算并覆盖 current，load() 只回读已存结果；两者都把失败收敛成
 * error 文案，不向外抛（调用方只管绑 error 显示，不需要 try/catch）。
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
