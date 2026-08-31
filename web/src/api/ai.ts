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
  status: 'GRANTED' | 'WITHDRAWN'
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

export const JOB_GENERATION_POLICY_VERSION = 'v1.2.0'
export const JOB_GENERATION_DATA_CATEGORIES = ['CAREER_MATERIAL', 'JOB_DESCRIPTION', 'PERSONAL_PROFILE'] as const
export const AI_CONSENT_TASK_SCOPES = ['JOB_MATERIAL_SELECTION', 'JOB_GENERATION', 'RESUME_OPTIMIZE', 'ACHIEVEMENT_GUIDANCE', 'COMMUNICATION_GENERATE', 'MATERIAL_IMPORT', 'INLINE_OPTIMIZE', 'INTERVIEW_COACH', 'ATS_ANALYSIS'] as const
export const AI_CONSENT_DATA_CATEGORIES = ['RESUME', 'INTERVIEW_ANSWER', ...JOB_GENERATION_DATA_CATEGORIES] as const

export function hasJobGenerationConsent(consent: ConsentResponse | null | undefined) {
  return consent?.status === 'GRANTED'
    && consent.policyVersion === JOB_GENERATION_POLICY_VERSION
    && consent.taskScopes.includes('JOB_MATERIAL_SELECTION')
    && consent.taskScopes.includes('JOB_GENERATION')
    && JOB_GENERATION_DATA_CATEGORIES.every(category => consent.dataCategories.includes(category))
}

export function hasFullAiConsent(consent: ConsentResponse | null | undefined) {
  return consent?.status === 'GRANTED'
    && consent.policyVersion === JOB_GENERATION_POLICY_VERSION
    && AI_CONSENT_TASK_SCOPES.every(scope => consent.taskScopes.includes(scope))
    && AI_CONSENT_DATA_CATEGORIES.every(category => consent.dataCategories.includes(category))
}

export type MaterialSelectionRequest = GenerateTaskRequest

export interface MaterialSelectionItem {
  materialId: number
  title: string
  materialType: string
  relevanceScore?: number
  reason?: string
  matchedRequirements?: string[]
  usagePreference?: 'NORMAL' | 'PREFERRED' | 'EXCLUDED'
  exclusionReason?: 'GLOBAL' | 'MANUAL'
}

export interface MaterialSelectionResult {
  recommended: MaterialSelectionItem[]
  unselected: MaterialSelectionItem[]
  missingRequirements: string[]
  excluded: MaterialSelectionItem[]
}

export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELLED'

export interface AiTask {
  id: number
  taskType: 'JOB_MATERIAL_SELECTION' | 'JOB_GENERATION' | 'INLINE_OPTIMIZE' | 'ACHIEVEMENT_GUIDANCE' | 'MATERIAL_IMPORT' | 'ATS_ANALYSIS' | 'COMMUNICATION_GENERATE' | 'EXPORT_PDF'
  parentTaskId?: number | null
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
  originalContent: string
  candidates: { content: string; suggestion: string }[]
  requiresManualConfirmation: boolean
  emptyReason?: string
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

export function selectMaterialsForJob(payload: MaterialSelectionRequest, idempotencyKey: string) {
  const request = {
    jobDescriptionId: payload.jobDescriptionId,
    jdText: payload.jdText,
    companyName: payload.companyName,
    positionTitle: payload.positionTitle,
    includedMaterialIds: payload.input?.includedMaterialIds ?? [],
    preferredMaterialIds: payload.input?.preferredMaterialIds ?? [],
    excludedMaterialIds: payload.input?.excludedMaterialIds ?? [],
    resumeTitle: payload.resumeTitle,
  }
  return apiClient.post<ApiResponse<AiTask>>('/api/ai/select-materials-for-job', request, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function confirmMaterials(
  id: number,
  payload: {
    taskUpdatedAt: string
    selectedMaterialIds: number[]
    forcedIncludedMaterialIds?: number[]
    resumeTitle?: string
  },
  idempotencyKey: string,
) {
  return apiClient.post<ApiResponse<AiTask>>(`/api/ai/tasks/${id}/confirm-materials`, payload, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function getTask(id: number) {
  return apiClient.get<ApiResponse<AiTask>>(`/api/ai/tasks/${id}`)
}

export function listTaskContinuations() {
  return apiClient.get<ApiResponse<AiTask[]>>('/api/ai/tasks/continuations')
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
  return apiClient.post<ApiResponse<AiTask>>('/api/ai/inline-optimize', payload)
}

export async function waitForAiTaskResult<T>(taskId: number, maxAttempts = 30): Promise<T> {
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    await new Promise((resolve) => window.setTimeout(resolve, 1000))
    const task = (await getTask(taskId)).data.data
    if (task.status === 'SUCCESS' && task.resultJson) return task.resultJson as unknown as T
    if (task.status === 'FAILED' || task.status === 'CANCELLED') {
      throw new Error(task.errorMessage || 'AI 任务执行失败')
    }
  }
  throw new Error('AI 任务执行超时，请稍后重试')
}

export function guideAchievement(payload: { resumeVersionId: number; section: string; content: string }) {
  return apiClient.post<ApiResponse<AchievementGuidanceResponse>>('/api/ai/achievement-guidance', payload)
}
