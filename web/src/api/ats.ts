import { apiClient, type ApiResponse } from './client'

export interface AtsCheckResponse {
  id: number
  totalScore: number
  checks: Record<string, unknown>
  risks: string[]
  disclaimer: string
}

export function runAtsCheck(resumeVersionId: number, jobDescriptionId: number) {
  return apiClient.post<ApiResponse<AtsCheckResponse>>('/api/ats/check', { resumeVersionId, jobDescriptionId })
}
