import { apiClient, type ApiResponse } from './client'
import type { AiTask } from './ai'
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

export function generateCommunicationWithAi(resumeVersionId: number, jobDescriptionId: number,
                                            type: CommunicationType, outputLanguage: CommunicationOutputLanguage,
                                            idempotencyKey: string) {
  return apiClient.post<ApiResponse<AiTask>>('/api/communications/ai-generate', {
    resumeVersionId, jobDescriptionId, type, outputLanguage,
  }, { headers: { 'Idempotency-Key': idempotencyKey } })
}

// ==================== 模板库 ====================

export function listTemplates(params?: { scene?: TemplateScene; type?: CommunicationType; outputLanguage?: CommunicationOutputLanguage }) {
  return apiClient.get<ApiResponse<CommunicationTemplateSummary[]>>('/api/communications/templates', { params })
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
