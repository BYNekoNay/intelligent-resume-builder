import { apiClient, type ApiResponse } from './client'

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
  const empty = emptyPersonalProfile()
  return {
    ...empty,
    ...value,
    targetRoleTitles: Array.isArray(value?.targetRoleTitles) ? value.targetRoleTitles : [],
    targetIndustries: Array.isArray(value?.targetIndustries) ? value.targetIndustries : [],
    targetWorkPreferences: Array.isArray(value?.targetWorkPreferences) ? value.targetWorkPreferences : [],
  }
}

export function getPersonalProfile() {
  return apiClient.get<ApiResponse<PersonalProfile | null>>('/api/personal-profile')
}

export function updatePersonalProfile(payload: PersonalProfile) {
  return apiClient.put<ApiResponse<PersonalProfile>>('/api/personal-profile', payload)
}

export function getPersonalProfileImportSuggestion(resumeId: number) {
  return apiClient.get<ApiResponse<PersonalProfile>>('/api/personal-profile/import-suggestion', {
    params: { resumeId },
  })
}
