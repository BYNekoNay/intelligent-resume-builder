import { apiClient, type ApiResponse } from './client'

export interface JobDescription {
  id: number
  title: string
  companyName: string | null
  jdText: string
  parsedKeywordsJson: Record<string, unknown> | null
  parsedAt: string | null
  parsedVersion: string | null
  createdAt: string
  updatedAt: string
}

export interface JobDescriptionPayload {
  title: string
  companyName?: string
  jdText: string
}

export function listJobs() {
  return apiClient.get<ApiResponse<JobDescription[]>>('/api/jobs')
}

export function createJob(payload: JobDescriptionPayload) {
  return apiClient.post<ApiResponse<JobDescription>>('/api/jobs', payload)
}

export function parseJob(id: number) {
  return apiClient.post<ApiResponse<JobDescription>>(`/api/jobs/${id}/parse`)
}

export function deleteJob(id: number) {
  return apiClient.delete<ApiResponse<void>>(`/api/jobs/${id}`)
}