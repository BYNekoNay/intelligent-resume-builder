<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, ArrowRight, BriefcaseBusiness, Check, Database, FileText, ShieldCheck, Sparkles } from 'lucide-vue-next'
import { useCareerMaterialStore } from '@/stores/careerMaterial'
import { useJobDescriptionStore } from '@/stores/jobDescription'
import { useAiTaskStore } from '@/stores/aiTask'
import { getConsent, hasJobGenerationConsent, selectMaterialsForJob, type MaterialSelectionRequest } from '@/api/ai'
import type { CareerMaterialSummary, MaterialType } from '@/api/careerMaterial'
import { useLocale } from '@/i18n'

const { t } = useLocale()

const PENDING_GENERATION_KEY = 'pending-job-generation'

interface PendingGeneration {
  payload: MaterialSelectionRequest
  idempotencyKey: string
}

const router = useRouter()
const route = useRoute()
const materialStore = useCareerMaterialStore()
const jdStore = useJobDescriptionStore()
const taskStore = useAiTaskStore()

const step = ref(1)
const error = ref('')
const generating = ref(false)

// Step 1: JD selection
const jdMode = ref<'select' | 'paste'>('select')
const selectedJdId = ref<number | null>(null)
const pastedJdText = ref('')
const companyName = ref('')
const positionTitle = ref('')

// Step 2: Material selection
type MaterialDecision = 'default' | 'must' | 'exclude'
const materialDecisions = ref<Record<number, MaterialDecision>>({})

const selectedJd = computed(() =>
  jdStore.items.find(j => j.id === selectedJdId.value) ?? null
)

const resolvedResumeTitle = computed(() => {
  const company = jdMode.value === 'select' ? (selectedJd.value?.companyName ?? '') : companyName.value
  const position = jdMode.value === 'select' ? (selectedJd.value?.title ?? '') : positionTitle.value
  if (company && position) return `${company} - ${position}`
  return position || company || t('generationWorkbench.fallbackResumeTitle')
})

const canProceedStep1 = computed(() => {
  if (jdMode.value === 'select') return selectedJdId.value !== null
  return pastedJdText.value.trim().length >= 20
})

const materialsByDecision = computed(() => {
  const must: CareerMaterialSummary[] = []
  const exclude: CareerMaterialSummary[] = []
  const auto: CareerMaterialSummary[] = []
  for (const m of materialStore.items) {
    const d = materialDecisions.value[m.id] ?? 'default'
    if (d === 'must') must.push(m)
    else if (d === 'exclude') exclude.push(m)
    else auto.push(m)
  }
  return { must, exclude, auto }
})

onMounted(async () => {
  const storeLoads = Promise.all([materialStore.load(), jdStore.load()])
  if (route.query.resumePending === '1') {
    await Promise.all([storeLoads, resumePendingGeneration()])
    return
  }

  await storeLoads
  initializeMaterialDecisions()
  const jdId = Number(route.query.jdId)
  if (Number.isInteger(jdId) && jdStore.items.some((jd) => jd.id === jdId)) {
    jdMode.value = 'select'
    selectedJdId.value = jdId
  }
})

function setDecision(id: number, decision: MaterialDecision) {
  materialDecisions.value[id] = decision
}

function initializeMaterialDecisions() {
  for (const material of materialStore.items) {
    if (!(material.id in materialDecisions.value) && material.usagePreference === 'EXCLUDED') {
      materialDecisions.value[material.id] = 'exclude'
    }
  }
}

function cycleDecision(id: number) {
  const current = materialDecisions.value[id] ?? 'default'
  const next: MaterialDecision = current === 'default' ? 'must' : current === 'must' ? 'exclude' : 'default'
  materialDecisions.value[id] = next
}

