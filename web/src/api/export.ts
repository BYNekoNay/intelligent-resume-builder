import { apiClient, type ApiResponse } from './client'

export interface ExportTask {
  taskId: number
  templateCode: string
  status: 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'EXPIRED'
  fileSizeBytes: number | null
  checksumSha256: string | null
  errorMessage: string | null
  expiresAt: string | null
  downloadUrl: string | null
}

export type ResumeTemplateCode = 'classic' | 'modern' | 'minimal' | 'ats' | 'executive' | 'compact' | 'academic'

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
