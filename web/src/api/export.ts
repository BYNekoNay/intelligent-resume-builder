import { apiClient, type ApiResponse } from './client'

export interface ExportTask {
  id: number
  resumeVersionId: number
  templateCode: string
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'EXPIRED'
  fileSizeBytes: number | null
  sha256: string | null
  errorMessage: string | null
  expiresAt: string | null
  createdAt: string
  updatedAt: string
}

export type ResumeTemplateCode = 'classic' | 'modern' | 'minimal'

export function createExport(resumeVersionId: number, templateCode: ResumeTemplateCode = 'classic') {
  return apiClient.post<ApiResponse<ExportTask>>('/api/exports/pdf', { resumeVersionId, templateCode })
}

export function getExportTask(id: number) {
  return apiClient.get<ApiResponse<ExportTask>>(`/api/exports/tasks/${id}`)
}

export function retryExport(id: number) {
  return apiClient.post<ApiResponse<ExportTask>>(`/api/exports/tasks/${id}/retry`)
}

export function downloadExport(id: number) {
  return apiClient.get<Blob>(`/api/exports/files/${id}`, { responseType: 'blob' })
}
