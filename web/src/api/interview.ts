/**
 * 模拟面试接口层：对应后端 InterviewController（/api/interviews）。
 * 边界：startInterview 与 answerInterview 是同步长请求（服务端现场生成题目/评估回答），
 * 显式放宽到 60s，且都注入 Idempotency-Key 防重复提交；轮次推进状态由服务端机管理，
 * 前端只读 InterviewStateResponse。AI 失败不走业务码，而是看 aiFailure 字段决定 retryAi 或降级 continueWithRules。
 * 错误码语义：40901 覆盖幂等键冲突、非法状态迁移（如当前状态不允许重试/降级、面试未完成不能出报告）；
 * 50002（AI_FAILURE）表示 AI 调用失败（可重试）。
 */
import { http } from './client'
import type { AiTask, AiTaskPreview } from './ai'

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
  interviewMode: 'JD_TARGETED' | 'TECHNICAL' | 'BEHAVIORAL' | 'COMPREHENSIVE' | null
  outputLanguage: 'ZH_CN' | 'EN' | null
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
  return http.post<InterviewStateResponse>('/api/interviews/start', payload, {
    headers: { 'Idempotency-Key': idempotencyKey },
    timeout: 60_000,
  })
}

export function getInterviewState(id: number) {
  return http.get<InterviewStateResponse>(`/api/interviews/${id}`)
}

export function answerInterview(id: number, answer: string, idempotencyKey: string) {
  return http.post<InterviewStateResponse>(`/api/interviews/${id}/answer`, { answer }, {
    headers: { 'Idempotency-Key': idempotencyKey },
    timeout: 60_000,
  })
}

export function retryAi(id: number) {
  return http.post<InterviewStateResponse>(`/api/interviews/${id}/ai/retry`, null, {
    timeout: 60_000,
  })
}

export function continueWithRules(id: number) {
  return http.post<InterviewStateResponse>(`/api/interviews/${id}/continue-with-rules`)
}

export function finishInterview(id: number) {
  return http.post<InterviewStateResponse>(`/api/interviews/${id}/finish`)
}

export function getInterviewReport(id: number) {
  return http.get<InterviewReportResponse>(`/api/interviews/${id}/report`)
}

export function listInterviewHistory(params?: { jobDescriptionId?: number }) {
  return http.get<InterviewSessionSummary[]>('/api/interviews', { params })
}

/**
 * 追问练习的发送前预览（R32）。只读：不落库、不调模型。
 *
 * <p>该领域端点的 `includeContact` 由服务端固定为 false，所以 fetcher 拿到的入参
 * **刻意忽略**、也不往 body 里塞字段 —— 多带一个字段就会与服务端自算的载荷漂移（→ 40901）。
 */
export function previewFollowUp(id: number, weakness: string) {
  return http.post<AiTaskPreview>(`/api/interviews/${id}/follow-up/preview`, { weakness })
}

export function createFollowUp(id: number, weakness: string, idempotencyKey: string, previewToken: string) {
  return http.post<AiTask>(`/api/interviews/${id}/follow-up`, { weakness }, {
    headers: { 'Idempotency-Key': idempotencyKey, 'X-AI-Preview-Token': previewToken },
  })
}