function buildGenerationPayload(): MaterialSelectionRequest {
    const includedIds = materialsByDecision.value.must.map(m => m.id)
    const excludedIds = materialsByDecision.value.exclude.map(m => m.id)

    const payload: MaterialSelectionRequest = {
      taskType: 'JOB_MATERIAL_SELECTION',
      input: {
        includedMaterialIds: includedIds,
        preferredMaterialIds: materialsByDecision.value.auto.filter(m => m.usagePreference === 'PREFERRED').map(m => m.id),
        excludedMaterialIds: excludedIds,
      },
    }

    if (jdMode.value === 'select' && selectedJdId.value) {
      payload.jobDescriptionId = selectedJdId.value
      // Use JD info for resume title
      const jd = selectedJd.value
      if (jd) {
        payload.companyName = jd.companyName || ''
        payload.positionTitle = jd.title || ''
      }
    } else {
      payload.jdText = pastedJdText.value
      payload.companyName = companyName.value
      payload.positionTitle = positionTitle.value
    }

    payload.resumeTitle = resolvedResumeTitle.value
    return payload
}

function storePendingGeneration(pending: PendingGeneration) {
  sessionStorage.setItem(PENDING_GENERATION_KEY, JSON.stringify(pending))
}

function readPendingGeneration(): PendingGeneration | null {
  try {
    const stored = sessionStorage.getItem(PENDING_GENERATION_KEY)
    if (!stored) return null
    const parsed = JSON.parse(stored) as Partial<PendingGeneration>
    if (typeof parsed.idempotencyKey !== 'string' || !parsed.payload || parsed.payload.taskType !== 'JOB_MATERIAL_SELECTION') return null
    return parsed as PendingGeneration
  } catch {
    return null
  }
}

function restoreGenerationForm(payload: MaterialSelectionRequest) {
  materialDecisions.value = {}
  for (const id of payload.input?.includedMaterialIds ?? []) materialDecisions.value[id] = 'must'
  for (const id of payload.input?.excludedMaterialIds ?? []) materialDecisions.value[id] = 'exclude'

  if (payload.jobDescriptionId) {
    jdMode.value = 'select'
    selectedJdId.value = payload.jobDescriptionId
  } else {
    jdMode.value = 'paste'
    pastedJdText.value = payload.jdText ?? ''
    companyName.value = payload.companyName ?? ''
    positionTitle.value = payload.positionTitle ?? ''
  }
  step.value = 3
}

async function submitGeneration(payload: MaterialSelectionRequest, idempotencyKey: string) {
  const res = await selectMaterialsForJob(payload, idempotencyKey)
  const task = res.data.data
  taskStore.remember(task.id)
  sessionStorage.removeItem(PENDING_GENERATION_KEY)
  await router.push(`/generate/materials?taskId=${task.id}`)
}

async function resumePendingGeneration() {
  const pending = readPendingGeneration()
  if (!pending) return

  restoreGenerationForm(pending.payload)
  generating.value = true
  error.value = ''
  try {
    const consent = (await getConsent()).data.data
    if (!hasJobGenerationConsent(consent)) {
      error.value = t('generationWorkbench.consentNotActive')
      return
    }
    await submitGeneration(pending.payload, pending.idempotencyKey)
  } catch (e: any) {
    error.value = e.response?.data?.message || e.message || t('generationWorkbench.generationFailed')
  } finally {
    generating.value = false
  }
}

async function startGeneration() {
  error.value = ''
  generating.value = true

  try {
    const payload = buildGenerationPayload()
    const idempotencyKey = `gen-workbench-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`
    const consent = (await getConsent()).data.data
    if (!hasJobGenerationConsent(consent)) {
      storePendingGeneration({ payload, idempotencyKey })
      await router.push({
        name: 'ai-consent',
        query: { redirect: '/generate?resumePending=1' },
      })
      return
    }

    await submitGeneration(payload, idempotencyKey)
  } catch (e: any) {
    error.value = e.response?.data?.message || e.message || t('generationWorkbench.generationFailed')
  } finally {
    generating.value = false
  }
}

