import { apiClient, type ApiResponse } from './client'

export interface AtsCheckResponse {
  id: number
  resumeId: number | null
  resumeVersionId: number
  jobDescriptionId: number
  totalScore: number
  checks: Record<string, unknown>
  passedChecks: string[]
  risks: string[]
  priorities: string[]
  disclaimer: string
  analysisStatus: 'ANALYZING' | 'COMPLETED' | 'RULES_ONLY' | 'RULES_FALLBACK'
  analysisSource: 'RULES' | 'HYBRID'
  aiTaskId: number | null
  aiInsights: AtsAiInsights | null
  fallback: AtsFallbackInfo | null
}

export interface AtsAiInsights {
  summary: string
  semanticCoverage: Array<{ requirement: string; status: 'MATCHED' | 'PARTIAL' | 'MISSING'; evidence: string | null; reason: string }>
  evidenceFindings: Array<{ section: string; quote: string | null; assessment: string; suggestion: string }>
  readabilityRisks: string[]
  prioritizedActions: Array<{ priority: 'P0' | 'P1' | 'P2'; section: string; action: string; basis: string }>
  confidence: 'LOW' | 'MEDIUM' | 'HIGH'
}

export interface AtsFallbackInfo {
  code: 'AI_DISABLED' | 'CONSENT_REQUIRED' | 'QUOTA_EXCEEDED' | 'PROVIDER_TIMEOUT' | 'PROVIDER_ERROR' | 'INVALID_RESPONSE' | 'UNKNOWN'
  message: string
  retryable: boolean
  consentRequired: boolean
}

export function runAtsCheck(resumeVersionId: number, jobDescriptionId: number, useAi = true, idempotencyKey = crypto.randomUUID()) {
  return apiClient.post<ApiResponse<AtsCheckResponse>>('/api/ats/check', { resumeVersionId, jobDescriptionId, useAi }, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function getAtsCheck(id: number) {
  return apiClient.get<ApiResponse<AtsCheckResponse>>(`/api/ats/checks/${id}`)
}

export function retryAtsAi(id: number) {
  return apiClient.post<ApiResponse<AtsCheckResponse>>(`/api/ats/checks/${id}/ai-retry`)
}
