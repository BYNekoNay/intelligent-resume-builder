import { apiClient, type ApiResponse } from './client'

// ==================== 请求类型 ====================

export interface StartInterviewRequest {
  sourceType: 'PLATFORM_RESUME' | 'EXTERNAL_RESUME'
  resumeVersionId?: number
  externalResumeText?: string
  jobDescriptionId?: number
  interviewMode: 'JD_TARGETED' | 'TECHNICAL' | 'BEHAVIORAL' | 'COMPREHENSIVE'
  targetQuestionCount?: number
  outputLanguage: 'ZH_CN' | 'EN'
  /** 可选：提供时跳过 AI 首题生成，会话直接进入 AWAITING_ANSWER（用于薄弱项练习） */
  initialQuestion?: string
}

// ==================== 响应类型 ====================

export interface DimensionScores {
  relevance: number
  evidenceSpecificity: number
  structureClarity: number
  roleCompetency: number
  authenticityReflection: number
}

export interface LastEvaluation {
  recordId: number
  roundNo: number
  questionText: string
  answerText: string
  roundScore: number
  dimensionScores: DimensionScores | null
  evaluationSource: 'AI' | 'RULE' | null
  strengths: string[]
  improvements: string[]
  suggestedAnswer: string | null
}

export interface AiFailureInfo {
  operationId: number
  stage: string
  retryable: boolean
  reauthorizationRequired: boolean
  messageCode: string
}

export interface InterviewStateResponse {
  interviewId: number
  status: 'GENERATING_QUESTION' | 'AWAITING_ANSWER' | 'EVALUATING_ANSWER' | 'AI_ACTION_REQUIRED' | 'COMPLETED'
  executionMode: 'AI' | 'RULE'
  currentQuestion: string | null
  currentQuestionNo: number | null
  completedQuestionCount: number
  targetQuestionCount: number
  minQuestionCount: number
  maxQuestionCount: number
  lastEvaluation: LastEvaluation | null
  aiFailure: AiFailureInfo | null
  completionReason: string | null
  sourceType: 'PLATFORM_RESUME' | 'EXTERNAL_RESUME' | null
  resumeVersionId: number | null
  jobDescriptionId: number | null
}

export interface RoundDetail {
  recordId: number
  roundNo: number
  questionText: string
  answerText: string
  roundScore: number
  dimensionScores: DimensionScores | null
  strengths: string[]
  improvements: string[]
  suggestedAnswer: string | null
  evaluationSource: 'AI' | 'RULE' | null
}

export interface InterviewReportResponse {
  totalScore: number
  summary: string
  strengths: string[]
  weaknesses: string[]
  resumeSuggestions: string[]
  expressionSuggestions: string[]
  dimensionScores: DimensionScores | null
  targetQuestionCount: number
  actualQuestionCount: number
  completionReason: string | null
  evaluationSource: 'AI' | 'RULE' | 'MIXED' | null
  aiEvaluatedRounds: number
  ruleEvaluatedRounds: number
  rounds: RoundDetail[]
}

export interface InterviewSessionSummary {
  id: number
  jobDescriptionId: number | null
  resumeVersionId: number | null
  sourceType: 'PLATFORM_RESUME' | 'EXTERNAL_RESUME'
  interviewMode: string
  executionMode: 'AI' | 'RULE' | null
  completionReason: string | null
  targetQuestionCount: number
  actualQuestionCount: number
  totalScore: number
  createdAt: string
  updatedAt: string
}

export interface FollowUpCandidate {
  question: string
  focus: string
  expectedSignals: string[]
  coverageTags: string[]
}

export interface FollowUpPracticeResult {
  operation: 'FOLLOW_UP_PRACTICE'
  promptVersion: string
  weakness: string
  candidates: FollowUpCandidate[]
}

// ==================== API 函数 ====================

export function startInterview(payload: StartInterviewRequest, idempotencyKey: string) {
  return apiClient.post<ApiResponse<InterviewStateResponse>>('/api/interviews/start', payload, {
    headers: { 'Idempotency-Key': idempotencyKey },
    timeout: 60_000,
  })
}

export function getInterviewState(id: number) {
  return apiClient.get<ApiResponse<InterviewStateResponse>>(`/api/interviews/${id}`)
}

export function answerInterview(id: number, answer: string, idempotencyKey: string) {
  return apiClient.post<ApiResponse<InterviewStateResponse>>(`/api/interviews/${id}/answer`, { answer }, {
    headers: { 'Idempotency-Key': idempotencyKey },
    timeout: 60_000,
  })
}

export function retryAi(id: number) {
  return apiClient.post<ApiResponse<InterviewStateResponse>>(`/api/interviews/${id}/ai/retry`, null, {
    timeout: 60_000,
  })
}

export function continueWithRules(id: number) {
  return apiClient.post<ApiResponse<InterviewStateResponse>>(`/api/interviews/${id}/continue-with-rules`)
}

export function finishInterview(id: number) {
  return apiClient.post<ApiResponse<InterviewStateResponse>>(`/api/interviews/${id}/finish`)
}

export function getInterviewReport(id: number) {
  return apiClient.get<ApiResponse<InterviewReportResponse>>(`/api/interviews/${id}/report`)
}

export function listInterviewHistory(params?: { jobDescriptionId?: number }) {
  return apiClient.get<ApiResponse<InterviewSessionSummary[]>>('/api/interviews', { params })
}

export function createFollowUp(id: number, weakness: string, idempotencyKey: string) {
  return apiClient.post<ApiResponse<import('./ai').AiTask>>(`/api/interviews/${id}/follow-up`, { weakness }, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
}
