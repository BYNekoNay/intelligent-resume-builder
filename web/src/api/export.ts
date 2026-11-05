/**
 * PDF 导出接口层：对应后端 ExportController（/api/exports）。
 * 流程是异步任务制：createExport 建任务 → getExportTask 轮询 → downloadExport 拿文件（Blob 附件，
 * 非 ApiResponse 信封）；与同步返回的 JSON 导出（api/resume.ts 的 downloadJsonResume）不同。
 * 错误码语义：50003（PDF_FAILURE）表示 pdf-service 渲染/连接/容量类失败；40902（版本已归档）表示
 * 该版本不能发起导出；40901（CONFLICT）表示重试非法（只有失败的任务可以重试）。
 */
import { http } from './client'

export type ExportTaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'EXPIRED' | 'UNKNOWN'

export interface ExportTask {
  taskId: number
  templateCode: string
  status: ExportTaskStatus
  fileSizeBytes: number | null
  checksumSha256: string | null
  errorMessage: string | null
  expiresAt: string | null
  downloadUrl: string | null
}

export type ResumeTemplateCode = 'classic' | 'modern' | 'minimal' | 'ats' | 'executive' | 'compact' | 'academic'

export function createExport(resumeVersionId: number, templateCode: ResumeTemplateCode = 'classic') {
  return http.post<ExportTask>('/api/exports/pdf', { resumeVersionId, templateCode })
}

export function getExportTask(id: number) {
  return http.get<ExportTask>(`/api/exports/tasks/${id}`)
}

export function retryExport(id: number) {
  return http.post<ExportTask>(`/api/exports/tasks/${id}/retry`)
}

/**
 * 下载导出文件。响应体上限 `app.pdf.max-output-bytes`（默认 10MB），全局 10s 超时会在
 * 慢网络下中断合法下载（大文件在移动网络需数十秒），故显式放宽。契约由 UploadPathContractTest 守护。
 */
export function downloadExport(id: number) {
  return http.get<Blob>(`/api/exports/files/${id}`, { responseType: 'blob', timeout: 60_000 })
}
