import { http } from './client'
import { newIdempotencyKey } from '@/utils/idempotency'

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
  /**
   * 关键词三档明细（B4）。**旧检查记录为 null**——面板据此隐藏，
   * 不要当成「检查过且全部命中」。
   */
  keywordGap: KeywordGap | null
}

export interface KeywordGap {
  matched: string[]
  partialMatched: string[]
  missing: string[]
  coverage: number
}

/** 解析器视角（B4）：机器读这份简历时看到的线性文本与结构。 */
export interface ParserViewSection {
  code: string
  present: boolean
  itemCount: number
  charCount: number
}

export interface ParserViewWarning {
  code: string
  section: string | null
  count: number | null
}

export interface ParserViewContact {
  name: boolean
  email: boolean
  emailValid: boolean
  phone: boolean
  location: boolean
  linkCount: number
}

export interface ParserViewResponse {
  resumeVersionId: number
  charCount: number
  lineCount: number
  sections: ParserViewSection[]
  contact: ParserViewContact
  warnings: ParserViewWarning[]
  plainText: string
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

export function runAtsCheck(resumeVersionId: number, jobDescriptionId: number, useAi = true, idempotencyKey = newIdempotencyKey()) {
  return http.post<AtsCheckResponse>('/api/ats/check', { resumeVersionId, jobDescriptionId, useAi }, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}

export function getAtsCheck(id: number) {
  return http.get<AtsCheckResponse>(`/api/ats/checks/${id}`)
}

export function retryAtsAi(id: number) {
  return http.post<AtsCheckResponse>(`/api/ats/checks/${id}/ai-retry`)
}

/**
 * 解析器视角诊断（B4）：按版本渲染，与检查记录无关（不需要 checkId、不落库、不调 AI）。
 * 之所以按版本而不是按检查取：用户换 JD 重跑检查时，同一份简历「被机器读到的样子」不会变。
 */
export function getAtsParserView(resumeVersionId: number) {
  return http.get<ParserViewResponse>('/api/ats/parser-view', { params: { resumeVersionId } })
}
