import { apiClient, type ApiResponse } from './client'

export interface ResumeSummary {
  id: number
  title: string
  currentVersionId: number | null
  createdAt: string
  updatedAt: string
}

export interface ResumeVersion {
  id: number
  resumeId: number
  versionNo: number
  sourceType: 'MANUAL' | 'AI_OPTIMIZED' | 'JD_CUSTOMIZED' | 'MATERIAL_CUSTOMIZED'
  resumeJson: Record<string, unknown>
  optimizationSummary: string | null
  createdAt: string
}

export function createResume(title: string, resumeJson: Record<string, unknown>) {
  return apiClient.post<ApiResponse<ResumeSummary>>('/api/resumes', { title, resumeJson })
}

export function listResumes() {
  return apiClient.get<ApiResponse<ResumeSummary[]>>('/api/resumes')
}

export function getResume(id: number) {
  return apiClient.get<ApiResponse<ResumeSummary>>(`/api/resumes/${id}`)
}

export function updateResumeTitle(id: number, title: string) {
  return apiClient.put<ApiResponse<ResumeSummary>>(`/api/resumes/${id}`, { title })
}

export function listVersions(resumeId: number) {
  return apiClient.get<ApiResponse<ResumeVersion[]>>(`/api/resumes/${resumeId}/versions`)
}

export function createManualVersion(resumeId: number, resumeJson: Record<string, unknown>, summary?: string) {
  return apiClient.post<ApiResponse<ResumeVersion>>(`/api/resumes/${resumeId}/versions`, {
    resumeJson,
    sourceType: 'MANUAL',
    optimizationSummary: summary ?? null,
  })
}

export function setCurrentVersion(resumeId: number, versionId: number) {
  return apiClient.post<ApiResponse<ResumeSummary>>(`/api/resumes/${resumeId}/versions/${versionId}/current`)
}

export function deleteResume(id: number) {
  return apiClient.delete<ApiResponse<void>>(`/api/resumes/${id}`)
}
