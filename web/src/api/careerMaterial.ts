import { apiClient, type ApiResponse } from './client'

export type MaterialType =
  | 'WORK_EXPERIENCE'
  | 'PROJECT_EXPERIENCE'
  | 'EDUCATION'
  | 'SKILL'
  | 'CERTIFICATE'
  | 'HIGHLIGHT'
  | 'AWARD'
  | 'ACHIEVEMENT'
  | 'LEADERSHIP_EXPERIENCE'
  | 'SKILL_EVIDENCE'
  | 'VOLUNTEER_EXPERIENCE'
  | 'COURSE'
  | 'PUBLICATION'

export type UsagePreference = 'NORMAL' | 'PREFERRED' | 'EXCLUDED'

export interface CareerMaterialSummary {
  id: number
  materialType: MaterialType
  title: string
  usagePreference: UsagePreference
  updatedAt: string
}

export interface CareerMaterial extends CareerMaterialSummary {
  contentJson: Record<string, unknown>
  sourceText: string | null
  createdAt: string
}

export interface CareerMaterialPayload {
  materialType: MaterialType
  title: string
  contentJson: Record<string, unknown>
  sourceText?: string
  usagePreference?: UsagePreference
}

export function listMaterials(type?: MaterialType) {
  return apiClient.get<ApiResponse<CareerMaterialSummary[]>>('/api/career-materials', { params: { type } })
}

export function getMaterial(id: number) {
  return apiClient.get<ApiResponse<CareerMaterial>>(`/api/career-materials/${id}`)
}

export function createMaterial(payload: CareerMaterialPayload) {
  return apiClient.post<ApiResponse<CareerMaterial>>('/api/career-materials', payload)
}

export function updateMaterial(id: number, payload: CareerMaterialPayload) {
  return apiClient.patch<ApiResponse<CareerMaterial>>(`/api/career-materials/${id}`, payload)
}

export function deleteMaterial(id: number) {
  return apiClient.delete<ApiResponse<void>>(`/api/career-materials/${id}`)
}
