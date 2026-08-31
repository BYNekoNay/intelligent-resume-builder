import { apiClient, type ApiResponse } from './client'
import type { AiTask } from './ai'
export type CommunicationType='COVER_LETTER'|'EMAIL'|'OPENING_MESSAGE'
export type CommunicationOutputLanguage = 'ZH_CN' | 'EN'
export type CommunicationGenerationSource = 'AI' | 'TEMPLATE'

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
