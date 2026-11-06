/**
 * 职位描述（JD）接口层：对应后端 JobDescriptionController（/api/jobs）。
 * 业务：用户粘贴目标岗位的招聘要求，供生成、匹配打分、面试等流程引用；parseJob 触发服务端
 * 关键词解析，解析出的结构化关键词供 ATS 检查与匹配使用。
 * 边界：只做请求封装与类型定义。摘要（JobDescriptionSummary）只带 jdTextPreview，不含全文，
 * 所以编辑/展示全文必须再调 getJob。
 * parseJob 是 POST 触发的服务端解析，返回更新后的详情，调用方需自行刷新列表以更新 parsedAt 状态。
 */
import { http } from './client'

interface JobDescriptionBase {
  id: number
  title: string
  companyName: string | null
  parsedAt: string | null
  updatedAt: string
}

export interface JobDescriptionSummary extends JobDescriptionBase {
  jdTextPreview: string
}

export interface JobDescriptionDetail extends JobDescriptionBase {
  jdText: string
  parsedKeywordsJson: Record<string, unknown> | null
  parsedAt: string | null
  parsedVersion: string | null
  createdAt: string
}

export interface JobDescriptionReference {
  id: number
  title: string
  companyName: string | null
}

export interface JobDescriptionPayload {
  title: string
  companyName?: string
  jdText: string
}

export function listJobs() {
  return http.get<JobDescriptionSummary[]>('/api/jobs')
}

export function getJob(id: number) {
  return http.get<JobDescriptionDetail>(`/api/jobs/${id}`)
}

export function getJobReference(id: number) {
  return http.get<JobDescriptionReference>(`/api/jobs/${id}/reference`)
}

export function createJob(payload: JobDescriptionPayload) {
  return http.post<JobDescriptionDetail>('/api/jobs', payload)
}

export function updateJob(id: number, payload: JobDescriptionPayload) {
  return http.patch<JobDescriptionDetail>(`/api/jobs/${id}`, payload)
}

export function parseJob(id: number) {
  return http.post<JobDescriptionDetail>(`/api/jobs/${id}/parse`)
}

export function deleteJob(id: number) {
  return http.delete<void>(`/api/jobs/${id}`)
}
