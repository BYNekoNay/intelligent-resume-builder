import { apiClient, type ApiResponse } from './client'

export type ApplicationStatus = 'DRAFT' | 'APPLIED' | 'INTERVIEWING' | 'OFFERED' | 'REJECTED' | 'WITHDRAWN'
export interface ApplicationRecord {
  id: number
  jobDescriptionId: number
  resumeVersionId: number
  status: ApplicationStatus
  coverLetterText: string | null
  openingMessageText: string | null
  feedbackText: string | null
  appliedAt: string | null
  createdAt: string
  updatedAt: string
}
export function listApplications() { return apiClient.get<ApiResponse<ApplicationRecord[]>>('/api/applications') }
export function createApplication(payload: { jobDescriptionId: number; resumeVersionId: number; status: ApplicationStatus; coverLetterText?: string; openingMessageText?: string }) {
  return apiClient.post<ApiResponse<ApplicationRecord>>('/api/applications', payload)
}
export function updateApplicationStatus(id: number, status: ApplicationStatus, feedbackText?: string) {
  return apiClient.patch<ApiResponse<ApplicationRecord>>(`/api/applications/${id}/status`, { status, feedbackText })
}
