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

export interface CareerMaterialSearchItem extends CareerMaterialSummary {
  excerpt: string
}

export type CareerMaterialSort = 'updatedAt,desc' | 'updatedAt,asc' | 'title,asc'

export interface CareerMaterialSearchPage {
  items: CareerMaterialSearchItem[]
  page: number
  size: number
  totalElements: number
  totalPages: number
  typeCounts: Partial<Record<MaterialType, number>>
}

export interface CareerMaterialSearchParams {
  q?: string
  type?: MaterialType
  usagePreference?: UsagePreference
  page?: number
  size?: number
  sort?: CareerMaterialSort
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

export function searchMaterials(params: CareerMaterialSearchParams = {}) {
  return apiClient.get<ApiResponse<CareerMaterialSearchPage>>('/api/career-materials/search', { params })
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
