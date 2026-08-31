<script setup lang="ts">
import { computed, nextTick, reactive, ref, watch } from 'vue'
import { ArrowLeft, BarChart3, BriefcaseBusiness, FileText, Save, Settings2, X } from 'lucide-vue-next'
import type { CareerMaterial, CareerMaterialPayload, CareerMaterialSummary, MaterialType, UsagePreference } from '@/api/careerMaterial'
import { useLocale } from '@/i18n'
import { MATERIAL_TYPE_OPTIONS, USAGE_OPTIONS } from './options'

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

const props = defineProps<{
  material: CareerMaterial | null
  initialType: MaterialType
  relationMaterials: CareerMaterialSummary[]
  saving: boolean
}>()
const emit = defineEmits<{
  save: [payload: CareerMaterialPayload]
  cancel: []
  'dirty-change': [dirty: boolean]
  'type-change': [type: MaterialType]
}>()
const { t } = useLocale()

const form = reactive({
  materialType: 'WORK_EXPERIENCE' as MaterialType,
  title: '',
  sourceText: '',
  contentJson: '',
  usagePreference: 'NORMAL' as UsagePreference,
  specialized: emptySpecializedForm(),
})
const baseline = ref('')
const localError = ref('')
const isEditing = computed(() => Boolean(props.material))
const isAchievement = computed(() => form.materialType === 'ACHIEVEMENT')
const isLeadership = computed(() => form.materialType === 'LEADERSHIP_EXPERIENCE')
const isSkillEvidence = computed(() => form.materialType === 'SKILL_EVIDENCE')
const isSpecialized = computed(() => isAchievement.value || isLeadership.value || isSkillEvidence.value)
const standardProficiencyValues = ['BASIC', 'PROFICIENT', 'EXPERT']
const hasLegacyProficiency = computed(() => form.specialized.proficiency && !standardProficiencyValues.includes(form.specialized.proficiency))
const currentTypeLabel = computed(() => {
  const option = MATERIAL_TYPE_OPTIONS.find(item => item.value === form.materialType)
  return option ? t(option.key) : form.materialType
})

function emptySpecializedForm(): SpecializedForm {
  return {
    relatedMaterialId: null, relatedMaterialIds: [], scenario: '', action: '', outcome: '', period: '', metricName: '',
    metricDisplayMode: 'QUALITATIVE', metricDisplayValue: '', metricExactValue: '', responsibilityScope: '', collaborationTargets: '',
    teamSize: '', crossFunctionalRelationship: '', keyDecision: '', result: '', skillName: '', category: '', proficiency: '',
    yearsOfExperience: '', lastUsedAt: '', applicationDescription: '', outcomeEvidence: '',
  }
}

function stringValue(value: unknown) { return typeof value === 'string' ? value : '' }
function numberValue(value: unknown) { return typeof value === 'number' && Number.isFinite(value) ? value : null }
function numberList(value: unknown) { return Array.isArray(value) ? value.filter((item): item is number => typeof item === 'number') : [] }

function loadSpecialized(content: Record<string, unknown>) {
  Object.assign(form.specialized, emptySpecializedForm(), {
    relatedMaterialId: numberValue(content.relatedMaterialId), relatedMaterialIds: numberList(content.relatedMaterialIds),
    scenario: stringValue(content.scenario), action: stringValue(content.action), outcome: stringValue(content.outcome), period: stringValue(content.period),
    metricName: stringValue(content.metricName),
    metricDisplayMode: ['EXACT', 'RANGE', 'QUALITATIVE'].includes(stringValue(content.metricDisplayMode)) ? stringValue(content.metricDisplayMode) as MetricDisplayMode : 'QUALITATIVE',
    metricDisplayValue: stringValue(content.metricDisplayValue), metricExactValue: stringValue(content.metricExactValue),
    responsibilityScope: stringValue(content.responsibilityScope), collaborationTargets: stringValue(content.collaborationTargets), teamSize: stringValue(content.teamSize),
    crossFunctionalRelationship: stringValue(content.crossFunctionalRelationship), keyDecision: stringValue(content.keyDecision), result: stringValue(content.result),
    skillName: stringValue(content.skillName), category: stringValue(content.category), proficiency: stringValue(content.proficiency),
    yearsOfExperience: stringValue(content.yearsOfExperience), lastUsedAt: stringValue(content.lastUsedAt),
    applicationDescription: stringValue(content.applicationDescription), outcomeEvidence: stringValue(content.outcomeEvidence),
  })
}

