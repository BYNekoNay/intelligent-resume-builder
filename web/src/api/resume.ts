import { http } from './client'
import type { ResumeTemplateCode } from './export'

export interface ResumeSummary {
  id: number
  title: string
  currentVersionId: number | null
  jobDescriptionId: number | null
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
  generationContext?: Record<string, unknown> | null
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
  return http.post<ResumeSummary>('/api/resumes', { title, resumeJson })
}

export function listResumes() {
  return http.get<ResumeSummary[]>('/api/resumes')
}

export function getResume(id: number) {
  return http.get<ResumeSummary>(`/api/resumes/${id}`)
}

export function updateResumeTitle(id: number, title: string) {
  return http.put<ResumeSummary>(`/api/resumes/${id}`, { title })
}

export function listVersions(resumeId: number, archived = false) {
  return http.get<ResumeVersionSummary[]>(`/api/resumes/${resumeId}/versions`, { params: { archived } })
}

export function getResumeVersion(versionId: number) {
  return http.get<ResumeVersion>(`/api/resume-versions/${versionId}`)
}

export function createManualVersion(resumeId: number, resumeJson: Record<string, unknown>, summary?: string) {
  return http.post<ResumeVersion>(`/api/resumes/${resumeId}/versions`, {
    resumeJson,
    sourceType: 'MANUAL',
    optimizationSummary: summary ?? null,
  })
}

export function setCurrentVersion(resumeId: number, versionId: number) {
  return http.patch<void>(`/api/resumes/${resumeId}/current-version`, { versionId })
}

export type AtsRestoreProvenance = {
  atsResultId: number
  atsItem: string
}

export function restoreResumeVersion(resumeId: number, versionId: number, provenance?: AtsRestoreProvenance) {
  return http.post<ResumeVersion>(`/api/resumes/${resumeId}/versions/${versionId}/restore`, provenance)
}

export function archiveResumeVersion(resumeId: number, versionId: number) {
  return http.post<void>(`/api/resumes/${resumeId}/versions/${versionId}/archive`)
}

export function unarchiveResumeVersion(resumeId: number, versionId: number) {
  return http.post<void>(`/api/resumes/${resumeId}/versions/${versionId}/unarchive`)
}

export function deleteResume(id: number) {
  return http.delete<void>(`/api/resumes/${id}`)
}

export function listResumesByJd(jdId: number) {
  return http.get<ResumeSummary[]>(`/api/resumes/by-jd/${jdId}`)
}
