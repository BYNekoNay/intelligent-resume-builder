import { apiClient, type ApiResponse } from './client'
import type { AiTask, AiTaskPreview } from './ai'
export type CommunicationType='COVER_LETTER'|'EMAIL'|'OPENING_MESSAGE'
export type CommunicationOutputLanguage = 'ZH_CN' | 'EN'
export type CommunicationGenerationSource = 'AI' | 'TEMPLATE'
export type TemplateScene = 'FOLLOW_UP' | 'THANK_YOU' | 'SALARY' | 'DECLINE' | 'GENERAL'

export interface CommunicationResult {
  type: CommunicationType
  draft: string
  sentAutomatically: boolean
  requiresManualConfirmation: boolean
  generationSource: CommunicationGenerationSource
}

export interface CommunicationAiResult {
  type: CommunicationType
  subject: string | null
  body: string
  draft: string
  generationSource: 'AI'
  communicationDraftId: number
  resumeVersionId: number
  jobDescriptionId: number
  promptVersion: string
}

export interface CommunicationTemplateSummary {
  id: number
  scene: TemplateScene
  type: CommunicationType
  outputLanguage: CommunicationOutputLanguage
  name: string
  description: string | null
  isSystem: boolean
  usageCount: number
}

export interface CommunicationTemplateDetail extends CommunicationTemplateSummary {
  bodyText: string
}

export interface TemplatePreview {
  id: number
  name: string
  scene: TemplateScene
  type: CommunicationType
  filledBody: string
  missingPlaceholders: string[]
}

export interface TemplatePayload {
  name: string
  scene: TemplateScene
  type: CommunicationType
  bodyText: string
  description?: string
  outputLanguage?: CommunicationOutputLanguage
}

export function generateCommunication(resumeVersionId: number, jobDescriptionId: number,
                                      type: CommunicationType, outputLanguage: CommunicationOutputLanguage) {
  return apiClient.post<ApiResponse<CommunicationResult>>('/api/communications/generate', {
    resumeVersionId, jobDescriptionId, type, outputLanguage,
  })
}

/** AI 文案创建的请求参数（R32）。预览与创建共用同一份，见 {@link communicationAiBody}。 */
export interface CommunicationAiPayload {
  resumeVersionId: number
  jobDescriptionId: number
  type: CommunicationType
  outputLanguage: CommunicationOutputLanguage
}

/** ai-generate 的请求体，**预览与创建共用同一份**（字段漂移 → 令牌对不上 → 40901）。 */
function communicationAiBody(payload: CommunicationAiPayload) {
  return {
    resumeVersionId: payload.resumeVersionId,
    jobDescriptionId: payload.jobDescriptionId,
    type: payload.type,
    outputLanguage: payload.outputLanguage,
  }
}

/** AI 文案的发送前预览（R32）。只读：不落库、不调模型。 */
export function previewCommunicationAi(payload: CommunicationAiPayload) {
  return apiClient
    .post<ApiResponse<AiTaskPreview>>('/api/communications/ai-generate/preview', communicationAiBody(payload))
    .then(response => response.data.data)
}

export function generateCommunicationWithAi(
  payload: CommunicationAiPayload,
  idempotencyKey: string,
  previewToken: string,
) {
  return apiClient.post<ApiResponse<AiTask>>('/api/communications/ai-generate', communicationAiBody(payload), {
    headers: { 'Idempotency-Key': idempotencyKey, 'X-AI-Preview-Token': previewToken },
  })
}

// ==================== 模板库 ====================

export function listTemplates(params?: { scene?: TemplateScene; type?: CommunicationType; outputLanguage?: CommunicationOutputLanguage }) {
  return apiClient.get<ApiResponse<CommunicationTemplateSummary[]>>('/api/communications/templates', { params })
}

export function getTemplate(id: number) {
  return apiClient.get<ApiResponse<CommunicationTemplateDetail>>(`/api/communications/templates/${id}`)
}

export function previewTemplate(id: number, resumeVersionId: number, jobDescriptionId: number) {
  return apiClient.get<ApiResponse<TemplatePreview>>(`/api/communications/templates/${id}/preview`, {
    params: { resumeVersionId, jobDescriptionId },
  })
}

export function createTemplate(payload: TemplatePayload) {
  return apiClient.post<ApiResponse<CommunicationTemplateSummary>>('/api/communications/templates', payload)
}

export function updateTemplate(id: number, payload: TemplatePayload) {
  return apiClient.put<ApiResponse<CommunicationTemplateSummary>>(`/api/communications/templates/${id}`, payload)
}

export function deleteTemplate(id: number) {
  return apiClient.delete<ApiResponse<void>>(`/api/communications/templates/${id}`)
}

// ==================== 草稿（确认保存，绝不发送） ====================

export function saveCommunicationDraft(payload: {
  resumeVersionId: number
  jobDescriptionId: number
  type: CommunicationType
  draftText: string
  templateId?: number
}) {
  return apiClient.post<ApiResponse<CommunicationResult>>('/api/communications/drafts', payload)
}
