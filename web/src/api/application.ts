import { apiClient, type ApiResponse } from './client'

export type ApplicationStatus = 'DRAFT' | 'APPLIED' | 'INTERVIEWING' | 'OFFERED' | 'REJECTED' | 'WITHDRAWN'
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
  version: number
  createdAt: string
  updatedAt: string
}
export function listApplications() { return apiClient.get<ApiResponse<ApplicationRecord[]>>('/api/applications') }
export interface ApplicationPayload { jobDescriptionId: number; resumeVersionId: number; status: ApplicationStatus; coverLetterText?: string; emailBodyText?: string; openingMessageText?: string; version?: number }
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
