<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Download, Save, UserRound } from 'lucide-vue-next'
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

const TYPE_OPTIONS: { value: MaterialType; key?: string; label?: string }[] = [
  { value: 'WORK_EXPERIENCE', key: 'careerMaterial.filterWorkExperience' },
  { value: 'PROJECT_EXPERIENCE', key: 'careerMaterial.filterProjectExperience' },
  { value: 'EDUCATION', key: 'careerMaterial.filterEducation' },
  { value: 'SKILL', key: 'careerMaterial.filterSkill' },
  { value: 'CERTIFICATE', key: 'careerMaterial.filterCertificate' },
  { value: 'HIGHLIGHT', key: 'careerMaterial.filterHighlight' },
  { value: 'AWARD', key: 'careerMaterial.filterAward' },
  { value: 'ACHIEVEMENT', label: '量化成果' },
  { value: 'LEADERSHIP_EXPERIENCE', label: '管理 / 协作经历' },
  { value: 'SKILL_EVIDENCE', label: '技能证据' },
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

function optionLabel(option: { key?: string; label?: string }) {
  return option.label ?? t(option.key ?? '')
}

function typeLabel(type: MaterialType) {
  return optionLabel(TYPE_OPTIONS.find(option => option.value === type) ?? { label: type })
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
    skillName: stringValue(value.skillName), category: stringValue(value.category), proficiency: stringValue(value.proficiency),
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

function relationTitle(id: number | null) { return relationMaterials.value.find(item => item.id === id)?.title ?? '未关联经历' }
function cardSummary(item: CareerMaterialSummary) {
  const content = materialDetails.value[item.id]?.contentJson ?? {}
  if (item.materialType === 'ACHIEVEMENT') return [stringValue(content.metricDisplayValue) || stringValue(content.outcome), relationTitle(numberValue(content.relatedMaterialId))].filter(Boolean).join(' · ')
  if (item.materialType === 'LEADERSHIP_EXPERIENCE') return [stringValue(content.responsibilityScope), stringValue(content.result)].filter(Boolean).join(' · ')
  if (item.materialType === 'SKILL_EVIDENCE') return [stringValue(content.skillName), stringValue(content.proficiency), stringValue(content.outcomeEvidence)].filter(Boolean).join(' · ')
  return materialDetails.value[item.id]?.sourceText || ''
}
</script>

<template>
  <section class="workspace-page">
    <h1>{{ t('careerMaterial.title') }}</h1>
    <section class="profile-band" aria-labelledby="personal-profile-title">
      <div class="profile-heading">
        <div><p class="eyebrow"><UserRound :size="14" /> {{ t('careerMaterial.profileEyebrow') }}</p><h2 id="personal-profile-title">{{ t('careerMaterial.profileTitle') }}</h2><p>{{ t('careerMaterial.profileDescription') }}</p></div>
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
      <div class="career-targets" aria-label="职业目标">
        <h3>职业目标</h3><p>作为长期定位参与岗位选材，只影响个人摘要和岗位表达。</p>
        <div class="profile-fields">
          <label>目标职位<input v-model.trim="targetRolesText" placeholder="例如：Java 后端工程师、技术负责人" /></label>
          <label>目标职级<input v-model.trim="profile.targetSeniority" placeholder="例如：高级 / 专家 / 负责人" /></label>
          <label>行业偏好<input v-model.trim="preferredIndustriesText" placeholder="多个内容用顿号或逗号分隔" /></label>
          <label>城市 / 工作方式偏好<input v-model.trim="preferredLocationsText" placeholder="例如：杭州、远程" /></label>
          <label class="wide-field">职业定位摘要<textarea v-model.trim="profile.careerPositioningSummary" rows="3" placeholder="说明你希望长期发展的专业方向与价值定位" /></label>
        </div>
      </div>
      <div v-if="resumes.length" class="profile-import">
        <label>{{ t('careerMaterial.importFromResume') }}<select v-model="importResumeId"><option :value="null">{{ t('careerMaterial.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
        <button class="btn-neon btn-ghost" type="button" :disabled="!importResumeId || profileLoading" @click="importProfileSuggestion"><Download :size="16" /> {{ t('careerMaterial.importSuggestion') }}</button><small>{{ t('careerMaterial.importDoesNotSave') }}</small>
      </div>
      <p v-if="profileMessage" class="profile-message" role="status">{{ profileMessage }}</p>
    </section>

    <label class="workspace-card">{{ t('careerMaterial.filterLabel') }}<select v-model="filterType" @change="reload"><option value="">{{ t('careerMaterial.filterAll') }}</option><option v-for="opt in TYPE_OPTIONS" :key="opt.value" :value="opt.value">{{ optionLabel(opt) }}</option></select></label>
    <form class="workspace-card material-form" @submit.prevent="create">
      <label>{{ t('careerMaterial.typeLabel') }}<select v-model="materialType" @change="startNewType"><option v-for="opt in TYPE_OPTIONS" :key="opt.value" :value="opt.value">{{ optionLabel(opt) }}</option></select></label>
      <label>{{ t('careerMaterial.titleLabel') }}<input v-model.trim="title" required maxlength="255" :placeholder="t('careerMaterial.titlePlaceholder')" /></label>
      <label>{{ t('careerMaterial.usageLabel') }}<select v-model="usagePreference"><option v-for="opt in USAGE_OPTIONS" :key="opt.value" :value="opt.value">{{ t(opt.key) }}</option></select></label>

      <template v-if="isAchievement">
        <label>关联工作或项目<select v-model.number="specializedForm.relatedMaterialId" required><option :value="null" disabled>选择关联经历</option><option v-for="item in relationMaterials" :key="item.id" :value="item.id">{{ item.title }}</option></select></label>
        <label>时间范围<input v-model.trim="specializedForm.period" required placeholder="例如：2024 年 Q1-Q2" /></label>
        <label class="wide-field">业务场景<textarea v-model.trim="specializedForm.scenario" rows="3" required placeholder="这项成果发生在什么业务或技术场景？" /></label>
        <label class="wide-field">采取行动<textarea v-model.trim="specializedForm.action" rows="3" required placeholder="你具体负责并完成了什么？" /></label>
        <label class="wide-field">成果说明<textarea v-model.trim="specializedForm.outcome" rows="3" required placeholder="可核实的结果和影响" /></label>
        <label>指标名称<input v-model.trim="specializedForm.metricName" required placeholder="例如：接口 P99 延迟" /></label>
        <label>展示口径<select v-model="specializedForm.metricDisplayMode"><option value="EXACT">精确数值</option><option value="RANGE">区间 / 比例</option><option value="QUALITATIVE">仅定性表达</option></select></label>
        <label v-if="specializedForm.metricDisplayMode !== 'EXACT'" class="wide-field">成果展示值<input v-model.trim="specializedForm.metricDisplayValue" required :placeholder="specializedForm.metricDisplayMode === 'RANGE' ? '例如：降低约三成' : '例如：显著缩短高峰期等待时间'" /></label>
        <label v-if="specializedForm.metricDisplayMode === 'EXACT'" class="wide-field">精确值<input v-model.trim="specializedForm.metricExactValue" required placeholder="仅填写允许进入简历与 AI 生成的精确数值" /></label>
      </template>

      <template v-else-if="isLeadership">
        <label>关联工作或项目<select v-model.number="specializedForm.relatedMaterialId" required><option :value="null" disabled>选择关联经历</option><option v-for="item in relationMaterials" :key="item.id" :value="item.id">{{ item.title }}</option></select></label>
        <label>带领 / 协作规模<input v-model.trim="specializedForm.teamSize" required placeholder="例如：6 人研发小组" /></label>
        <label class="wide-field">职责范围<textarea v-model.trim="specializedForm.responsibilityScope" rows="3" required placeholder="你承担的管理、协作或技术决策职责" /></label>
        <label>协作对象<input v-model.trim="specializedForm.collaborationTargets" required placeholder="例如：产品、测试、运营" /></label>
        <label>跨团队关系<input v-model.trim="specializedForm.crossFunctionalRelationship" required placeholder="例如：推动支付、风控共同交付" /></label>
        <label class="wide-field">关键决策或机制<textarea v-model.trim="specializedForm.keyDecision" rows="3" placeholder="建立的机制、做出的关键判断或推动的协作方式" /></label>
        <label class="wide-field">实际结果<textarea v-model.trim="specializedForm.result" rows="3" required placeholder="可核实的协作、交付或团队结果" /></label>
      </template>

      <template v-else-if="isSkillEvidence">
        <label>技能名称<input v-model.trim="specializedForm.skillName" required placeholder="例如：Spring Boot" /></label>
        <label>技能分类<input v-model.trim="specializedForm.category" required placeholder="例如：后端框架" /></label>
        <label>熟练度<select v-model="specializedForm.proficiency" required><option value="">请选择</option><option>了解</option><option>熟练</option><option>精通</option></select></label>
        <label>使用年限<input v-model.trim="specializedForm.yearsOfExperience" required placeholder="例如：3 年" /></label>
        <label>最近使用时间<input v-model.trim="specializedForm.lastUsedAt" required placeholder="例如：2026 年" /></label>
        <fieldset class="wide-field relation-group"><legend>关联工作 / 项目（可多选）</legend><label v-for="item in relationMaterials" :key="item.id" class="check-option"><input v-model="specializedForm.relatedMaterialIds" type="checkbox" :value="item.id" />{{ item.title }}</label></fieldset>
        <label class="wide-field">实际应用说明<textarea v-model.trim="specializedForm.applicationDescription" rows="3" required placeholder="在哪些经历中如何使用该技能？" /></label>
        <label class="wide-field">结果证据<textarea v-model.trim="specializedForm.outcomeEvidence" rows="3" required placeholder="使用该技能产生的可核实结果" /></label>
      </template>

      <template v-else>
        <label class="wide-field">{{ t('careerMaterial.sourceLabel') }}<textarea v-model.trim="sourceText" rows="4" :placeholder="t('careerMaterial.sourcePlaceholder')" /></label>
        <label class="wide-field">{{ t('careerMaterial.jsonLabel') }}<textarea v-model="contentJson" rows="8" spellcheck="false" :placeholder="t('careerMaterial.jsonPlaceholder')" /></label>
      </template>
      <div class="dialog-actions"><button v-if="editingId" class="btn-neon btn-ghost" type="button" @click="resetForm">{{ t('careerMaterial.cancelEdit') }}</button><button class="btn-neon btn-primary" :disabled="saving">{{ saving ? t('careerMaterial.saving') : editingId ? t('careerMaterial.saveEdit') : t('careerMaterial.saveNew') }}</button></div>
    </form>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p><p v-if="store.loading">{{ t('careerMaterial.loading') }}</p><p v-else-if="!store.items.length" class="empty-state">{{ t('careerMaterial.empty') }}</p>
    <div v-else class="job-list"><article v-for="m in store.items" :key="m.id" class="workspace-card job-card" :aria-label="m.title"><div><p class="material-kind">{{ typeLabel(m.materialType) }} · {{ m.usagePreference }}</p><h2>{{ m.title }}</h2><p v-if="cardSummary(m)" class="material-summary">{{ cardSummary(m) }}</p></div><div class="job-actions"><button class="btn-neon btn-ghost" :disabled="loadingDetail || saving" @click="edit(m)">{{ t('common.edit') }}</button><button class="danger-action" :title="t('careerMaterial.deleteAction')" @click="remove(m.id)">{{ t('common.delete') }}</button></div></article></div>
  </section>
</template>

<style scoped>
.profile-band { margin: 1.25rem 0 1.5rem; padding: 1.25rem 0 1.5rem; border-block: 1px solid rgba(100, 116, 139, 0.24); }
.profile-heading, .profile-import { display: flex; align-items: end; justify-content: space-between; gap: 1rem; }
.profile-heading h2 { margin: 0.15rem 0 0.3rem; font-size: 1.15rem; }.profile-heading p, .career-targets > p { margin: 0; color: var(--text-muted, #64748b); }
.profile-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.9rem 1rem; margin-top: 1.1rem; }.profile-fields label, .profile-import label { display: grid; gap: 0.35rem; font-size: 0.82rem; font-weight: 600; }.profile-fields input, .profile-fields textarea, .profile-import select { width: 100%; }.wide-field { grid-column: 1 / -1; }
.career-targets { margin-top: 1.2rem; padding-top: 1rem; border-top: 1px dashed rgba(100, 116, 139, 0.28); }.career-targets h3 { margin: 0 0 0.25rem; font-size: 1rem; }
.profile-import { justify-content: flex-start; margin-top: 1rem; padding-top: 1rem; border-top: 1px dashed rgba(100, 116, 139, 0.28); }.profile-import label { min-width: min(100%, 280px); }.profile-import small { color: var(--text-muted, #64748b); }.profile-message { margin: 0.8rem 0 0; color: var(--primary, #0e7490); font-size: 0.85rem; }
.relation-group { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0.5rem 1rem; margin: 0; padding: 0.75rem; border: 1px solid rgba(100, 116, 139, 0.35); }.relation-group legend { padding: 0 0.25rem; font-size: 0.85rem; }.check-option { display: flex; align-items: center; gap: 0.45rem; font-size: 0.88rem; }.check-option input { width: auto; }.material-kind { margin: 0 0 0.25rem; color: var(--text-muted, #64748b); font-size: 0.78rem; }.material-summary { margin: 0.4rem 0 0; color: var(--text-muted, #64748b); white-space: pre-wrap; }
@media (max-width: 640px) { .profile-heading, .profile-import { align-items: stretch; flex-direction: column; }.profile-fields, .relation-group { grid-template-columns: 1fr; }.wide-field { grid-column: auto; } }
</style>