const TYPE_LABELS = computed<Record<MaterialType, string>>(() => ({
  WORK_EXPERIENCE: t('generationWorkbench.typeWorkExperience'),
  PROJECT_EXPERIENCE: t('generationWorkbench.typeProjectExperience'),
  EDUCATION: t('generationWorkbench.typeEducation'),
  SKILL: t('generationWorkbench.typeSkill'),
  CERTIFICATE: t('generationWorkbench.typeCertificate'),
  AWARD: t('generationWorkbench.typeAward'),
  HIGHLIGHT: t('generationWorkbench.typeHighlight'),
  ACHIEVEMENT: t('generationWorkbench.typeAchievement'),
  LEADERSHIP_EXPERIENCE: t('generationWorkbench.typeLeadershipExperience'),
  SKILL_EVIDENCE: t('generationWorkbench.typeSkillEvidence'),
  VOLUNTEER_EXPERIENCE: t('generationWorkbench.typeVolunteerExperience'),
  COURSE: t('generationWorkbench.typeCourse'),
  PUBLICATION: t('generationWorkbench.typePublication'),
}))
</script>

<template>
  <main class="generation-workbench">
    <header class="workbench-header">
      <p class="eyebrow"><Sparkles :size="14" /> {{ t('generationWorkbench.eyebrow') }}</p>
      <h1>{{ t('generationWorkbench.title') }}</h1>
      <p class="subtitle">{{ t('generationWorkbench.subtitle') }}</p>
    </header>

    <ol class="steps-indicator" :aria-label="t('generationWorkbench.progressLabel')">
      <li :class="{ active: step === 1, done: step > 1 }"><span><Check v-if="step > 1" :size="13" /><template v-else>1</template></span><div><small>{{ t('generationWorkbench.stepLabel').replace('{step}', '1') }}</small><strong>{{ t('generationWorkbench.stepTargetJob') }}</strong></div></li>
      <li :class="{ active: step === 2, done: step > 2 }"><span><Check v-if="step > 2" :size="13" /><template v-else>2</template></span><div><small>{{ t('generationWorkbench.stepLabel').replace('{step}', '2') }}</small><strong>{{ t('generationWorkbench.stepMaterialScope') }}</strong></div></li>
      <li :class="{ active: step === 3 }"><span>3</span><div><small>{{ t('generationWorkbench.stepLabel').replace('{step}', '3') }}</small><strong>{{ t('generationWorkbench.stepAiSelection') }}</strong></div></li>
    </ol>

    <section v-if="step === 1" class="step-content workbench-panel">
      <header class="panel-heading"><span><BriefcaseBusiness :size="19" /></span><div><p class="section-kicker">{{ t('generationWorkbench.targetEyebrow') }}</p><h2>{{ t('generationWorkbench.targetTitle') }}</h2><p>{{ t('generationWorkbench.targetDescription') }}</p></div></header>
      <div class="mode-toggle" role="group" :aria-label="t('generationWorkbench.targetModeLabel')">
        <button type="button" :class="{ active: jdMode === 'select' }" :aria-pressed="jdMode === 'select'" @click="jdMode = 'select'"><Database :size="15" />{{ t('generationWorkbench.modeSelectExisting') }}</button>
        <button type="button" :class="{ active: jdMode === 'paste' }" :aria-pressed="jdMode === 'paste'" @click="jdMode = 'paste'"><FileText :size="15" />{{ t('generationWorkbench.modePasteJd') }}</button>
      </div>

      <div v-if="jdMode === 'select'" class="jd-select">
        <p v-if="jdStore.items.length === 0" class="empty-hint">
          {{ t('generationWorkbench.noSavedJd') }}
        </p>
        <div v-else class="jd-list">
          <label
            v-for="jd in jdStore.items"
            :key="jd.id"
            :class="['jd-card', { selected: selectedJdId === jd.id }]"
          >
            <input type="radio" :value="jd.id" v-model="selectedJdId" class="sr-only" />
            <span class="radio-mark"><Check v-if="selectedJdId === jd.id" :size="13" /></span>
            <span class="jd-copy"><strong>{{ jd.title }}</strong><small v-if="jd.companyName">{{ jd.companyName }}</small><p>{{ jd.jdText?.slice(0, 110) }}...</p></span>
          </label>
        </div>
      </div>

      <div v-else class="jd-paste">
        <div class="form-row">
          <label>{{ t('generationWorkbench.companyName') }}<input v-model="companyName" :placeholder="t('generationWorkbench.companyNamePlaceholder')" class="input" /></label>
          <label>{{ t('generationWorkbench.positionTitle') }}<input v-model="positionTitle" :placeholder="t('generationWorkbench.positionTitlePlaceholder')" class="input" /></label>
        </div>
        <label class="jd-text-field">{{ t('generationWorkbench.jdTextLabel') }}<textarea v-model="pastedJdText" :placeholder="t('generationWorkbench.jdTextPlaceholder')" class="textarea" rows="10"></textarea><span class="char-count">{{ pastedJdText.length }} {{ t('generationWorkbench.charUnit') }}</span></label>
      </div>

      <div class="step-actions">
        <span>{{ t('generationWorkbench.stepOneHint') }}</span>
        <button class="btn-neon btn-primary" :disabled="!canProceedStep1" @click="step = 2">
          {{ t('generationWorkbench.nextSelectMaterials') }} <ArrowRight :size="16" />
        </button>
      </div>
    </section>

    <section v-if="step === 2" class="step-content workbench-panel">
      <header class="panel-heading"><span><Database :size="19" /></span><div><p class="section-kicker">{{ t('generationWorkbench.scopeEyebrow') }}</p><h2>{{ t('generationWorkbench.scopeTitle') }}</h2><p>{{ t('generationWorkbench.step2Desc') }}</p></div></header>

      <div v-if="materialStore.items.length === 0" class="empty-hint">
        {{ t('generationWorkbench.emptyMaterialsPrefix') }}
        <router-link to="/career-materials">{{ t('generationWorkbench.emptyMaterialsLink') }}</router-link>
        {{ t('generationWorkbench.emptyMaterialsSuffix') }}
      </div>

      <div v-else class="material-list">
        <div
          v-for="m in materialStore.items"
          :key="m.id"
          :class="['material-card', materialDecisions[m.id] ?? 'default']"
        >
          <div class="material-info">
            <span class="material-type">{{ TYPE_LABELS[m.materialType] ?? m.materialType }}</span>
            <strong class="material-title">{{ m.title }}</strong>
            <small v-if="m.usagePreference === 'PREFERRED'" class="preference-badge preferred">{{ t('generationWorkbench.badgePreferred') }}</small>
            <small v-else-if="m.usagePreference === 'EXCLUDED'" class="preference-badge excluded">{{ t('generationWorkbench.badgeExcluded') }}</small>
          </div>
          <div class="material-actions">
            <button
              :class="['tag-btn', { active: (materialDecisions[m.id] ?? 'default') === 'must' }]"
              @click="setDecision(m.id, (materialDecisions[m.id] ?? 'default') === 'must' ? 'default' : 'must')"
            >{{ t('generationWorkbench.btnMustUse') }}</button>
            <button
              :class="['tag-btn exclude', { active: (materialDecisions[m.id] ?? 'default') === 'exclude' }]"
              @click="setDecision(m.id, (materialDecisions[m.id] ?? 'default') === 'exclude' ? 'default' : 'exclude')"
            >{{ t('generationWorkbench.btnExclude') }}</button>
          </div>
        </div>
      </div>

      <div class="selection-summary" v-if="materialStore.items.length > 0">
        <span><strong>{{ materialsByDecision.must.length }}</strong>{{ t('generationWorkbench.summaryMustPrefix') }}</span>
        <span><strong>{{ materialsByDecision.auto.length }}</strong>{{ t('generationWorkbench.summaryAutoPrefix') }}</span>
        <span><strong>{{ materialsByDecision.exclude.length }}</strong>{{ t('generationWorkbench.summaryExcludePrefix') }}</span>
      </div>

      <div class="step-actions">
        <button class="btn-neon btn-ghost" @click="step = 1"><ArrowLeft :size="16" />{{ t('generationWorkbench.prevStep') }}</button>
        <button class="btn-neon btn-primary" @click="step = 3" :disabled="materialStore.items.length === 0">
          {{ t('generationWorkbench.nextStartGeneration') }} <ArrowRight :size="16" />
        </button>
      </div>
    </section>

    <section v-if="step === 3" class="step-content workbench-panel review-panel">
      <header class="panel-heading"><span><ShieldCheck :size="19" /></span><div><p class="section-kicker">{{ t('generationWorkbench.reviewEyebrow') }}</p><h2>{{ t('generationWorkbench.confirmTitle') }}</h2><p>{{ t('generationWorkbench.reviewDescription') }}</p></div></header>
      <div class="generate-summary">
        <div class="summary-item">
          <span class="label">{{ t('generationWorkbench.labelTargetJob') }}</span>
          <span v-if="jdMode === 'select'">{{ selectedJd?.title }} {{ selectedJd?.companyName ? `@ ${selectedJd.companyName}` : '' }}</span>
          <span v-else>{{ positionTitle || t('generationWorkbench.customPosition') }} {{ companyName ? `@ ${companyName}` : '' }}</span>
        </div>
        <div class="summary-item">
          <span class="label">{{ t('generationWorkbench.labelMaterialScope') }}</span>
          <span>{{ t('generationWorkbench.scopeMust') }} {{ materialsByDecision.must.length }} / {{ t('generationWorkbench.scopeExclude') }} {{ materialsByDecision.exclude.length }} / {{ t('generationWorkbench.scopeAuto') }} {{ materialsByDecision.auto.length }}</span>
        </div>
        <div class="summary-item">
          <span class="label">{{ t('generationWorkbench.labelResumeName') }}</span>
          <span>{{ resolvedResumeTitle }}</span>
        </div>
      </div>

      <p v-if="error" class="error-msg">{{ error }}</p>

      <div class="step-actions">
        <button class="btn-neon btn-ghost" @click="step = 2"><ArrowLeft :size="16" />{{ t('generationWorkbench.prevStep') }}</button>
        <button class="btn-neon btn-primary btn-generate" @click="startGeneration" :disabled="generating">
          <span v-if="generating" class="spinner"></span>
          <Sparkles v-else :size="16" />{{ generating ? t('generationWorkbench.generating') : t('generationWorkbench.startGeneration') }}
        </button>
      </div>
    </section>
  </main>
