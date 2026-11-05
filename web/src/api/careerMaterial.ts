/**
 * 职业素材库（career materials）接口层。
 * 边界：只定义素材的数据形状与 /api/career-materials 系列 HTTP 调用，不做缓存和状态管理（分页/筛选状态属于调用方）。
 * listMaterials 只返回摘要（不含 contentJson），需要正文时用 getMaterial 单独取；searchMaterials 提供分页 + 关键词 + 类型统计。
 */
import { http } from './client'

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
  evidenceReady: boolean
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
  return http.get<CareerMaterialSummary[]>('/api/career-materials', { params: { type } })
}

export function searchMaterials(params: CareerMaterialSearchParams = {}) {
  return http.get<CareerMaterialSearchPage>('/api/career-materials/search', { params })
}

export function getMaterial(id: number) {
  return http.get<CareerMaterial>(`/api/career-materials/${id}`)
}

export function createMaterial(payload: CareerMaterialPayload) {
  return http.post<CareerMaterial>('/api/career-materials', payload)
}

export function updateMaterial(id: number, payload: CareerMaterialPayload) {
  return http.patch<CareerMaterial>(`/api/career-materials/${id}`, payload)
}

export function deleteMaterial(id: number) {
  return http.delete<void>(`/api/career-materials/${id}`)
}
