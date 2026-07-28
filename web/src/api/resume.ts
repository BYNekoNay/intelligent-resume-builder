import { apiClient, type ApiResponse } from './client'
import type { ResumeTemplateCode } from './export'

export interface ResumeSummary {
  id: number
  title: string
  currentVersionId: number | null
  jobDescriptionId: number | null
  createdAt: string
  updatedAt: string
}

export interface ResumeVersion {
  id: number
  resumeId: number
  versionNo: number
  sourceType: 'MANUAL' | 'AI_OPTIMIZED' | 'JD_CUSTOMIZED' | 'MATERIAL_CUSTOMIZED' | 'RESTORED'
  resumeJson: Record<string, unknown>
  optimizationSummary: string | null
  createdAt: string
  archivedAt: string | null
  restoredFromVersionId: number | null
}

export interface ResumeVersionSummary {
  id: number
  versionNo: number
  sourceType: ResumeVersion['sourceType']
  templateCode: ResumeTemplateCode
  optimizationSummary: string | null
  createdAt: string
  archivedAt: string | null
  restoredFromVersionId: number | null
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

export function listVersions(resumeId: number, archived = false) {
  return apiClient.get<ApiResponse<ResumeVersionSummary[]>>(`/api/resumes/${resumeId}/versions`, { params: { archived } })
}

export function getResumeVersion(versionId: number) {
  return apiClient.get<ApiResponse<ResumeVersion>>(`/api/resume-versions/${versionId}`)
}

export function createManualVersion(resumeId: number, resumeJson: Record<string, unknown>, summary?: string) {
  return apiClient.post<ApiResponse<ResumeVersion>>(`/api/resumes/${resumeId}/versions`, {
    resumeJson,
    sourceType: 'MANUAL',
    optimizationSummary: summary ?? null,
  })
}

export function setCurrentVersion(resumeId: number, versionId: number) {
  return apiClient.patch<ApiResponse<void>>(`/api/resumes/${resumeId}/current-version`, { versionId })
}

export function restoreResumeVersion(resumeId: number, versionId: number) {
  return apiClient.post<ApiResponse<ResumeVersion>>(`/api/resumes/${resumeId}/versions/${versionId}/restore`)
}

export function archiveResumeVersion(resumeId: number, versionId: number) {
  return apiClient.post<ApiResponse<void>>(`/api/resumes/${resumeId}/versions/${versionId}/archive`)
}

export function unarchiveResumeVersion(resumeId: number, versionId: number) {
  return apiClient.post<ApiResponse<void>>(`/api/resumes/${resumeId}/versions/${versionId}/unarchive`)
}

export function deleteResume(id: number) {
  return apiClient.delete<ApiResponse<void>>(`/api/resumes/${id}`)
}

export function listResumesByJd(jdId: number) {
  return apiClient.get<ApiResponse<ResumeSummary[]>>(`/api/resumes/by-jd/${jdId}`)
}
