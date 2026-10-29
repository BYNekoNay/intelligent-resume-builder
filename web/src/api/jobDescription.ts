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
