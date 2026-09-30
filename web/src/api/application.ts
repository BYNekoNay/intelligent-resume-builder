import { apiClient, type ApiResponse } from './client'

export type ApplicationStatus = 'DRAFT' | 'APPLIED' | 'INTERVIEWING' | 'OFFERED' | 'REJECTED' | 'WITHDRAWN'
export type FollowUpFilter = 'ALL' | 'TODAY' | 'OVERDUE'

export interface ApplicationRecord {
  id: number
  jobDescriptionId: number
  resumeVersionId: number
  status: ApplicationStatus
  coverLetterText: string | null
  emailBodyText: string | null
  openingMessageText: string | null
  feedbackText: string | null
  appliedAt: string | null
  nextFollowUpAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

/**
 * 投递记录列表摘要（#50）：草稿长文本不进列表（展开/编辑时按需走详情），
 * `draftCount` 支撑卡片上的「n/3」标记；`feedbackText` 仍在列表里——
 * 状态迁移接口按请求值覆盖备注，拖拽改状态时必须携带现值。
 */
export interface ApplicationSummary {
  id: number
  jobDescriptionId: number
  resumeVersionId: number
  status: ApplicationStatus
  feedbackText: string | null
  draftCount: number
  appliedAt: string | null
  nextFollowUpAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface ApplicationStats {
  total: number
  byStatus: { status: ApplicationStatus; count: number; percent: number | null }[]
  conversionRates: { appliedToInterviewing: number | null; interviewingToOffered: number | null; appliedToOffered: number | null }
  avgStageDurationDays: { applied: number | null; interviewing: number | null; totalToOffer: number | null }
}

export function listApplications(followUp?: FollowUpFilter) {
  return apiClient.get<ApiResponse<ApplicationSummary[]>>('/api/applications', { params: { followUp } })
}
export function getApplication(id: number) {
  return apiClient.get<ApiResponse<ApplicationRecord>>(`/api/applications/${id}`)
}
export interface ApplicationPayload { jobDescriptionId: number; resumeVersionId: number; status: ApplicationStatus; coverLetterText?: string; emailBodyText?: string; openingMessageText?: string; version?: number; nextFollowUpAt?: string | null }
export function createApplication(payload: ApplicationPayload) {
  return apiClient.post<ApiResponse<ApplicationRecord>>('/api/applications', payload)
}
export function updateApplication(id: number, payload: ApplicationPayload) {
  return apiClient.put<ApiResponse<ApplicationRecord>>(`/api/applications/${id}`, payload)
}
export function updateApplicationStatus(id: number, status: ApplicationStatus, version: number, feedbackText?: string) {
  return apiClient.patch<ApiResponse<ApplicationRecord>>(`/api/applications/${id}/status`, { status, version, feedbackText })
}
export function deleteApplication(id: number) { return apiClient.delete<ApiResponse<void>>(`/api/applications/${id}`) }
export function getApplicationStats() {
  return apiClient.get<ApiResponse<ApplicationStats>>('/api/applications/stats')
}
