<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Database, Download, Pencil, Plus, Save, SlidersHorizontal, Target, Trash2, UserRound } from 'lucide-vue-next'
import { useCareerMaterialStore } from '@/stores/careerMaterial'
import { createMaterial, deleteMaterial, getMaterial, listMaterials, updateMaterial, type CareerMaterial, type CareerMaterialSummary, type MaterialType, type UsagePreference } from '@/api/careerMaterial'
import { emptyPersonalProfile, getPersonalProfile, getPersonalProfileImportSuggestion, normalizePersonalProfile, updatePersonalProfile, type PersonalProfile } from '@/api/personalProfile'
import { listResumes, type ResumeSummary } from '@/api/resume'
import { useLocale } from '@/i18n'

type MetricDisplayMode = 'EXACT' | 'RANGE' | 'QUALITATIVE'

interface SpecializedForm {
  relatedMaterialId: number | null
  relatedMaterialIds: number[]
  scenario: string
  action: string
  outcome: string
  period: string
  metricName: string
  metricDisplayMode: MetricDisplayMode
  metricDisplayValue: string
  metricExactValue: string
  responsibilityScope: string
  collaborationTargets: string
  teamSize: string
  crossFunctionalRelationship: string
  keyDecision: string
  result: string
  skillName: string
  category: string
  proficiency: string
  yearsOfExperience: string
  lastUsedAt: string
  applicationDescription: string
  outcomeEvidence: string
}

const { t } = useLocale()
const store = useCareerMaterialStore()
const materialType = ref<MaterialType>('WORK_EXPERIENCE')
const title = ref('')
const sourceText = ref('')
const contentJson = ref('')
const usagePreference = ref<UsagePreference>('NORMAL')
const filterType = ref<'' | MaterialType>('')
const editingId = ref<number | null>(null)
const loadingDetail = ref(false)
const saving = ref(false)
const error = ref('')
const profile = ref<PersonalProfile>(emptyPersonalProfile())
const resumes = ref<ResumeSummary[]>([])
const importResumeId = ref<number | null>(null)
const profileLoading = ref(true)
const profileSaving = ref(false)
const profileMessage = ref('')
const relationMaterials = ref<CareerMaterialSummary[]>([])
const materialDetails = ref<Record<number, CareerMaterial>>({})

const TYPE_OPTIONS: { value: MaterialType; key: string }[] = [
  { value: 'WORK_EXPERIENCE', key: 'careerMaterial.filterWorkExperience' },
  { value: 'PROJECT_EXPERIENCE', key: 'careerMaterial.filterProjectExperience' },
  { value: 'EDUCATION', key: 'careerMaterial.filterEducation' },
  { value: 'SKILL', key: 'careerMaterial.filterSkill' },
  { value: 'CERTIFICATE', key: 'careerMaterial.filterCertificate' },
  { value: 'HIGHLIGHT', key: 'careerMaterial.filterHighlight' },
  { value: 'AWARD', key: 'careerMaterial.filterAward' },
  { value: 'ACHIEVEMENT', key: 'careerMaterial.filterAchievement' },
  { value: 'LEADERSHIP_EXPERIENCE', key: 'careerMaterial.filterLeadershipExperience' },
  { value: 'SKILL_EVIDENCE', key: 'careerMaterial.filterSkillEvidence' },
]

const USAGE_OPTIONS: { value: UsagePreference; key: string }[] = [
  { value: 'NORMAL', key: 'careerMaterial.usageNormal' },
  { value: 'PREFERRED', key: 'careerMaterial.usagePreferred' },
  { value: 'EXCLUDED', key: 'careerMaterial.usageExcluded' },
]

function emptySpecializedForm(): SpecializedForm {
  return {
    relatedMaterialId: null, relatedMaterialIds: [], scenario: '', action: '', outcome: '', period: '', metricName: '',
    metricDisplayMode: 'QUALITATIVE', metricDisplayValue: '', metricExactValue: '', responsibilityScope: '', collaborationTargets: '',
    teamSize: '', crossFunctionalRelationship: '', keyDecision: '', result: '', skillName: '', category: '', proficiency: '',
    yearsOfExperience: '', lastUsedAt: '', applicationDescription: '', outcomeEvidence: '',
  }
}

function normalizeProficiency(value: string) {
  return ({ BASIC: '了解', PROFICIENT: '熟练', EXPERT: '精通' } as Record<string, string>)[value] ?? value
}

