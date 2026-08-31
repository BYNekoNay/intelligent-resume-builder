import type { MaterialType, UsagePreference } from '@/api/careerMaterial'

export const MATERIAL_TYPE_OPTIONS: { value: MaterialType; key: string }[] = [
  { value: 'WORK_EXPERIENCE', key: 'careerMaterial.filterWorkExperience' },
  { value: 'PROJECT_EXPERIENCE', key: 'careerMaterial.filterProjectExperience' },
  { value: 'ACHIEVEMENT', key: 'careerMaterial.filterAchievement' },
  { value: 'LEADERSHIP_EXPERIENCE', key: 'careerMaterial.filterLeadershipExperience' },
  { value: 'SKILL_EVIDENCE', key: 'careerMaterial.filterSkillEvidence' },
  { value: 'EDUCATION', key: 'careerMaterial.filterEducation' },
  { value: 'SKILL', key: 'careerMaterial.filterSkill' },
  { value: 'CERTIFICATE', key: 'careerMaterial.filterCertificate' },
  { value: 'AWARD', key: 'careerMaterial.filterAward' },
  { value: 'HIGHLIGHT', key: 'careerMaterial.filterHighlight' },
  { value: 'VOLUNTEER_EXPERIENCE', key: 'careerMaterial.filterVolunteerExperience' },
  { value: 'COURSE', key: 'careerMaterial.filterCourse' },
  { value: 'PUBLICATION', key: 'careerMaterial.filterPublication' },
]

export const USAGE_OPTIONS: { value: UsagePreference; key: string }[] = [
  { value: 'NORMAL', key: 'careerMaterial.usageNormal' },
  { value: 'PREFERRED', key: 'careerMaterial.usagePreferred' },
  { value: 'EXCLUDED', key: 'careerMaterial.usageExcluded' },
]

export function optionLabel(
  options: { value: string; key: string }[],
  value: string,
  translate: (key: string) => string,
) {
  const option = options.find(candidate => candidate.value === value)
  return option ? translate(option.key) : value
}
