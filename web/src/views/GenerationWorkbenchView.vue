<script setup lang="ts">
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useCareerMaterialStore } from '@/stores/careerMaterial'
import { useJobDescriptionStore } from '@/stores/jobDescription'
import { useAiTaskStore } from '@/stores/aiTask'
import { getConsent, hasJobGenerationConsent, selectMaterialsForJob, type MaterialSelectionRequest } from '@/api/ai'
import type { CareerMaterialSummary, MaterialType } from '@/api/careerMaterial'

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
  return position || company || '岗位定制简历'
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
      error.value = 'AI 授权尚未生效，请重新授权后再试。'
      return
    }
    await submitGeneration(pending.payload, pending.idempotencyKey)
  } catch (e: any) {
    error.value = e.response?.data?.message || e.message || '生成请求失败'
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
    error.value = e.response?.data?.message || e.message || '生成请求失败'
  } finally {
    generating.value = false
  }
}

const TYPE_LABELS: Record<MaterialType, string> = {
  WORK_EXPERIENCE: '工作经历',
  PROJECT_EXPERIENCE: '项目经历',
  EDUCATION: '教育背景',
  SKILL: '技能',
  CERTIFICATE: '证书',
  AWARD: '荣誉',
  HIGHLIGHT: '亮点',
  ACHIEVEMENT: '量化成果',
  LEADERSHIP_EXPERIENCE: '管理 / 协作经历',
  SKILL_EVIDENCE: '技能证据',
  VOLUNTEER_EXPERIENCE: '志愿 / 实习经历',
  COURSE: '培训课程',
  PUBLICATION: '研究成果',
}
</script>

