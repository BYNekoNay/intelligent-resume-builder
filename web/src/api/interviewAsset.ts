/**
 * 面试问答资产（interview answer assets）接口层：把面试中的问答 + AI 建议答案沉淀成可复用条目。
 * 边界：仅封装 /api/interview-answer-assets 的增删改查；关联关系由 sectionKeys（简历章节）与 materialIds（职业素材）表达，
 * 服务端按这些字段做过滤，前端不负责拼装关联。
 */
import { http } from './client'

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
  return http.get<InterviewAsset[]>('/api/interview-answer-assets', { params })
}
export function createInterviewAsset(payload: InterviewAssetPayload) {
  return http.post<InterviewAsset>('/api/interview-answer-assets', payload)
}
export function updateInterviewAsset(id: number, payload: InterviewAssetPayload) {
  return http.put<InterviewAsset>(`/api/interview-answer-assets/${id}`, payload)
}
export function deleteInterviewAsset(id: number) {
  return http.delete<void>(`/api/interview-answer-assets/${id}`)
}
