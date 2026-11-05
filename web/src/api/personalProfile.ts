/**
 * 个人档案（求职画像）接口层：单条用户级记录，GET 可能返回 null 表示从未填写。
 * 边界：normalizePersonalProfile 负责把 null/缺字段的响应补成完整结构，调用方可以放心绑定表单；
 * getPersonalProfileImportSuggestion 只做「从某份简历生成建议值」，不写库，保存仍走 updatePersonalProfile。
 */
import { http } from './client'

export interface PersonalProfile {
  fullName: string
  email: string
  phone: string
  location: string
  website: string
  profileSummary: string
  targetRoleTitles: string[]
  targetSeniority: string
  targetIndustries: string[]
  targetWorkPreferences: string[]
  careerPositioningSummary: string
}

export const emptyPersonalProfile = (): PersonalProfile => ({
  fullName: '',
  email: '',
  phone: '',
  location: '',
  website: '',
  profileSummary: '',
  targetRoleTitles: [],
  targetSeniority: '',
  targetIndustries: [],
  targetWorkPreferences: [],
  careerPositioningSummary: '',
})

export function normalizePersonalProfile(value: Partial<PersonalProfile> | null | undefined): PersonalProfile {
  return {
    fullName: value?.fullName ?? '',
    email: value?.email ?? '',
    phone: value?.phone ?? '',
    location: value?.location ?? '',
    website: value?.website ?? '',
    profileSummary: value?.profileSummary ?? '',
    targetRoleTitles: Array.isArray(value?.targetRoleTitles) ? value.targetRoleTitles : [],
    targetSeniority: value?.targetSeniority ?? '',
    targetIndustries: Array.isArray(value?.targetIndustries) ? value.targetIndustries : [],
    targetWorkPreferences: Array.isArray(value?.targetWorkPreferences) ? value.targetWorkPreferences : [],
    careerPositioningSummary: value?.careerPositioningSummary ?? '',
  }
}

export function getPersonalProfile() {
  return http.get<PersonalProfile | null>('/api/personal-profile')
}

export function updatePersonalProfile(payload: PersonalProfile) {
  return http.put<PersonalProfile>('/api/personal-profile', payload)
}

export function getPersonalProfileImportSuggestion(resumeId: number) {
  return http.get<PersonalProfile>('/api/personal-profile/import-suggestion', {
    params: { resumeId },
  })
}