function snapshot() {
  return JSON.stringify({
    materialType: form.materialType, title: form.title, sourceText: form.sourceText,
    contentJson: form.contentJson, usagePreference: form.usagePreference, specialized: form.specialized,
  })
}

function reset() {
  localError.value = ''
  form.materialType = props.material?.materialType ?? props.initialType
  form.title = props.material?.title ?? ''
  form.sourceText = props.material?.sourceText ?? ''
  form.usagePreference = props.material?.usagePreference ?? 'NORMAL'
  Object.assign(form.specialized, emptySpecializedForm())
  if (props.material && ['ACHIEVEMENT', 'LEADERSHIP_EXPERIENCE', 'SKILL_EVIDENCE'].includes(props.material.materialType)) {
    form.contentJson = ''
    loadSpecialized(props.material.contentJson)
  } else {
    form.contentJson = props.material ? JSON.stringify(props.material.contentJson, null, 2) : ''
  }
  void nextTick(() => {
    baseline.value = snapshot()
    emit('dirty-change', false)
  })
}

watch([() => props.material, () => props.initialType], reset, { immediate: true })
watch(snapshot, value => {
  if (baseline.value) emit('dirty-change', value !== baseline.value)
})

function onTypeChange() {
  Object.assign(form.specialized, emptySpecializedForm())
  form.contentJson = ''
  emit('type-change', form.materialType)
}

function buildSpecializedContent(): Record<string, unknown> {
  const value = form.specialized
  if (isAchievement.value) return {
    relatedMaterialId: value.relatedMaterialId, scenario: value.scenario, action: value.action, outcome: value.outcome, period: value.period,
    metricName: value.metricName, metricDisplayMode: value.metricDisplayMode, metricDisplayValue: value.metricDisplayValue,
    ...(value.metricDisplayMode === 'EXACT' ? { metricExactValue: value.metricExactValue } : {}),
  }
  if (isLeadership.value) return {
    relatedMaterialId: value.relatedMaterialId, responsibilityScope: value.responsibilityScope, collaborationTargets: value.collaborationTargets,
    teamSize: value.teamSize, crossFunctionalRelationship: value.crossFunctionalRelationship, keyDecision: value.keyDecision, result: value.result,
  }
  return {
    skillName: value.skillName, category: value.category, proficiency: value.proficiency, yearsOfExperience: value.yearsOfExperience,
    lastUsedAt: value.lastUsedAt, relatedMaterialIds: value.relatedMaterialIds, applicationDescription: value.applicationDescription, outcomeEvidence: value.outcomeEvidence,
  }
}

function submit() {
  let contentJson: Record<string, unknown>
  try {
    contentJson = isSpecialized.value
      ? buildSpecializedContent()
      : form.contentJson.trim() ? JSON.parse(form.contentJson) as Record<string, unknown> : { title: form.title, sourceText: form.sourceText }
  } catch {
    localError.value = t('careerMaterial.invalidJson')
    return
  }
  localError.value = ''
  emit('save', {
    materialType: form.materialType,
    title: form.title,
    sourceText: isEditing.value ? form.sourceText : form.sourceText || undefined,
    usagePreference: form.usagePreference,
    contentJson,
  })
}
</script>