const specializedForm = ref<SpecializedForm>(emptySpecializedForm())
const isSpecialized = computed((): boolean => ['ACHIEVEMENT', 'LEADERSHIP_EXPERIENCE', 'SKILL_EVIDENCE'].includes(materialType.value))
const isAchievement = computed(() => materialType.value === 'ACHIEVEMENT')
const isLeadership = computed(() => materialType.value === 'LEADERSHIP_EXPERIENCE')
const isSkillEvidence = computed(() => materialType.value === 'SKILL_EVIDENCE')
const targetRolesText = computed({ get: () => profile.value.targetRoleTitles.join('、'), set: value => { profile.value.targetRoleTitles = splitList(value) } })
const preferredIndustriesText = computed({ get: () => profile.value.targetIndustries.join('、'), set: value => { profile.value.targetIndustries = splitList(value) } })
const preferredLocationsText = computed({ get: () => profile.value.targetWorkPreferences.join('、'), set: value => { profile.value.targetWorkPreferences = splitList(value) } })

function splitList(value: string) {
  return value.split(/[、,，\n]/).map(item => item.trim()).filter(Boolean)
}

function typeLabel(type: MaterialType) {
  const option = TYPE_OPTIONS.find(candidate => candidate.value === type)
  return option ? t(option.key) : type
}

function usageLabel(preference: UsagePreference) {
  const option = USAGE_OPTIONS.find(candidate => candidate.value === preference)
  return option ? t(option.key) : preference
}

function proficiencyLabel(value: string) {
  const key = ({
    '了解': 'careerMaterial.proficiencyBasic',
    BASIC: 'careerMaterial.proficiencyBasic',
    '熟练': 'careerMaterial.proficiencyProficient',
    PROFICIENT: 'careerMaterial.proficiencyProficient',
    '精通': 'careerMaterial.proficiencyExpert',
    EXPERT: 'careerMaterial.proficiencyExpert',
  } as Record<string, string>)[value]
  return key ? t(key) : value
}

function objectValue(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}
}

function stringValue(value: unknown) { return typeof value === 'string' ? value : '' }
function numberValue(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value : null }
function numberList(value: unknown) { return Array.isArray(value) ? value.filter((item): item is number => typeof item === 'number') : [] }

function loadSpecializedForm(content: Record<string, unknown>) {
  const value = objectValue(content)
  specializedForm.value = {
    ...emptySpecializedForm(),
    relatedMaterialId: numberValue(value.relatedMaterialId),
    relatedMaterialIds: numberList(value.relatedMaterialIds),
    scenario: stringValue(value.scenario), action: stringValue(value.action), outcome: stringValue(value.outcome), period: stringValue(value.period),
    metricName: stringValue(value.metricName), metricDisplayMode: ['EXACT', 'RANGE', 'QUALITATIVE'].includes(stringValue(value.metricDisplayMode)) ? stringValue(value.metricDisplayMode) as MetricDisplayMode : 'QUALITATIVE',
    metricDisplayValue: stringValue(value.metricDisplayValue), metricExactValue: stringValue(value.metricExactValue),
    responsibilityScope: stringValue(value.responsibilityScope), collaborationTargets: stringValue(value.collaborationTargets), teamSize: stringValue(value.teamSize),
    crossFunctionalRelationship: stringValue(value.crossFunctionalRelationship), keyDecision: stringValue(value.keyDecision), result: stringValue(value.result),
    skillName: stringValue(value.skillName), category: stringValue(value.category), proficiency: normalizeProficiency(stringValue(value.proficiency)),
    yearsOfExperience: stringValue(value.yearsOfExperience), lastUsedAt: stringValue(value.lastUsedAt),
    applicationDescription: stringValue(value.applicationDescription), outcomeEvidence: stringValue(value.outcomeEvidence),
  }
}

function buildSpecializedContent(): Record<string, unknown> {
  const form = specializedForm.value
  if (isAchievement.value) return {
    relatedMaterialId: form.relatedMaterialId, scenario: form.scenario, action: form.action, outcome: form.outcome, period: form.period,
    metricName: form.metricName, metricDisplayMode: form.metricDisplayMode, metricDisplayValue: form.metricDisplayValue,
    ...(form.metricDisplayMode === 'EXACT' ? { metricExactValue: form.metricExactValue } : {}),
  }
  if (isLeadership.value) return {
    relatedMaterialId: form.relatedMaterialId, responsibilityScope: form.responsibilityScope, collaborationTargets: form.collaborationTargets,
    teamSize: form.teamSize, crossFunctionalRelationship: form.crossFunctionalRelationship, keyDecision: form.keyDecision, result: form.result,
  }
  return {
    skillName: form.skillName, category: form.category, proficiency: form.proficiency, yearsOfExperience: form.yearsOfExperience,
    lastUsedAt: form.lastUsedAt, relatedMaterialIds: form.relatedMaterialIds, applicationDescription: form.applicationDescription, outcomeEvidence: form.outcomeEvidence,
  }
}

