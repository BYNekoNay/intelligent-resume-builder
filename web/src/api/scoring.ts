/**
 * 简历-职位匹配打分的接口层：POST /api/scoring/match 现场计算，GET /api/scoring/results/{id} 读取已存结果。
 * 边界：只透传分数与解释（matched/partialMatched/missing/suggestions），不做加权或阈值判断；
 * 规则版本随响应返回（ruleVersion），说明结果可能因规则变化而与历史记录不同。
 */
import { http } from './client'

export interface MatchExplanation {
  matched: string[]
  partialMatched: string[]
  missing: string[]
  suggestions: string[]
  disclaimer: string
}

export interface MatchResponse {
  matchResultId: number
  totalScore: number
  keywordScore: number
  skillScore: number
  experienceScore: number
  explanation: MatchExplanation
  ruleVersion: string
}

export function scoreMatch(resumeVersionId: number, jobDescriptionId: number) {
  return http.post<MatchResponse>('/api/scoring/match', {
    resumeVersionId,
    jobDescriptionId,
  })
}

export function getMatchResult(id: number) {
  return http.get<MatchResponse>(`/api/scoring/results/${id}`)
}