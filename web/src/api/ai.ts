import { apiClient, type ApiResponse } from './client'

export interface ConsentRequest {
  policyVersion: string
  providerCode: string
  taskScopes: string[]
  dataCategories: string[]
  noticeHash: string
}

export interface ConsentResponse {
  id: number
  eventType: 'GRANTED' | 'WITHDRAWN'
  createdAt: string
  policyVersion: string
  providerCode: string
  taskScopes: string[]
  dataCategories: string[]
}

export interface GenerateTaskRequest {
  taskType?: string
  targetResumeId?: number | null
  jobDescriptionId?: number | null
  jdText?: string
  companyName?: string
  positionTitle?: string
  resumeTitle?: string
  input?: {
    includedMaterialIds?: number[]
    preferredMaterialIds?: number[]
    excludedMaterialIds?: number[]
  }
  additionalInput?: Record<string, unknown>
}

export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'

export interface AiTask {
  id: number
  taskType: 'JOB_GENERATION' | 'EXPORT_PDF'
  jobDescriptionId: number | null
  status: TaskStatus
  confirmationStatus: 'PENDING' | 'CONFIRMED' | 'REJECTED' | null
  resultJson: Record<string, unknown> | null
  errorMessage: string | null
  retryCount: number
  resultResumeVersionId: number | null
  createdAt: string
  updatedAt: string
}

export interface ConfirmRequest {
  taskUpdatedAt: string
  items: { outputPath: string; decision: 'ACCEPT' | 'EDIT' | 'REJECT'; editedValue?: Record<string, unknown> }[]
  additionalResumeJson?: Record<string, unknown>
  resumeTitle?: string
  targetResumeId?: number | null
}

export interface InlineOptimizeRequest {
  resumeVersionId: number
  section: string
  content: string
  jobDescriptionId?: number
}

export interface InlineOptimizeResponse {
  recordId: number
  section: string
  originalContent: string
  candidates: { content: string; suggestion: string }[]
  requiresManualConfirmation: boolean
}

export interface AchievementGuidanceResponse {
  questions: string[]
  writesBackAutomatically: boolean
}

export function grantConsent(payload: ConsentRequest) {
  return apiClient.post<ApiResponse<ConsentResponse>>('/api/ai/consent', payload)
}

export function getConsent() {
  return apiClient.get<ApiResponse<ConsentResponse | null>>('/api/ai/consent')
}

export function withdrawConsent() {
  return apiClient.delete<ApiResponse<ConsentResponse>>('/api/ai/consent')
}

export function generateForJob(payload: GenerateTaskRequest, idempotencyKey: string) {
  return apiClient.post<ApiResponse<AiTask>>('/api/ai/generate-resume-for-job', payload, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function getTask(id: number) {
  return apiClient.get<ApiResponse<AiTask>>(`/api/ai/tasks/${id}`)
}

export function retryTask(id: number) {
  return apiClient.post<ApiResponse<AiTask>>(`/api/ai/tasks/${id}/retry`)
}

export function confirmTask(id: number, payload: ConfirmRequest, idempotencyKey: string) {
  return apiClient.post<ApiResponse<{ resumeVersionId: number; versionNo: number; resultResumeVersionId: number; rejectedPaths: string[]; newMaterialIds: number[]; resumeId: number }>>(
    `/api/ai/tasks/${id}/confirm`,
    payload,
    { headers: { 'Idempotency-Key': idempotencyKey } },
  )
}

export function rejectTask(id: number, taskUpdatedAt: string) {
  return apiClient.post<ApiResponse<void>>(`/api/ai/tasks/${id}/reject`, { taskUpdatedAt })
}

export function inlineOptimize(payload: InlineOptimizeRequest) {
  return apiClient.post<ApiResponse<InlineOptimizeResponse>>('/api/ai/inline-optimize', payload)
}

export function guideAchievement(payload: { resumeVersionId: number; section: string; content: string }) {
  return apiClient.post<ApiResponse<AchievementGuidanceResponse>>('/api/ai/achievement-guidance', payload)
}