onMounted(async () => {
  await Promise.all([reload(), loadProfile(), loadResumes(), loadRelationMaterials()])
})

async function loadProfile() {
  profileLoading.value = true
  try { profile.value = normalizePersonalProfile((await getPersonalProfile()).data.data) }
  catch { profileMessage.value = t('careerMaterial.profileLoadError') }
  finally { profileLoading.value = false }
}

async function loadResumes() {
  try { resumes.value = (await listResumes()).data.data } catch { resumes.value = [] }
}

async function loadRelationMaterials() {
  try {
    const [work, projects] = await Promise.all([listMaterials('WORK_EXPERIENCE'), listMaterials('PROJECT_EXPERIENCE')])
    relationMaterials.value = [...work.data.data, ...projects.data.data]
  } catch { relationMaterials.value = [] }
}

async function saveProfile() {
  profileSaving.value = true; profileMessage.value = ''
  try { profile.value = normalizePersonalProfile((await updatePersonalProfile(profile.value)).data.data); profileMessage.value = t('careerMaterial.profileSaved') }
  catch { profileMessage.value = t('careerMaterial.profileSaveError') }
  finally { profileSaving.value = false }
}

async function importProfileSuggestion() {
  if (!importResumeId.value) return
  profileLoading.value = true; profileMessage.value = ''
  try { profile.value = normalizePersonalProfile((await getPersonalProfileImportSuggestion(importResumeId.value)).data.data); profileMessage.value = t('careerMaterial.profileImportedHint') }
  catch { profileMessage.value = t('careerMaterial.profileImportError') }
  finally { profileLoading.value = false }
}

function resetForm() {
  editingId.value = null; materialType.value = 'WORK_EXPERIENCE'; title.value = ''; sourceText.value = ''; contentJson.value = ''
  usagePreference.value = 'NORMAL'; specializedForm.value = emptySpecializedForm()
}

function startNewType() {
  if (!editingId.value) specializedForm.value = emptySpecializedForm()
}

async function edit(summary: CareerMaterialSummary) {
  if (loadingDetail.value) return
  loadingDetail.value = true; error.value = ''
  try {
    const material = (await getMaterial(summary.id)).data.data
    materialDetails.value[material.id] = material
    editingId.value = material.id; materialType.value = material.materialType; title.value = material.title; sourceText.value = material.sourceText ?? ''; usagePreference.value = material.usagePreference
    if (['ACHIEVEMENT', 'LEADERSHIP_EXPERIENCE', 'SKILL_EVIDENCE'].includes(material.materialType)) { contentJson.value = ''; loadSpecializedForm(material.contentJson) }
    else contentJson.value = JSON.stringify(material.contentJson, null, 2)
  } catch { error.value = t('careerMaterial.loadDetailError') }
  finally { loadingDetail.value = false }
}

async function loadPreviews() {
  const details = await Promise.all(store.items.map(async item => {
    try { return (await getMaterial(item.id)).data.data } catch { return null }
  }))
  materialDetails.value = Object.fromEntries(details.filter((item): item is CareerMaterial => item !== null).map(item => [item.id, item]))
}

async function reload() {
  await store.load(filterType.value || undefined)
  await loadPreviews()
}

async function create() {
  let parsedContent: Record<string, unknown>
  try {
    parsedContent = isSpecialized.value
      ? buildSpecializedContent()
      : contentJson.value.trim() ? JSON.parse(contentJson.value) as Record<string, unknown> : { title: title.value, sourceText: sourceText.value }
  } catch { error.value = t('careerMaterial.invalidJson'); return }
  saving.value = true; error.value = ''
  try {
    const payload = { materialType: materialType.value, title: title.value, sourceText: sourceText.value || undefined, usagePreference: usagePreference.value, contentJson: parsedContent }
    if (editingId.value) await updateMaterial(editingId.value, payload); else await createMaterial(payload)
    resetForm(); await Promise.all([reload(), loadRelationMaterials()])
  } catch { error.value = t('careerMaterial.saveError') }
  finally { saving.value = false }
}

async function remove(id: number) {
  if (!window.confirm(t('careerMaterial.deleteConfirm'))) return
  try { await deleteMaterial(id); await Promise.all([reload(), loadRelationMaterials()]) } catch { error.value = t('careerMaterial.saveError') }
}

