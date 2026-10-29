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