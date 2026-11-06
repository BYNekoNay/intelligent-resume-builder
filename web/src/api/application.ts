/**
 * 投递（求职申请）接口层：对应后端 ApplicationController（/api/applications）。
 * 业务：用户把「用哪份简历投了哪个岗位」登记进来，按 9 个状态（草稿→已投递→筛选中→面试中…）跟进，
 * 本模块负责状态流转、列表/详情、统计、增删改。
 * 边界：列表用 ApplicationSummary（无三段长文本，只有 draftCount），详情才拿 ApplicationRecord 全文；
 * updateApplicationStatus 带 version 做乐观锁（version 是后端每次修改后的递增号，
 * 提交时带旧值，别人改过则对不上，请求以 40901 拒绝——防止两个人/两个标签页互相覆盖状态），
 * feedbackText 只在显式传入时才提交（不传表示保留服务端现值）。
 */
import { http } from './client'

export type ApplicationStatus = 'DRAFT' | 'APPLIED' | 'SCREENING' | 'INTERVIEWING' | 'OFFERED' | 'HIRED' | 'REJECTED' | 'WITHDRAWN' | 'NO_RESPONSE'

export interface ApplicationStatusHistory {
  id: number
  fromStatus: ApplicationStatus | null
  toStatus: ApplicationStatus
  note: string | null
  changedAt: string
}
export type FollowUpFilter = 'ALL' | 'TODAY' | 'OVERDUE'

export interface ApplicationRecord {
  id: number
  jobDescriptionId: number
  resumeVersionId: number
  status: ApplicationStatus
  coverLetterText: string | null
  emailBodyText: string | null
  openingMessageText: string | null
  feedbackText: string | null
  appliedAt: string | null
  nextFollowUpAt: string | null
  version: number
  createdAt: string
  updatedAt: string
  statusHistory?: ApplicationStatusHistory[]
}

/**
 * 投递记录列表摘要（#50）：草稿长文本不进列表（展开/编辑时按需走详情），
 * `draftCount` 支撑卡片上的「n/3」标记；`feedbackText` 仍在列表里——
 * 列表页客户端搜索会匹配备注文本，展开面板的备注框以它为初值。
 * 状态迁移接口为「未发送即保留」语义，无需为防清空而回传现值。
 */
export interface ApplicationSummary {
  id: number
  jobDescriptionId: number
  resumeVersionId: number
  status: ApplicationStatus
  feedbackText: string | null
  draftCount: number
  appliedAt: string | null
  nextFollowUpAt: string | null
  version: number
  createdAt: string
  updatedAt: string
}

export interface ApplicationStats {
  total: number
  byStatus: { status: ApplicationStatus; count: number; percent: number | null }[]
  conversionRates: { appliedToInterviewing: number | null; interviewingToOffered: number | null; appliedToOffered: number | null }
  avgStageDurationDays: { applied: number | null; interviewing: number | null; totalToOffer: number | null }
}

export function listApplications(followUp?: FollowUpFilter) {
  return http.get<ApplicationSummary[]>('/api/applications', { params: { followUp } })
}
export function getApplication(id: number) {
  return http.get<ApplicationRecord>(`/api/applications/${id}`)
}
export interface ApplicationPayload { jobDescriptionId: number; resumeVersionId: number; status: ApplicationStatus; coverLetterText?: string; emailBodyText?: string; openingMessageText?: string; version?: number; nextFollowUpAt?: string | null }
export function createApplication(payload: ApplicationPayload) {
  return http.post<ApplicationRecord>('/api/applications', payload)
}
export function updateApplication(id: number, payload: ApplicationPayload) {
  return http.put<ApplicationRecord>(`/api/applications/${id}`, payload)
}
export function updateApplicationStatus(id: number, status: ApplicationStatus, version: number, feedbackText?: string, note?: string) {
  return http.patch<ApplicationRecord>(`/api/applications/${id}/status`, { status, version, ...(feedbackText !== undefined ? { feedbackText } : {}), ...(note !== undefined ? { note } : {}) })
}
export function deleteApplication(id: number) { return http.delete<void>(`/api/applications/${id}`) }
export function getApplicationStats() {
  return http.get<ApplicationStats>('/api/applications/stats')
}