function relationTitle(id: number | null) { return relationMaterials.value.find(item => item.id === id)?.title ?? t('careerMaterial.noRelatedMaterial') }
function cardSummary(item: CareerMaterialSummary) {
  const content = materialDetails.value[item.id]?.contentJson ?? {}
  if (item.materialType === 'ACHIEVEMENT') return [stringValue(content.metricDisplayValue) || stringValue(content.outcome), relationTitle(numberValue(content.relatedMaterialId))].filter(Boolean).join(' · ')
  if (item.materialType === 'LEADERSHIP_EXPERIENCE') return [stringValue(content.responsibilityScope), stringValue(content.result)].filter(Boolean).join(' · ')
  if (item.materialType === 'SKILL_EVIDENCE') return [stringValue(content.skillName), proficiencyLabel(stringValue(content.proficiency)), stringValue(content.outcomeEvidence)].filter(Boolean).join(' · ')
  return materialDetails.value[item.id]?.sourceText || ''
}
</script>

<template>
  <section class="workspace-page career-material-page">
    <header class="material-page-heading">
      <div>
        <p class="eyebrow"><Database :size="14" /> {{ t('careerMaterial.eyebrow') }}</p>
        <h1>{{ t('careerMaterial.title') }}</h1>
        <p class="page-lead">{{ t('careerMaterial.subtitle') }}</p>
      </div>
      <div class="evidence-route" aria-hidden="true">
        <span class="active">{{ t('careerMaterial.routeIdentity') }}</span><i></i><span>{{ t('careerMaterial.routeEvidence') }}</span><i></i><span>{{ t('careerMaterial.routeResume') }}</span>
      </div>
    </header>

    <section class="profile-band" aria-labelledby="personal-profile-title">
      <div class="profile-heading">
        <div class="material-section-heading"><span><UserRound :size="19" /></span><div><p class="section-kicker">{{ t('careerMaterial.profileEyebrow') }}</p><h2 id="personal-profile-title">{{ t('careerMaterial.profileTitle') }}</h2><p>{{ t('careerMaterial.profileDescription') }}</p></div></div>
        <button class="btn-neon btn-primary" type="button" :disabled="profileLoading || profileSaving" @click="saveProfile"><Save :size="16" /> {{ profileSaving ? t('careerMaterial.profileSaving') : t('careerMaterial.profileSave') }}</button>
      </div>
      <div class="profile-fields" :aria-busy="profileLoading">
        <label>{{ t('careerMaterial.fullName') }}<input v-model.trim="profile.fullName" autocomplete="name" /></label>
        <label>{{ t('careerMaterial.email') }}<input v-model.trim="profile.email" type="email" autocomplete="email" /></label>
        <label>{{ t('careerMaterial.phone') }}<input v-model.trim="profile.phone" autocomplete="tel" /></label>
        <label>{{ t('careerMaterial.location') }}<input v-model.trim="profile.location" autocomplete="address-level2" /></label>
        <label class="wide-field">{{ t('careerMaterial.website') }}<input v-model.trim="profile.website" type="url" placeholder="https://" /></label>
        <label class="wide-field">{{ t('careerMaterial.profileSummary') }}<textarea v-model.trim="profile.profileSummary" rows="4" :placeholder="t('careerMaterial.profileSummaryPlaceholder')" /></label>
      </div>
      <div class="career-targets" :aria-label="t('careerMaterial.careerTargetsTitle')">
        <div class="career-target-heading"><Target :size="17" /><div><h3>{{ t('careerMaterial.careerTargetsTitle') }}</h3><p>{{ t('careerMaterial.careerTargetsDescription') }}</p></div></div>
        <div class="profile-fields">
          <label>{{ t('careerMaterial.targetRoles') }}<input v-model.trim="targetRolesText" :placeholder="t('careerMaterial.targetRolesPlaceholder')" /></label>
          <label>{{ t('careerMaterial.targetSeniority') }}<input v-model.trim="profile.targetSeniority" :placeholder="t('careerMaterial.targetSeniorityPlaceholder')" /></label>
          <label>{{ t('careerMaterial.industryPreference') }}<input v-model.trim="preferredIndustriesText" :placeholder="t('careerMaterial.listSeparatorHint')" /></label>
          <label>{{ t('careerMaterial.locationPreference') }}<input v-model.trim="preferredLocationsText" :placeholder="t('careerMaterial.locationPreferencePlaceholder')" /></label>
          <label class="wide-field">{{ t('careerMaterial.careerPositioningSummary') }}<textarea v-model.trim="profile.careerPositioningSummary" rows="3" :placeholder="t('careerMaterial.careerPositioningSummaryPlaceholder')" /></label>
        </div>
      </div>
      <div v-if="resumes.length" class="profile-import">
        <label>{{ t('careerMaterial.importFromResume') }}<select v-model="importResumeId"><option :value="null">{{ t('careerMaterial.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
        <button class="btn-neon btn-ghost" type="button" :disabled="!importResumeId || profileLoading" @click="importProfileSuggestion"><Download :size="16" /> {{ t('careerMaterial.importSuggestion') }}</button><small>{{ t('careerMaterial.importDoesNotSave') }}</small>
      </div>
      <p v-if="profileMessage" class="profile-message" role="status">{{ profileMessage }}</p>
    </section>

    <section class="material-composer" aria-labelledby="material-composer-title">
      <header class="composer-heading">
        <div class="material-section-heading"><span><Plus :size="19" /></span><div><p class="section-kicker">{{ editingId ? t('careerMaterial.editEyebrow') : t('careerMaterial.createEyebrow') }}</p><h2 id="material-composer-title">{{ editingId ? t('careerMaterial.editTitle') : t('careerMaterial.createTitle') }}</h2><p>{{ t('careerMaterial.createDescription') }}</p></div></div>
        <button v-if="editingId" class="btn-neon btn-ghost" type="button" @click="resetForm">{{ t('careerMaterial.cancelEdit') }}</button>
      </header>
      <form class="material-form" @submit.prevent="create">
      <label>{{ t('careerMaterial.typeLabel') }}<select v-model="materialType" @change="startNewType"><option v-for="opt in TYPE_OPTIONS" :key="opt.value" :value="opt.value">{{ t(opt.key) }}</option></select></label>
      <label>{{ t('careerMaterial.titleLabel') }}<input v-model.trim="title" required maxlength="255" :placeholder="t('careerMaterial.titlePlaceholder')" /></label>
      <label>{{ t('careerMaterial.usageLabel') }}<select v-model="usagePreference"><option v-for="opt in USAGE_OPTIONS" :key="opt.value" :value="opt.value">{{ t(opt.key) }}</option></select></label>

      <template v-if="isAchievement">
        <label>{{ t('careerMaterial.relatedWork') }}<select v-model.number="specializedForm.relatedMaterialId" required><option :value="null" disabled>{{ t('careerMaterial.selectRelatedMaterial') }}</option><option v-for="item in relationMaterials" :key="item.id" :value="item.id">{{ item.title }}</option></select></label>
        <label>{{ t('careerMaterial.period') }}<input v-model.trim="specializedForm.period" required :placeholder="t('careerMaterial.periodPlaceholder')" /></label>
        <label class="wide-field">{{ t('careerMaterial.scenario') }}<textarea v-model.trim="specializedForm.scenario" rows="3" required :placeholder="t('careerMaterial.scenarioPlaceholder')" /></label>
        <label class="wide-field">{{ t('careerMaterial.action') }}<textarea v-model.trim="specializedForm.action" rows="3" required :placeholder="t('careerMaterial.actionPlaceholder')" /></label>
        <label class="wide-field">{{ t('careerMaterial.outcome') }}<textarea v-model.trim="specializedForm.outcome" rows="3" required :placeholder="t('careerMaterial.outcomePlaceholder')" /></label>
        <label>{{ t('careerMaterial.metricName') }}<input v-model.trim="specializedForm.metricName" required :placeholder="t('careerMaterial.metricNamePlaceholder')" /></label>
        <label>{{ t('careerMaterial.metricDisplayMode') }}<select v-model="specializedForm.metricDisplayMode"><option value="EXACT">{{ t('careerMaterial.metricModeExact') }}</option><option value="RANGE">{{ t('careerMaterial.metricModeRange') }}</option><option value="QUALITATIVE">{{ t('careerMaterial.metricModeQualitative') }}</option></select></label>
        <label v-if="specializedForm.metricDisplayMode !== 'EXACT'" class="wide-field">{{ t('careerMaterial.metricDisplayValue') }}<input v-model.trim="specializedForm.metricDisplayValue" required :placeholder="specializedForm.metricDisplayMode === 'RANGE' ? t('careerMaterial.metricDisplayValueRangePlaceholder') : t('careerMaterial.metricDisplayValueQualitativePlaceholder')" /></label>
        <label v-if="specializedForm.metricDisplayMode === 'EXACT'" class="wide-field">{{ t('careerMaterial.metricExactValue') }}<input v-model.trim="specializedForm.metricExactValue" required :placeholder="t('careerMaterial.metricExactValuePlaceholder')" /></label>
      </template>

      <template v-else-if="isLeadership">
        <label>{{ t('careerMaterial.relatedWork') }}<select v-model.number="specializedForm.relatedMaterialId" required><option :value="null" disabled>{{ t('careerMaterial.selectRelatedMaterial') }}</option><option v-for="item in relationMaterials" :key="item.id" :value="item.id">{{ item.title }}</option></select></label>
        <label>{{ t('careerMaterial.teamSize') }}<input v-model.trim="specializedForm.teamSize" required :placeholder="t('careerMaterial.teamSizePlaceholder')" /></label>
        <label class="wide-field">{{ t('careerMaterial.responsibilityScope') }}<textarea v-model.trim="specializedForm.responsibilityScope" rows="3" required :placeholder="t('careerMaterial.responsibilityScopePlaceholder')" /></label>
        <label>{{ t('careerMaterial.collaborationTargets') }}<input v-model.trim="specializedForm.collaborationTargets" required :placeholder="t('careerMaterial.collaborationTargetsPlaceholder')" /></label>
        <label>{{ t('careerMaterial.crossFunctionalRelationship') }}<input v-model.trim="specializedForm.crossFunctionalRelationship" required :placeholder="t('careerMaterial.crossFunctionalRelationshipPlaceholder')" /></label>
        <label class="wide-field">{{ t('careerMaterial.keyDecision') }}<textarea v-model.trim="specializedForm.keyDecision" rows="3" :placeholder="t('careerMaterial.keyDecisionPlaceholder')" /></label>
        <label class="wide-field">{{ t('careerMaterial.result') }}<textarea v-model.trim="specializedForm.result" rows="3" required :placeholder="t('careerMaterial.resultPlaceholder')" /></label>
      </template>

      <template v-else-if="isSkillEvidence">
        <label>{{ t('careerMaterial.skillName') }}<input v-model.trim="specializedForm.skillName" required :placeholder="t('careerMaterial.skillNamePlaceholder')" /></label>
        <label>{{ t('careerMaterial.skillCategory') }}<input v-model.trim="specializedForm.category" required :placeholder="t('careerMaterial.skillCategoryPlaceholder')" /></label>
        <label>{{ t('careerMaterial.proficiency') }}<select v-model="specializedForm.proficiency" required><option value="">{{ t('careerMaterial.selectProficiency') }}</option><option value="了解">{{ t('careerMaterial.proficiencyBasic') }}</option><option value="熟练">{{ t('careerMaterial.proficiencyProficient') }}</option><option value="精通">{{ t('careerMaterial.proficiencyExpert') }}</option></select></label>
        <label>{{ t('careerMaterial.yearsOfExperience') }}<input v-model.trim="specializedForm.yearsOfExperience" required :placeholder="t('careerMaterial.yearsOfExperiencePlaceholder')" /></label>
        <label>{{ t('careerMaterial.lastUsedAt') }}<input v-model.trim="specializedForm.lastUsedAt" required :placeholder="t('careerMaterial.lastUsedAtPlaceholder')" /></label>
        <fieldset class="wide-field relation-group"><legend>{{ t('careerMaterial.relatedWorks') }}</legend><label v-for="item in relationMaterials" :key="item.id" class="check-option"><input v-model="specializedForm.relatedMaterialIds" type="checkbox" :value="item.id" />{{ item.title }}</label></fieldset>
        <label class="wide-field">{{ t('careerMaterial.applicationDescription') }}<textarea v-model.trim="specializedForm.applicationDescription" rows="3" required :placeholder="t('careerMaterial.applicationDescriptionPlaceholder')" /></label>
        <label class="wide-field">{{ t('careerMaterial.outcomeEvidence') }}<textarea v-model.trim="specializedForm.outcomeEvidence" rows="3" required :placeholder="t('careerMaterial.outcomeEvidencePlaceholder')" /></label>
      </template>

      <template v-else>
        <label class="wide-field">{{ t('careerMaterial.sourceLabel') }}<textarea v-model.trim="sourceText" rows="4" :placeholder="t('careerMaterial.sourcePlaceholder')" /></label>
        <label class="wide-field">{{ t('careerMaterial.jsonLabel') }}<textarea v-model="contentJson" rows="8" spellcheck="false" :placeholder="t('careerMaterial.jsonPlaceholder')" /></label>
      </template>
        <div class="composer-actions"><p>{{ t('careerMaterial.truthHint') }}</p><button class="btn-neon btn-primary" :disabled="saving"><Save :size="16" /> {{ saving ? t('careerMaterial.saving') : editingId ? t('careerMaterial.saveEdit') : t('careerMaterial.saveNew') }}</button></div>
      </form>
    </section>

    <p v-if="error" class="form-error" role="alert">{{ error }}</p>

    <section class="material-library" aria-labelledby="material-library-title">
      <header class="library-heading">
        <div class="material-section-heading"><span><Database :size="19" /></span><div><p class="section-kicker">{{ t('careerMaterial.libraryEyebrow') }}</p><h2 id="material-library-title">{{ t('careerMaterial.libraryTitle') }}</h2><p>{{ t('careerMaterial.libraryDescription') }}</p></div></div>
        <label class="material-filter"><SlidersHorizontal :size="15" /><span>{{ t('careerMaterial.filterLabel') }}</span><select v-model="filterType" @change="reload"><option value="">{{ t('careerMaterial.filterAll') }}</option><option v-for="opt in TYPE_OPTIONS" :key="opt.value" :value="opt.value">{{ t(opt.key) }}</option></select></label>
      </header>
      <div class="library-meta"><span>{{ t('careerMaterial.visibleCount') }}</span><strong>{{ store.items.length }}</strong></div>
      <p v-if="store.loading" class="material-loading" role="status">{{ t('careerMaterial.loading') }}</p>
      <div v-else-if="!store.items.length" class="material-empty"><Database :size="23" /><div><h3>{{ t('careerMaterial.emptyTitle') }}</h3><p>{{ t('careerMaterial.empty') }}</p></div></div>
      <div v-else class="material-list">
        <article v-for="m in store.items" :key="m.id" class="material-row" :aria-label="m.title">
          <div class="material-row-main">
            <div class="material-meta"><span>{{ typeLabel(m.materialType) }}</span><span :class="`preference-${m.usagePreference.toLowerCase()}`">{{ usageLabel(m.usagePreference) }}</span></div>
            <h3>{{ m.title }}</h3>
            <p v-if="cardSummary(m)" class="material-summary">{{ cardSummary(m) }}</p>
          </div>
          <div class="material-actions">
            <button class="icon-action" type="button" :disabled="loadingDetail || saving" :title="t('common.edit')" :aria-label="`${t('common.edit')} ${m.title}`" @click="edit(m)"><Pencil :size="16" /></button>
            <button class="icon-action danger" type="button" :title="t('careerMaterial.deleteAction')" :aria-label="`${t('careerMaterial.deleteAction')} ${m.title}`" @click="remove(m.id)"><Trash2 :size="16" /></button>
          </div>
        </article>
      </div>
    </section>
  </section>
</template>

<style scoped>
.career-material-page { width: min(100%, 1120px); max-width: 1120px; gap: 28px; }
.material-page-heading { display: flex; align-items: end; justify-content: space-between; gap: 30px; padding-bottom: 24px; border-bottom: 1px solid var(--border); }
.material-page-heading h1 { margin: 5px 0 7px; font-family: var(--font-display); font-size: 36px; letter-spacing: 0; }
.material-page-heading .page-lead { max-width: 660px; }
.evidence-route { display: grid; grid-template-columns: auto 34px auto 34px auto; align-items: center; gap: 7px; flex: none; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.evidence-route i { display: block; height: 1px; background: var(--border); }
.evidence-route span.active { color: var(--accent); }
.profile-band, .material-composer, .material-library { display: grid; gap: 22px; padding: 26px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.profile-band { border-top: 4px solid var(--accent); }
.profile-heading, .profile-import, .composer-heading, .library-heading { display: flex; align-items: center; justify-content: space-between; gap: 20px; }
.material-section-heading { display: grid; grid-template-columns: 40px minmax(0, 1fr); align-items: start; gap: 12px; min-width: 0; }
.material-section-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--accent); background: var(--accent-light); }
.section-kicker { margin: 0 0 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 10px; font-weight: 700; }
.material-section-heading h2 { margin: 0; color: var(--text-primary); font-size: 17px; }
.material-section-heading p:last-child { margin: 5px 0 0; color: var(--text-secondary); font-size: 11px; line-height: 1.55; }
.profile-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 16px; }
.profile-fields label, .profile-import label, .material-form > label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 12px; font-weight: 650; }
.profile-fields input, .profile-fields textarea, .profile-import select, .material-form input, .material-form select, .material-form textarea { width: 100%; padding: 10px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font: inherit; font-size: 13px; }
.profile-fields input:focus, .profile-fields textarea:focus, .profile-import select:focus, .material-form input:focus, .material-form select:focus, .material-form textarea:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.profile-fields textarea, .material-form textarea { resize: vertical; }
.wide-field { grid-column: 1 / -1; }
.career-targets { display: grid; gap: 16px; padding-top: 20px; border-top: 1px solid var(--border-soft); }
.career-target-heading { display: flex; align-items: start; gap: 9px; color: var(--accent); }
.career-target-heading h3, .career-target-heading p { margin: 0; }
.career-target-heading h3 { color: var(--text-primary); font-size: 14px; }
.career-target-heading p { margin-top: 3px; color: var(--text-secondary); font-size: 11px; }
.profile-import { justify-content: flex-start; padding-top: 20px; border-top: 1px solid var(--border-soft); }
.profile-import label { min-width: min(100%, 300px); }
.profile-import small { max-width: 260px; color: var(--text-tertiary); font-size: 10px; line-height: 1.5; }
.profile-message { margin: -8px 0 0; color: var(--accent); font-size: 11px; font-weight: 650; }
.material-composer { border-left: 4px solid var(--highlight); }
.material-form { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 16px; padding-top: 20px; border-top: 1px solid var(--border-soft); }
.relation-group { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 14px; margin: 0; padding: 14px; border: 1px solid var(--border); border-radius: 6px; }
.relation-group legend { padding: 0 4px; color: var(--text-secondary); font-size: 12px; font-weight: 650; }
.check-option { display: flex; align-items: center; gap: 7px; color: var(--text-secondary); font-size: 12px; }
.check-option input { width: auto; }
.composer-actions { grid-column: 1 / -1; display: flex; align-items: center; justify-content: space-between; gap: 20px; padding-top: 4px; }
.composer-actions p { max-width: 550px; margin: 0; color: var(--text-tertiary); font-size: 10px; line-height: 1.55; }
.library-heading { align-items: end; }
.material-filter { display: grid; grid-template-columns: 16px auto minmax(150px, 190px); align-items: center; gap: 7px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.material-filter svg { color: var(--accent); }
.material-filter select { width: 100%; min-height: 36px; padding: 7px 28px 7px 9px; border: 1px solid var(--border); border-radius: 5px; color: var(--text-primary); background: var(--bg-input); font: inherit; }
.material-filter select:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.library-meta { display: flex; align-items: center; gap: 8px; margin: -5px 0 -10px; padding-top: 13px; border-top: 1px solid var(--border-soft); color: var(--text-tertiary); font-size: 10px; }
.library-meta strong { color: var(--accent); font-family: var(--font-utility); font-size: 11px; }
.material-loading { margin: 0; padding: 22px 0; color: var(--text-secondary); }
.material-empty { display: flex; align-items: center; gap: 13px; padding: 24px 4px; border-block: 1px solid var(--border-soft); color: var(--accent); }
.material-empty h3, .material-empty p { margin: 0; }
.material-empty h3 { color: var(--text-primary); font-size: 14px; }
.material-empty p { margin-top: 4px; color: var(--text-secondary); font-size: 11px; }
.material-list { display: grid; border-top: 1px solid var(--border); }
.material-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 20px; min-height: 96px; padding: 16px 4px; border-bottom: 1px solid var(--border); }
.material-row-main { min-width: 0; }
.material-row h3 { margin: 7px 0 0; color: var(--text-primary); font-size: 14px; }
.material-meta { display: flex; flex-wrap: wrap; gap: 6px; }
.material-meta span { display: inline-flex; min-height: 22px; align-items: center; padding: 3px 7px; border: 1px solid var(--border); border-radius: 4px; color: var(--text-secondary); background: var(--bg-page); font-size: 9px; font-weight: 700; }
.material-meta .preference-preferred { border-color: color-mix(in srgb, var(--highlight) 35%, var(--border)); color: var(--highlight); background: color-mix(in srgb, var(--highlight) 8%, #fff); }
.material-meta .preference-excluded { color: var(--text-tertiary); text-decoration: line-through; }
.material-summary { display: -webkit-box; margin: 6px 0 0; overflow: hidden; color: var(--text-secondary); font-size: 11px; line-height: 1.6; white-space: pre-wrap; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.material-actions { display: flex; gap: 6px; }
.icon-action { display: grid; width: 34px; height: 34px; place-items: center; padding: 0; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); cursor: pointer; }
.icon-action:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.icon-action.danger:hover { border-color: color-mix(in srgb, var(--danger) 30%, transparent); color: var(--danger); background: var(--danger-light); }
@media (max-width: 820px) { .material-page-heading { align-items: stretch; flex-direction: column; } .evidence-route { align-self: start; } .library-heading { align-items: stretch; flex-direction: column; } .material-filter { grid-template-columns: 16px auto minmax(0, 1fr); } }
@media (max-width: 640px) { .material-page-heading h1 { font-size: 30px; } .evidence-route { grid-template-columns: auto 16px auto 16px auto; width: 100%; } .profile-band, .material-composer, .material-library { padding: 20px 16px; } .profile-heading, .profile-import, .composer-heading { align-items: stretch; flex-direction: column; } .profile-fields, .material-form, .relation-group { grid-template-columns: 1fr; } .wide-field, .composer-actions { grid-column: auto; } .profile-heading .btn-neon, .profile-import .btn-neon, .composer-actions .btn-neon { justify-content: center; } .composer-actions { align-items: stretch; flex-direction: column; } .material-filter { grid-template-columns: 16px minmax(0, 1fr); } .material-filter span { display: none; } .material-row { align-items: start; grid-template-columns: minmax(0, 1fr) auto; gap: 8px; } }
</style>