<template>
  <section class="material-editor">
    <header class="editor-toolbar">
      <button class="mobile-back" type="button" :title="t('careerMaterial.closePanel')" :aria-label="t('careerMaterial.closePanel')" @click="emit('cancel')"><ArrowLeft :size="17" /></button>
      <div class="editor-heading">
        <p>{{ isEditing ? t('careerMaterial.editEyebrow') : t('careerMaterial.createEyebrow') }}</p>
        <div class="editor-title-line"><h2>{{ isEditing ? t('careerMaterial.editTitle') : t('careerMaterial.createTitle') }}</h2><span>{{ currentTypeLabel }}</span></div>
      </div>
      <button class="close-action" type="button" :title="t('careerMaterial.closePanel')" :aria-label="t('careerMaterial.closePanel')" @click="emit('cancel')"><X :size="17" /></button>
    </header>

    <form class="material-form" @submit.prevent="submit">
      <fieldset class="editor-fieldset" :disabled="saving">
        <div class="form-scroll">
          <section class="form-section">
            <header class="form-section-heading"><Settings2 :size="16" /><h3>{{ t('careerMaterial.editorBasics') }}</h3></header>
            <div class="form-grid compact-fields">
              <label>{{ t('careerMaterial.typeLabel') }}<select v-model="form.materialType" :disabled="isEditing" @change="onTypeChange"><option v-for="option in MATERIAL_TYPE_OPTIONS" :key="option.value" :value="option.value">{{ t(option.key) }}</option></select></label>
              <label>{{ t('careerMaterial.usageLabel') }}<select v-model="form.usagePreference"><option v-for="option in USAGE_OPTIONS" :key="option.value" :value="option.value">{{ t(option.key) }}</option></select></label>
              <label class="wide-field">{{ t('careerMaterial.titleLabel') }}<input v-model.trim="form.title" required maxlength="255" :placeholder="t('careerMaterial.titlePlaceholder')" /></label>
            </div>
          </section>

          <template v-if="isAchievement">
            <section class="form-section">
              <header class="form-section-heading"><BriefcaseBusiness :size="16" /><h3>{{ t('careerMaterial.editorExperience') }}</h3></header>
              <div class="form-grid">
                <label>{{ t('careerMaterial.relatedWork') }}<select v-model.number="form.specialized.relatedMaterialId" required><option :value="null" disabled>{{ t('careerMaterial.selectRelatedMaterial') }}</option><option v-for="item in relationMaterials" :key="item.id" :value="item.id">{{ item.title }}</option></select></label>
                <label>{{ t('careerMaterial.period') }}<input v-model.trim="form.specialized.period" required :placeholder="t('careerMaterial.periodPlaceholder')" /></label>
                <label>{{ t('careerMaterial.scenario') }}<textarea v-model.trim="form.specialized.scenario" rows="3" required :placeholder="t('careerMaterial.scenarioPlaceholder')" /></label>
                <label>{{ t('careerMaterial.action') }}<textarea v-model.trim="form.specialized.action" rows="3" required :placeholder="t('careerMaterial.actionPlaceholder')" /></label>
              </div>
            </section>
            <section class="form-section">
              <header class="form-section-heading"><BarChart3 :size="16" /><h3>{{ t('careerMaterial.editorResults') }}</h3></header>
              <div class="form-grid">
                <label>{{ t('careerMaterial.outcome') }}<textarea v-model.trim="form.specialized.outcome" rows="3" required :placeholder="t('careerMaterial.outcomePlaceholder')" /></label>
                <div class="paired-fields">
                  <label>{{ t('careerMaterial.metricName') }}<input v-model.trim="form.specialized.metricName" required :placeholder="t('careerMaterial.metricNamePlaceholder')" /></label>
                  <label>{{ t('careerMaterial.metricDisplayMode') }}<select v-model="form.specialized.metricDisplayMode"><option value="EXACT">{{ t('careerMaterial.metricModeExact') }}</option><option value="RANGE">{{ t('careerMaterial.metricModeRange') }}</option><option value="QUALITATIVE">{{ t('careerMaterial.metricModeQualitative') }}</option></select></label>
                </div>
                <label v-if="form.specialized.metricDisplayMode !== 'EXACT'">{{ t('careerMaterial.metricDisplayValue') }}<input v-model.trim="form.specialized.metricDisplayValue" required :placeholder="form.specialized.metricDisplayMode === 'RANGE' ? t('careerMaterial.metricDisplayValueRangePlaceholder') : t('careerMaterial.metricDisplayValueQualitativePlaceholder')" /></label>
                <label v-else>{{ t('careerMaterial.metricExactValue') }}<input v-model.trim="form.specialized.metricExactValue" required :placeholder="t('careerMaterial.metricExactValuePlaceholder')" /></label>
              </div>
            </section>
          </template>

          <template v-else-if="isLeadership">
            <section class="form-section">
              <header class="form-section-heading"><BriefcaseBusiness :size="16" /><h3>{{ t('careerMaterial.editorExperience') }}</h3></header>
              <div class="form-grid">
                <label>{{ t('careerMaterial.relatedWork') }}<select v-model.number="form.specialized.relatedMaterialId" required><option :value="null" disabled>{{ t('careerMaterial.selectRelatedMaterial') }}</option><option v-for="item in relationMaterials" :key="item.id" :value="item.id">{{ item.title }}</option></select></label>
                <label>{{ t('careerMaterial.responsibilityScope') }}<textarea v-model.trim="form.specialized.responsibilityScope" rows="3" required :placeholder="t('careerMaterial.responsibilityScopePlaceholder')" /></label>
                <div class="paired-fields">
                  <label>{{ t('careerMaterial.teamSize') }}<input v-model.trim="form.specialized.teamSize" required :placeholder="t('careerMaterial.teamSizePlaceholder')" /></label>
                  <label>{{ t('careerMaterial.collaborationTargets') }}<input v-model.trim="form.specialized.collaborationTargets" required :placeholder="t('careerMaterial.collaborationTargetsPlaceholder')" /></label>
                </div>
                <label>{{ t('careerMaterial.crossFunctionalRelationship') }}<input v-model.trim="form.specialized.crossFunctionalRelationship" required :placeholder="t('careerMaterial.crossFunctionalRelationshipPlaceholder')" /></label>
              </div>
            </section>
            <section class="form-section">
              <header class="form-section-heading"><BarChart3 :size="16" /><h3>{{ t('careerMaterial.editorResults') }}</h3></header>
              <div class="form-grid">
                <label>{{ t('careerMaterial.keyDecision') }}<textarea v-model.trim="form.specialized.keyDecision" rows="3" required :placeholder="t('careerMaterial.keyDecisionPlaceholder')" /></label>
                <label>{{ t('careerMaterial.result') }}<textarea v-model.trim="form.specialized.result" rows="3" required :placeholder="t('careerMaterial.resultPlaceholder')" /></label>
              </div>
            </section>
          </template>

          <template v-else-if="isSkillEvidence">
            <section class="form-section">
              <header class="form-section-heading"><BriefcaseBusiness :size="16" /><h3>{{ t('careerMaterial.editorExperience') }}</h3></header>
              <div class="form-grid">
                <div class="paired-fields">
                  <label>{{ t('careerMaterial.skillName') }}<input v-model.trim="form.specialized.skillName" required :placeholder="t('careerMaterial.skillNamePlaceholder')" /></label>
                  <label>{{ t('careerMaterial.skillCategory') }}<input v-model.trim="form.specialized.category" required :placeholder="t('careerMaterial.skillCategoryPlaceholder')" /></label>
                </div>
                <div class="paired-fields">
                  <label>{{ t('careerMaterial.proficiency') }}<select v-model="form.specialized.proficiency" required><option value="">{{ t('careerMaterial.selectProficiency') }}</option><option v-if="hasLegacyProficiency" :value="form.specialized.proficiency">{{ form.specialized.proficiency }}</option><option value="BASIC">{{ t('careerMaterial.proficiencyBasic') }}</option><option value="PROFICIENT">{{ t('careerMaterial.proficiencyProficient') }}</option><option value="EXPERT">{{ t('careerMaterial.proficiencyExpert') }}</option></select></label>
                  <label>{{ t('careerMaterial.yearsOfExperience') }}<input v-model.trim="form.specialized.yearsOfExperience" required :placeholder="t('careerMaterial.yearsOfExperiencePlaceholder')" /></label>
                </div>
                <label>{{ t('careerMaterial.lastUsedAt') }}<input v-model.trim="form.specialized.lastUsedAt" required :placeholder="t('careerMaterial.lastUsedAtPlaceholder')" /></label>
                <fieldset class="relation-group"><legend>{{ t('careerMaterial.relatedWorks') }}</legend><label v-for="item in relationMaterials" :key="item.id" class="check-option"><input v-model="form.specialized.relatedMaterialIds" type="checkbox" :value="item.id" />{{ item.title }}</label></fieldset>
              </div>
            </section>
            <section class="form-section">
              <header class="form-section-heading"><BarChart3 :size="16" /><h3>{{ t('careerMaterial.editorResults') }}</h3></header>
              <div class="form-grid">
                <label>{{ t('careerMaterial.applicationDescription') }}<textarea v-model.trim="form.specialized.applicationDescription" rows="3" required :placeholder="t('careerMaterial.applicationDescriptionPlaceholder')" /></label>
                <label>{{ t('careerMaterial.outcomeEvidence') }}<textarea v-model.trim="form.specialized.outcomeEvidence" rows="3" required :placeholder="t('careerMaterial.outcomeEvidencePlaceholder')" /></label>
              </div>
            </section>
          </template>

          <section v-else class="form-section">
            <header class="form-section-heading"><FileText :size="16" /><h3>{{ t('careerMaterial.editorSource') }}</h3></header>
            <div class="form-grid">
              <label>{{ t('careerMaterial.sourceLabel') }}<textarea v-model.trim="form.sourceText" rows="6" :placeholder="t('careerMaterial.sourcePlaceholder')" /></label>
              <details class="advanced-json"><summary>{{ t('careerMaterial.advancedJson') }}</summary><label>{{ t('careerMaterial.jsonLabel') }}<textarea v-model="form.contentJson" rows="9" spellcheck="false" :placeholder="t('careerMaterial.jsonPlaceholder')" /></label></details>
            </div>
          </section>

          <p v-if="localError" class="form-error" role="alert">{{ localError }}</p>
          <p class="truth-hint">{{ t('careerMaterial.truthHint') }}</p>
        </div>
      </fieldset>
      <footer class="editor-actions">
        <button class="btn-neon btn-ghost" type="button" @click="emit('cancel')">{{ t('common.cancel') }}</button>
        <button class="btn-neon btn-primary" :disabled="saving"><Save :size="16" /> {{ saving ? t('careerMaterial.saving') : isEditing ? t('careerMaterial.saveEdit') : t('careerMaterial.saveNew') }}</button>
      </footer>
    </form>
  </section>