<template>
  <div class="workbench">
    <header class="workbench-header">
      <h1>根据目标岗位生成简历</h1>
      <p class="subtitle">从你的资料库出发，AI 为你定制一份岗位简历</p>
    </header>

    <!-- Step indicators -->
    <div class="steps-indicator">
      <div :class="['step-dot', { active: step === 1, done: step > 1 }]">
        <span class="dot">1</span>
        <span class="label">确定目标岗位</span>
      </div>
      <div class="step-line" :class="{ active: step > 1 }"></div>
      <div :class="['step-dot', { active: step === 2, done: step > 2 }]">
        <span class="dot">2</span>
        <span class="label">选择资料范围</span>
      </div>
      <div class="step-line" :class="{ active: step > 2 }"></div>
      <div :class="['step-dot', { active: step === 3 }]">
        <span class="dot">3</span>
        <span class="label">AI 选材</span>
      </div>
    </div>

    <!-- Step 1: JD Selection -->
    <section v-if="step === 1" class="step-content">
      <div class="mode-toggle">
        <button :class="{ active: jdMode === 'select' }" @click="jdMode = 'select'">选择已有岗位</button>
        <button :class="{ active: jdMode === 'paste' }" @click="jdMode = 'paste'">粘贴 JD</button>
      </div>

      <div v-if="jdMode === 'select'" class="jd-select">
        <p v-if="jdStore.items.length === 0" class="empty-hint">
          暂无已保存的岗位描述，请切换到"粘贴 JD"模式。
        </p>
        <div v-else class="jd-list">
          <label
            v-for="jd in jdStore.items"
            :key="jd.id"
            :class="['jd-card', { selected: selectedJdId === jd.id }]"
          >
            <input type="radio" :value="jd.id" v-model="selectedJdId" class="sr-only" />
            <div class="jd-title">{{ jd.title }}</div>
            <div class="jd-company" v-if="jd.companyName">{{ jd.companyName }}</div>
            <div class="jd-preview">{{ jd.jdText?.slice(0, 80) }}...</div>
          </label>
        </div>
      </div>

      <div v-else class="jd-paste">
        <div class="form-row">
          <input v-model="companyName" placeholder="公司名称（可选）" class="input" />
          <input v-model="positionTitle" placeholder="岗位名称（推荐）" class="input" />
        </div>
        <textarea
          v-model="pastedJdText"
          placeholder="粘贴职位描述原文（至少 20 字）..."
          class="textarea"
          rows="10"
        ></textarea>
        <p class="char-count">{{ pastedJdText.length }} 字</p>
      </div>

      <div class="step-actions">
        <button class="btn-primary" :disabled="!canProceedStep1" @click="step = 2">
          下一步：选择资料
        </button>
      </div>
    </section>

    <!-- Step 2: Material Selection -->
    <section v-if="step === 2" class="step-content">
      <p class="step-desc">
        标记必须使用或不使用的资料，其余交给 AI 根据岗位相关性自动判断。
      </p>

      <div v-if="materialStore.items.length === 0" class="empty-hint">
        资料库为空。建议先
        <router-link to="/career-materials">完善资料库</router-link>
        再生成简历。
      </div>

      <div v-else class="material-list">
        <div
          v-for="m in materialStore.items"
          :key="m.id"
          :class="['material-card', materialDecisions[m.id] ?? 'default']"
        >
          <div class="material-info">
            <span class="material-type">{{ TYPE_LABELS[m.materialType] ?? m.materialType }}</span>
            <span class="material-title">{{ m.title }}</span>
            <span v-if="m.usagePreference === 'PREFERRED'" class="preference-badge preferred">优先资料</span>
            <span v-else-if="m.usagePreference === 'EXCLUDED'" class="preference-badge excluded">默认排除</span>
          </div>
          <div class="material-actions">
            <button
              :class="['tag-btn', { active: (materialDecisions[m.id] ?? 'default') === 'must' }]"
              @click="setDecision(m.id, (materialDecisions[m.id] ?? 'default') === 'must' ? 'default' : 'must')"
            >必须使用</button>
            <button
              :class="['tag-btn exclude', { active: (materialDecisions[m.id] ?? 'default') === 'exclude' }]"
              @click="setDecision(m.id, (materialDecisions[m.id] ?? 'default') === 'exclude' ? 'default' : 'exclude')"
            >不使用</button>
          </div>
        </div>
      </div>

      <div class="selection-summary" v-if="materialStore.items.length > 0">
        <span>必须使用 {{ materialsByDecision.must.length }} 条</span>
        <span>不使用 {{ materialsByDecision.exclude.length }} 条</span>
        <span>AI 自动判断 {{ materialsByDecision.auto.length }} 条</span>
      </div>

      <div class="step-actions">
        <button class="btn-secondary" @click="step = 1">上一步</button>
        <button class="btn-primary" @click="step = 3" :disabled="materialStore.items.length === 0">
          下一步：开始生成
        </button>
      </div>
    </section>

    <!-- Step 3: Generate -->
    <section v-if="step === 3" class="step-content">
      <div class="generate-summary">
        <h3>选材配置确认</h3>
        <div class="summary-item">
          <span class="label">目标岗位：</span>
          <span v-if="jdMode === 'select'">{{ selectedJd?.title }} {{ selectedJd?.companyName ? `@ ${selectedJd.companyName}` : '' }}</span>
          <span v-else>{{ positionTitle || '自定义岗位' }} {{ companyName ? `@ ${companyName}` : '' }}</span>
        </div>
        <div class="summary-item">
          <span class="label">资料范围：</span>
          <span>必须 {{ materialsByDecision.must.length }} / 排除 {{ materialsByDecision.exclude.length }} / 自动 {{ materialsByDecision.auto.length }}</span>
        </div>
        <div class="summary-item">
          <span class="label">简历命名：</span>
          <span>{{ resolvedResumeTitle }}</span>
        </div>
      </div>

      <p v-if="error" class="error-msg">{{ error }}</p>

      <div class="step-actions">
        <button class="btn-secondary" @click="step = 2">上一步</button>
        <button class="btn-primary btn-generate" @click="startGeneration" :disabled="generating">
          <span v-if="generating" class="spinner"></span>
          {{ generating ? '正在分析资料...' : '开始 AI 选材' }}
        </button>
      </div>
    </section>
  </div>
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
</style>
