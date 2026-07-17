import { apiClient, type ApiResponse } from './client'

export type MaterialType =
  | 'WORK_EXPERIENCE'
  | 'PROJECT_EXPERIENCE'
  | 'EDUCATION'
  | 'SKILL'
  | 'CERTIFICATE'
  | 'AWARD'

export type UsagePreference = 'NORMAL' | 'PREFERRED' | 'EXCLUDED'

export interface CareerMaterial {
  id: number
  materialType: MaterialType
  title: string
  contentJson: Record<string, unknown>
  sourceText: string | null
  usagePreference: UsagePreference
  createdAt: string
  updatedAt: string
}

export interface CareerMaterialPayload {
  materialType: MaterialType
  title: string
  contentJson: Record<string, unknown>
  sourceText?: string
  usagePreference?: UsagePreference
}

export function listMaterials(type?: MaterialType) {
  return apiClient.get<ApiResponse<CareerMaterial[]>>('/api/career-materials', { params: { type } })
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