</template>

<style scoped>
.material-editor { display: grid; height: 100%; grid-template-rows: auto minmax(0, 1fr); min-width: 0; min-height: 0; overflow: hidden; background: var(--bg-surface); }
.editor-fieldset { display: grid; min-width: 0; min-height: 0; height: 100%; margin: 0; padding: 0; overflow: hidden; border: 0; grid-template-rows: minmax(0, 1fr); }
.editor-toolbar { display: grid; grid-template-columns: minmax(0, 1fr) 32px; align-items: center; gap: 10px; min-height: 68px; padding: 11px 14px 11px 20px; border-bottom: 1px solid var(--border); }
.editor-toolbar p, .editor-toolbar h2 { margin: 0; }
.editor-heading { min-width: 0; }
.editor-toolbar p { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 750; text-transform: uppercase; }
.editor-title-line { display: flex; min-width: 0; align-items: center; gap: 9px; margin-top: 4px; }
.editor-toolbar h2 { min-width: 0; overflow: hidden; font-size: 16px; text-overflow: ellipsis; white-space: nowrap; }
.editor-title-line span { flex: none; max-width: 150px; overflow: hidden; padding: 3px 7px; border: 1px solid var(--border); border-radius: 4px; color: var(--accent); background: var(--accent-light); font-size: 9px; font-weight: 700; text-overflow: ellipsis; white-space: nowrap; }
.close-action, .mobile-back { display: grid; width: 32px; height: 32px; place-items: center; padding: 0; border: 0; border-radius: 5px; color: var(--text-secondary); background: transparent; cursor: pointer; }
.close-action:hover, .mobile-back:hover { color: var(--accent); background: var(--accent-light); }
.mobile-back { display: none; }
.material-form { display: grid; min-height: 0; overflow: hidden; gap: 0; grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr) auto; }
.form-scroll { min-height: 0; overflow-y: auto; overscroll-behavior: contain; padding: 0 20px 22px; scrollbar-gutter: stable; }
.form-section { padding: 19px 0 21px; border-bottom: 1px solid var(--border-soft); }
.form-section:first-child { padding-top: 17px; }
.form-section-heading { display: flex; align-items: center; gap: 8px; margin-bottom: 15px; color: var(--accent); }
.form-section-heading h3 { margin: 0; color: var(--text-primary); font-size: 12px; font-weight: 750; }
.form-grid { display: grid; grid-template-columns: minmax(0, 1fr); gap: 15px; }
.compact-fields { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.paired-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.form-grid > label, .paired-fields > label, .advanced-json label { display: grid; min-width: 0; gap: 7px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.form-grid input, .form-grid select, .form-grid textarea { width: 100%; min-height: 40px; padding: 9px 10px; border: 1px solid var(--border); border-radius: 5px; color: var(--text-primary); background: var(--bg-input); font: inherit; font-size: 12px; line-height: 1.45; }
.form-grid input:focus, .form-grid select:focus, .form-grid textarea:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.form-grid textarea { min-height: 98px; resize: vertical; line-height: 1.65; }
.wide-field { grid-column: 1 / -1; }
.relation-group { display: grid; max-height: 176px; grid-template-columns: minmax(0, 1fr); gap: 4px; margin: 0; padding: 10px 12px 12px; overflow-y: auto; border: 1px solid var(--border); border-radius: 5px; background: var(--bg-input); }
.relation-group legend { padding: 0 4px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.check-option { display: flex; min-width: 0; align-items: flex-start; gap: 8px; padding: 5px 3px; color: var(--text-secondary); font-size: 11px; line-height: 1.45; }
.check-option input { width: auto; }
.advanced-json { padding-top: 2px; }
.advanced-json summary { color: var(--accent); font-size: 11px; font-weight: 650; cursor: pointer; }
.advanced-json label { margin-top: 10px; }
.truth-hint { margin: 16px 0 0; color: var(--text-tertiary); font-size: 10px; line-height: 1.6; }
.form-error { margin: 14px 0 0; }
.editor-actions { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 16px; border-top: 1px solid var(--border); background: color-mix(in srgb, var(--bg-surface) 94%, var(--accent-light)); box-shadow: 0 -8px 20px rgb(10 34 27 / .04); }
.editor-actions .btn-primary { min-width: 132px; justify-content: center; }
@media (max-width: 767px) {
  .material-editor { min-height: 100dvh; }
  .editor-toolbar { grid-template-columns: 32px minmax(0, 1fr) 32px; padding-left: 12px; }
  .mobile-back { display: grid; }
  .form-scroll { overflow-y: auto; padding: 0 15px 96px; }
  .compact-fields, .paired-fields { grid-template-columns: minmax(0, 1fr); }
  .wide-field { grid-column: auto; }
  .editor-actions { position: fixed; right: 0; bottom: 0; left: 0; z-index: 3; padding-bottom: max(12px, env(safe-area-inset-bottom)); }
  .editor-actions .btn-primary { flex: 1; }
}
</style>