</template>

<style scoped>
.workbench {
  max-width: 720px;
  margin: 0 auto;
  padding: 2rem 1rem;
}
.workbench-header h1 {
  font-size: 1.5rem;
  font-weight: 700;
  margin-bottom: 0.25rem;
}
.subtitle {
  color: #6b7280;
  margin-bottom: 1.5rem;
}
.steps-indicator {
  display: flex;
  align-items: center;
  margin-bottom: 2rem;
}
.step-dot {
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.step-dot .dot {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.8rem;
  font-weight: 600;
  background: #e5e7eb;
  color: #6b7280;
}
.step-dot.active .dot {
  background: #0e7490;
  color: #fff;
}
.step-dot.done .dot {
  background: #10b981;
  color: #fff;
}
.step-dot .label {
  font-size: 0.85rem;
  color: #6b7280;
}
.step-dot.active .label {
  color: #0e7490;
  font-weight: 600;
}
.step-line {
  flex: 1;
  height: 2px;
  background: #e5e7eb;
  margin: 0 0.75rem;
}
.step-line.active {
  background: #10b981;
}
.step-content {
  animation: fadeIn 0.2s ease;
}
@keyframes fadeIn {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: translateY(0); }
}
.mode-toggle {
  display: flex;
  gap: 0.5rem;
  margin-bottom: 1rem;
}
.mode-toggle button {
  padding: 0.5rem 1rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  background: #fff;
  cursor: pointer;
  font-size: 0.9rem;
}
.mode-toggle button.active {
  border-color: #0e7490;
  background: #ecfeff;
  color: #0e7490;
  font-weight: 600;
}
.jd-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  max-height: 320px;
  overflow-y: auto;
}
.jd-card {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 0.75rem 1rem;
  cursor: pointer;
  transition: border-color 0.15s;
}
.jd-card:hover {
  border-color: #0e7490;
}
.jd-card.selected {
  border-color: #0e7490;
  background: #ecfeff;
}
.jd-title {
  font-weight: 600;
}
.jd-company {
  font-size: 0.85rem;
  color: #6b7280;
}
.jd-preview {
  font-size: 0.8rem;
  color: #9ca3af;
  margin-top: 0.25rem;
}
.form-row {
  display: flex;
  gap: 0.75rem;
  margin-bottom: 0.75rem;
}
.input {
  flex: 1;
  padding: 0.5rem 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.9rem;
}
.textarea {
  width: 100%;
  padding: 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 8px;
  font-size: 0.9rem;
  resize: vertical;
  font-family: inherit;
}
.char-count {
  text-align: right;
  font-size: 0.8rem;
  color: #9ca3af;
  margin-top: 0.25rem;
}
.step-desc {
  color: #4b5563;
  margin-bottom: 1rem;
}
.material-list {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  max-height: 360px;
  overflow-y: auto;
}
.material-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 0.6rem 1rem;
  transition: border-color 0.15s;
}
.material-card.must {
  border-color: #10b981;
  background: #ecfdf5;
}
.material-card.exclude {
  border-color: #ef4444;
  background: #fef2f2;
  opacity: 0.7;
}
.material-info {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.material-type {
  font-size: 0.75rem;
  padding: 0.15rem 0.4rem;
  border-radius: 4px;
  background: #f3f4f6;
  color: #6b7280;
}
.material-title {
  font-size: 0.9rem;
}
.preference-badge {
  flex: 0 0 auto;
  padding: 0.12rem 0.42rem;
  border-radius: 4px;
  font-size: 0.7rem;
  font-weight: 600;
}
.preference-badge.preferred { background: #ecfdf5; color: #047857; }
.preference-badge.excluded { background: #f1f5f9; color: #64748b; }
.material-actions {
  display: flex;
  gap: 0.4rem;
}
.tag-btn {
  font-size: 0.75rem;
  padding: 0.25rem 0.5rem;
  border: 1px solid #d1d5db;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
}
.tag-btn.active {
  background: #10b981;
  border-color: #10b981;
  color: #fff;
}
.tag-btn.exclude.active {
  background: #ef4444;
  border-color: #ef4444;
}
.selection-summary {
  display: flex;
  gap: 1rem;
  margin-top: 1rem;
  font-size: 0.85rem;
  color: #6b7280;
}
.generate-summary {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 1.25rem;
  margin-bottom: 1.5rem;
}
.generate-summary h3 {
  font-size: 1rem;
  margin-bottom: 0.75rem;
}
.summary-item {
  margin-bottom: 0.4rem;
  font-size: 0.9rem;
}
.summary-item .label {
  color: #6b7280;
}
.step-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1.5rem;
}
.btn-primary {
  padding: 0.6rem 1.5rem;
  background: #0e7490;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 0.9rem;
  cursor: pointer;
  font-weight: 500;
}
.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-secondary {
  padding: 0.6rem 1.5rem;
  background: #fff;
  color: #374151;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.9rem;
  cursor: pointer;
}
.btn-generate {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
.error-msg {
  color: #dc2626;
  font-size: 0.85rem;
  margin-top: 0.75rem;
}
.empty-hint {
  color: #6b7280;
  padding: 2rem;
  text-align: center;
}
.empty-hint a {
  color: #0e7490;
}
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0,0,0,0);
}

/* Evidence-led generation workflow. */
.generation-workbench { display: grid; gap: 26px; width: min(100%, 980px); margin: 0 auto; padding: 8px 0 48px; }
.workbench-header { max-width: 720px; }
.workbench-header h1 { margin: 5px 0 7px; color: var(--text-primary); font-family: var(--font-display); font-size: 36px; font-weight: 700; letter-spacing: 0; }
.workbench-header .subtitle { margin: 0; color: var(--text-secondary); font-size: 13px; line-height: 1.7; }
.steps-indicator { display: grid; grid-template-columns: repeat(3, 1fr); margin: 0; padding: 0; border-block: 1px solid var(--border); list-style: none; }
.steps-indicator li { position: relative; display: grid; grid-template-columns: 28px minmax(0, 1fr); align-items: center; gap: 9px; min-height: 64px; padding: 11px 16px; color: var(--text-tertiary); }
.steps-indicator li:not(:last-child)::after { position: absolute; top: 16px; right: 0; bottom: 16px; width: 1px; background: var(--border-soft); content: ''; }
.steps-indicator li > span { display: grid; width: 26px; height: 26px; place-items: center; border: 1px solid var(--border); border-radius: 50%; background: var(--bg-surface); font-family: var(--font-utility); font-size: 10px; }
.steps-indicator li > div { display: grid; gap: 2px; min-width: 0; }
.steps-indicator li small { font-family: var(--font-utility); font-size: 8px; font-weight: 700; }
.steps-indicator li strong { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.steps-indicator li.active { color: var(--accent); box-shadow: inset 0 -3px 0 var(--accent); }
.steps-indicator li.active > span { border-color: var(--accent); color: #fff; background: var(--accent); }
.steps-indicator li.done { color: var(--text-primary); }
.steps-indicator li.done > span { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.step-content.workbench-panel { display: grid; gap: 22px; padding: 26px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); animation: workflow-enter .18s ease-out; }
.panel-heading { display: grid; grid-template-columns: 40px minmax(0, 1fr); align-items: start; gap: 12px; padding-bottom: 20px; border-bottom: 1px solid var(--border-soft); }
.panel-heading > span { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--accent); background: var(--accent-light); }
.section-kicker { margin: 0 0 3px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 10px; font-weight: 700; }
.panel-heading h2 { margin: 0; color: var(--text-primary); font-size: 17px; }
.panel-heading p:last-child { margin: 5px 0 0; color: var(--text-secondary); font-size: 11px; line-height: 1.55; }
.mode-toggle { display: inline-grid; grid-template-columns: 1fr 1fr; gap: 3px; width: min(100%, 430px); margin: 0; padding: 3px; border: 1px solid var(--border); border-radius: 6px; background: var(--bg-page); }
.mode-toggle button { display: inline-flex; align-items: center; justify-content: center; gap: 7px; min-height: 36px; padding: 7px 12px; border: 0; border-radius: 4px; color: var(--text-secondary); background: transparent; font-size: 11px; font-weight: 650; cursor: pointer; }
.mode-toggle button.active { border: 0; color: var(--accent); background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.jd-list, .material-list { display: grid; gap: 0; max-height: 390px; overflow-y: auto; border-top: 1px solid var(--border); scrollbar-width: thin; }
.jd-card { display: grid; grid-template-columns: 24px minmax(0, 1fr); align-items: start; gap: 11px; padding: 15px 8px; border: 0; border-bottom: 1px solid var(--border); border-radius: 0; background: transparent; cursor: pointer; }
.jd-card:hover { border-color: var(--border); background: color-mix(in srgb, var(--accent-light) 45%, transparent); }
.jd-card.selected { border-color: var(--border); background: var(--accent-light); box-shadow: inset 3px 0 0 var(--accent); }
.radio-mark { display: grid; width: 20px; height: 20px; place-items: center; border: 1px solid var(--border); border-radius: 50%; color: #fff; background: var(--bg-surface); }
.jd-card.selected .radio-mark { border-color: var(--accent); background: var(--accent); }
.jd-copy { display: grid; min-width: 0; gap: 3px; }
.jd-copy strong { color: var(--text-primary); font-size: 13px; }
.jd-copy small { color: var(--accent); font-size: 10px; font-weight: 650; }
.jd-copy p { display: -webkit-box; margin: 3px 0 0; overflow: hidden; color: var(--text-secondary); font-size: 10px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.jd-paste { display: grid; gap: 14px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; margin: 0; }
.form-row label, .jd-text-field { display: grid; gap: 6px; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.input, .textarea { width: 100%; padding: 10px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font: inherit; font-size: 13px; }
.textarea { resize: vertical; }
.input:focus, .textarea:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.jd-text-field { position: relative; }
.jd-text-field .char-count { justify-self: end; margin: -27px 10px 10px 0; padding: 2px 5px; color: var(--text-tertiary); background: var(--bg-input); font-family: var(--font-utility); font-size: 9px; font-weight: 500; }
.material-card { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 16px; min-height: 66px; padding: 12px 8px; border: 0; border-bottom: 1px solid var(--border); border-radius: 0; background: transparent; }
.material-card.must { border-color: var(--border); background: var(--accent-light); box-shadow: inset 3px 0 0 var(--accent); }
.material-card.exclude { border-color: var(--border); background: var(--danger-light); box-shadow: inset 3px 0 0 var(--danger); opacity: .72; }
.material-info { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; min-width: 0; }
.material-type, .preference-badge { min-height: 21px; padding: 3px 6px; border: 1px solid var(--border); border-radius: 4px; color: var(--text-secondary); background: var(--bg-page); font-size: 9px; font-weight: 700; }
.material-title { margin-right: 3px; overflow-wrap: anywhere; color: var(--text-primary); font-size: 12px; }
.preference-badge.preferred { border-color: color-mix(in srgb, var(--highlight) 35%, var(--border)); color: var(--highlight); background: var(--highlight-light); }
.preference-badge.excluded { color: var(--text-tertiary); }
.material-actions { display: flex; gap: 5px; }
.tag-btn { min-height: 31px; padding: 5px 8px; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); font-size: 10px; font-weight: 650; cursor: pointer; }
.tag-btn:hover { border-color: var(--accent); color: var(--accent); }
.tag-btn.active { border-color: var(--accent); color: #fff; background: var(--accent); }
.tag-btn.exclude.active { border-color: var(--danger); color: #fff; background: var(--danger); }
.selection-summary { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; margin: 0; padding: 12px; border: 1px solid var(--border-soft); border-radius: 6px; background: var(--bg-page); color: var(--text-secondary); font-size: 10px; }
.selection-summary span { display: flex; align-items: baseline; gap: 6px; }
.selection-summary strong { color: var(--text-primary); font-family: var(--font-utility); font-size: 16px; }
.generate-summary { display: grid; gap: 0; padding: 0; border: 0; border-radius: 0; background: transparent; }
.summary-item { display: grid; grid-template-columns: minmax(140px, .35fr) minmax(0, 1fr); gap: 20px; padding: 15px 4px; border-bottom: 1px solid var(--border-soft); color: var(--text-primary); font-size: 12px; }
.summary-item .label { color: var(--text-tertiary); font-size: 10px; font-weight: 650; }
.step-actions { display: flex; align-items: center; justify-content: flex-end; gap: 9px; margin: 0; padding-top: 18px; border-top: 1px solid var(--border-soft); }
.step-actions > span { margin-right: auto; color: var(--text-tertiary); font-size: 10px; }
.step-actions .btn-neon { min-height: 38px; padding: 0 14px; }
.step-actions .btn-neon.btn-primary { border-color: var(--accent); color: #fff; background: var(--accent); }
.step-actions .btn-neon.btn-primary:hover:not(:disabled) { border-color: var(--accent-hover); background: var(--accent-hover); }
.empty-hint { margin: 0; padding: 28px 18px; border-block: 1px solid var(--border-soft); color: var(--text-secondary); background: transparent; font-size: 12px; text-align: center; }
.empty-hint a { color: var(--accent); }
.error-msg { margin: 0; padding: 11px 13px; border: 1px solid color-mix(in srgb, var(--danger) 25%, var(--border)); border-radius: 6px; color: var(--danger); background: var(--danger-light); font-size: 11px; }
@keyframes workflow-enter { from { opacity: 0; transform: translateY(5px); } to { opacity: 1; transform: none; } }
@media (max-width: 680px) { .generation-workbench { gap: 20px; padding-top: 0; } .workbench-header h1 { font-size: 30px; } .steps-indicator li { grid-template-columns: 24px minmax(0, 1fr); gap: 6px; padding: 9px 7px; } .steps-indicator li > span { width: 23px; height: 23px; } .steps-indicator li small { display: none; } .steps-indicator li strong { font-size: 9px; white-space: normal; } .step-content.workbench-panel { padding: 20px 16px; } .form-row { grid-template-columns: 1fr; } .material-card { align-items: stretch; grid-template-columns: 1fr; } .material-actions { display: grid; grid-template-columns: 1fr 1fr; } .tag-btn { width: 100%; } .selection-summary { grid-template-columns: 1fr; } .summary-item { grid-template-columns: 1fr; gap: 4px; } .step-actions { align-items: stretch; flex-direction: column-reverse; } .step-actions .btn-neon { width: 100%; justify-content: center; } .step-actions > span { margin: 0; text-align: center; } }
@media (prefers-reduced-motion: reduce) { .step-content.workbench-panel { animation: none; } }
</style>
