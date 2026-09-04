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
