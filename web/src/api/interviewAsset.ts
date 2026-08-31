import { apiClient, type ApiResponse } from './client'

export interface InterviewAsset {
  id: number
  interviewRecordId: number | null
  questionText: string
  originalAnswerText: string
  suggestedAnswerText: string | null
  feedbackJson: Record<string, unknown> | null
  createdAt: string
  updatedAt: string
  sectionKeys: string[]
  materialIds: number[]
}

export interface InterviewAssetPayload {
  interviewRecordId?: number
  questionText: string
  originalAnswerText: string
  suggestedAnswerText?: string
  feedbackJson?: Record<string, unknown>
  sectionKeys?: string[]
  materialIds?: number[]
}

export function listInterviewAssets(params?: { jobDescriptionId?: number; keyword?: string; sectionKey?: string; interviewRecordId?: number }) {
  return apiClient.get<ApiResponse<InterviewAsset[]>>('/api/interview-answer-assets', { params })
}
export function createInterviewAsset(payload: InterviewAssetPayload) {
  return apiClient.post<ApiResponse<InterviewAsset>>('/api/interview-answer-assets', payload)
}
export function updateInterviewAsset(id: number, payload: InterviewAssetPayload) {
  return apiClient.put<ApiResponse<InterviewAsset>>(`/api/interview-answer-assets/${id}`, payload)
}
export function deleteInterviewAsset(id: number) {
  return apiClient.delete<ApiResponse<void>>(`/api/interview-answer-assets/${id}`)
}
