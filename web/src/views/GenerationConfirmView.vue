<script setup lang="ts">
import { onMounted, onUnmounted, ref, computed, toRaw } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAiTaskStore } from '@/stores/aiTask'
import { getTask, confirmTask, rejectTask, retryTask } from '@/api/ai'
import { listResumesByJd, type ResumeSummary } from '@/api/resume'
import DraftContentFields from '@/components/DraftContentFields.vue'
import {
  AlertCircle,
  Check,
  CheckCircle2,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Circle,
  ClipboardCheck,
  ListFilter,
  Menu,
  Pencil,
  Sparkles,
  Trash2,
  X,
} from 'lucide-vue-next'
import { useLocale } from '@/i18n'

const { t } = useLocale()

// data identifier for material-library source
const MATERIAL_SOURCE = '\u8D44\u6599\u5E93'
const REVIEWABLE_SECTIONS = [
  'basics', 'work', 'education', 'skills', 'projects', 'certificates',
  'objective', 'volunteering', 'courses', 'publications', 'customSections',
] as const
type ReviewableSection = (typeof REVIEWABLE_SECTIONS)[number]

const route = useRoute()
const router = useRouter()
const taskStore = useAiTaskStore()

const loading = ref(true)
const error = ref('')
const confirming = ref(false)
const rejecting = ref(false)

// Task data
const task = ref<any>(null)
const resultJson = ref<any>(null)

// Draft sections for display
interface DraftItem {
  path: string
  section: ReviewableSection
  content: any
  provenance: Record<string, unknown>
  source: string | null
  pending: string | null
  decision: 'ACCEPT' | 'EDIT' | 'REJECT' | null
  editedValue: any
}

interface QualitySummary {
  totalDraftItems: number
  sourcedItems: number
  pendingItems: number
  unsupportedItems: number
  draftGapCount: number
  missingRequirementCount: number
  readiness: 'READY' | 'REVIEW_RECOMMENDED' | 'REQUIRES_ACTION'
}

interface MissingInfo {
  section: string
  reason: string
}

const draftItems = ref<DraftItem[]>([])
const selectedInfo = ref<any[]>([])
const unselectedInfo = ref<any[]>([])
const missingInfo = ref<MissingInfo[]>([])
const warnings = ref<string[]>([])
const qualitySummary = ref<QualitySummary | null>(null)

// Same-JD dialog
const showJdDialog = ref(false)
const existingResumes = ref<ResumeSummary[]>([])
const resumeTitle = ref('')

// Edit dialog
const showEditDialog = ref(false)
const editingItem = ref<DraftItem | null>(null)
const editValue = ref<unknown>(null)
const activeSection = ref<ReviewableSection | null>(null)
const attentionOnly = ref(false)
const qualityExpanded = ref(false)
const mobileNavigationOpen = ref(false)

// Resume title input
const customTitle = ref('')

let pollTimer: ReturnType<typeof setTimeout> | null = null

onMounted(async () => {
  const tid = route.query.taskId
  if (!tid) {
    error.value = t('generationConfirm.missingTaskId')
    loading.value = false
    return
  }
  await loadTask(Number(tid))
})

onUnmounted(() => {
  if (pollTimer) clearTimeout(pollTimer)
})

async function loadTask(id: number) {
  loading.value = true
  error.value = ''
  try {
    const res = await getTask(id)
    task.value = res.data.data
    if (task.value.status === 'SUCCESS' && task.value.confirmationStatus === 'PENDING') {
      resultJson.value = task.value.resultJson
      parseDraft()
    } else if (task.value.status === 'PENDING' || task.value.status === 'RUNNING') {
      startPolling(id)
    } else if (task.value.status === 'FAILED') {
      error.value = task.value.errorMessage || t('generationConfirm.aiGenerationFailed')
    }
  } catch (e: any) {
    error.value = e.response?.data?.message || t('generationConfirm.loadTaskFailed')
  } finally {
    loading.value = false
  }
}

function startPolling(id: number) {
  const poll = async () => {
    try {
      const res = await getTask(id)
      task.value = res.data.data
      if (task.value.status === 'SUCCESS') {
        resultJson.value = task.value.resultJson
        parseDraft()
        loading.value = false
        return
      }
      if (task.value.status === 'FAILED') {
        error.value = task.value.errorMessage || t('generationConfirm.aiGenerationFailed')
        loading.value = false
        return
      }
      pollTimer = setTimeout(poll, 2000)
    } catch {
      pollTimer = setTimeout(poll, 3000)
    }
  }
  pollTimer = setTimeout(poll, 1500)
}

function parseDraft() {
  if (!resultJson.value) return
  const draft = resultJson.value.draftResumeJson
  selectedInfo.value = resultJson.value.selected ?? []
  unselectedInfo.value = resultJson.value.unselected ?? []
  missingInfo.value = resultJson.value.missing ?? []
  warnings.value = resultJson.value.warnings ?? []
  qualitySummary.value = normalizeQualitySummary(resultJson.value.qualitySummary)

  // Flatten draft into items by section
  const items: DraftItem[] = []
  for (const section of REVIEWABLE_SECTIONS) {
    const data = draft[section]
    if (!data) continue
    if (Array.isArray(data)) {
      data.forEach((entry: any, idx: number) => {
        const path = `${section}[${idx}]`
        items.push({
          path,
          section,
          content: stripMeta(entry),
          provenance: sourceMeta(entry),
          source: entry._source ?? (entry._sources ? MATERIAL_SOURCE : null),
          pending: entry._pending?.reason ?? (typeof entry._pending === 'string' ? entry._pending : null),
          decision: entry._pending ? null : 'ACCEPT',
          editedValue: null,
        })
      })
    } else if (typeof data === 'object') {
      const path = section
      items.push({
        path,
        section,
        content: stripMeta(data),
        provenance: sourceMeta(data),
        source: data._source ?? (data._sources ? MATERIAL_SOURCE : null),
        pending: data._pending?.reason ?? (typeof data._pending === 'string' ? data._pending : null),
        decision: data._pending ? null : 'ACCEPT',
        editedValue: null,
      })
    }
  }
  draftItems.value = items
  activeSection.value = items.find(item => item.decision === null)?.section ?? items[0]?.section ?? null
}

function normalizeQualitySummary(value: unknown): QualitySummary | null {
  if (!value || typeof value !== 'object') return null
  const summary = value as Partial<QualitySummary>
  const numericKeys: Array<keyof Pick<QualitySummary,
    'totalDraftItems' | 'sourcedItems' | 'pendingItems' | 'unsupportedItems' | 'draftGapCount' | 'missingRequirementCount'>> = [
    'totalDraftItems', 'sourcedItems', 'pendingItems', 'unsupportedItems', 'draftGapCount', 'missingRequirementCount',
  ]
  if (!numericKeys.every(key => typeof summary[key] === 'number') || typeof summary.readiness !== 'string') return null
  if (!['READY', 'REVIEW_RECOMMENDED', 'REQUIRES_ACTION'].includes(summary.readiness)) return null
  return summary as QualitySummary
}

const QUALITY_READINESS = computed<Record<QualitySummary['readiness'], { label: string; hint: string }>>(() => ({
  READY: { label: t('generationConfirm.qualityReadyLabel'), hint: t('generationConfirm.qualityReadyHint') },
  REVIEW_RECOMMENDED: { label: t('generationConfirm.qualityReviewLabel'), hint: t('generationConfirm.qualityReviewHint') },
  REQUIRES_ACTION: { label: t('generationConfirm.qualityActionLabel'), hint: t('generationConfirm.qualityActionHint') },
}))

function stripMeta(value: any): any {
  if (Array.isArray(value)) return value.map(stripMeta)
  if (value === null || typeof value !== 'object') return value
  return Object.fromEntries(
    Object.entries(value)
      .filter(([key]) => key !== '_source' && key !== '_sources' && key !== '_pending')
      .map(([key, nestedValue]) => [key, stripMeta(nestedValue)]),
  )
}

function sourceMeta(value: any): Record<string, unknown> {
  if (value?._source) return { _source: value._source }
  if (value?._sources) return { _sources: value._sources }
  if (value?._pending) return { _pending: value._pending }
  return {}
}

const SECTION_LABELS = computed<Record<ReviewableSection, string>>(() => ({
  basics: t('generationConfirm.sectionBasics'),
  work: t('generationConfirm.sectionWork'),
  education: t('generationConfirm.sectionEducation'),
  skills: t('generationConfirm.sectionSkills'),
  projects: t('generationConfirm.sectionProjects'),
  certificates: t('generationConfirm.sectionCertificates'),
  objective: t('generationConfirm.sectionObjective'),
  volunteering: t('generationConfirm.sectionVolunteering'),
  courses: t('generationConfirm.sectionCourses'),
  publications: t('generationConfirm.sectionPublications'),
  customSections: t('generationConfirm.sectionCustomSections'),
}))

const SECTION_EDIT_TEMPLATES: Record<ReviewableSection, Record<string, unknown>> = {
  basics: { name: '', title: '', email: '', phone: '', location: '', summary: '' },
  work: { company: '', position: '', period: '', description: '', highlights: [] },
  education: { school: '', degree: '', major: '', period: '' },
  skills: { name: '', level: '' },
  projects: { name: '', role: '', period: '', description: '', highlights: [] },
  certificates: { name: '', issuer: '', date: '', credentialId: '' },
  objective: { targetRole: '', targetIndustry: '', location: '', summary: '' },
  volunteering: { organization: '', position: '', startDate: '', endDate: '', summary: '', highlights: [] },
  courses: { name: '', provider: '', date: '', description: '' },
  publications: { title: '', publisher: '', date: '', url: '', description: '' },
  customSections: { title: '', entries: [] },
}

const groupedItems = computed(() => {
  const groups: Partial<Record<ReviewableSection, DraftItem[]>> = {}
  for (const item of draftItems.value) {
    (groups[item.section] ??= []).push(item)
  }
  return groups
})

const pendingCount = computed(() =>
  draftItems.value.filter(i => i.decision === null).length
)

const processedCount = computed(() => draftItems.value.length - pendingCount.value)

function matchesMissingSection(section: ReviewableSection, missing: MissingInfo) {
  const missingSection = String(missing?.section ?? '').trim().toLocaleLowerCase()
  return missingSection === section.toLocaleLowerCase()
    || missingSection === (SECTION_LABELS.value[section] ?? '').toLocaleLowerCase()
}

const sectionEntries = computed(() => REVIEWABLE_SECTIONS
  .filter(section => (groupedItems.value[section]?.length ?? 0) > 0
    || missingInfo.value.some(missing => matchesMissingSection(section, missing)))
  .map(section => {
    const items = groupedItems.value[section] ?? []
    const unresolved = items.filter(item => item.decision === null).length
    const missing = missingInfo.value.filter(entry => matchesMissingSection(section, entry)).length
    return {
      key: section,
      label: SECTION_LABELS.value[section] ?? section,
      count: items.length,
      rejected: items.length > 0 && items.every(item => item.decision === 'REJECT'),
      needsAttention: unresolved > 0 || missing > 0,
    }
  }))

const visibleSectionEntries = computed(() => attentionOnly.value
  ? sectionEntries.value.filter(section => section.needsAttention)
  : sectionEntries.value)
const attentionSectionCount = computed(() => sectionEntries.value.filter(section => section.needsAttention).length)

const activeEntry = computed(() => sectionEntries.value.find(section => section.key === activeSection.value)
  ?? sectionEntries.value[0])
const activeItems = computed(() => activeEntry.value ? (groupedItems.value[activeEntry.value.key] ?? []) : [])
const activeMissingInfo = computed(() => missingInfo.value.filter(entry => activeEntry.value
  && matchesMissingSection(activeEntry.value.key, entry)))
const activeSectionIndex = computed(() => sectionEntries.value.findIndex(section => section.key === activeEntry.value?.key))
const activeNavigationIndex = computed(() => visibleSectionEntries.value
  .findIndex(section => section.key === activeEntry.value?.key))

function selectSection(section: ReviewableSection) {
  activeSection.value = section
  mobileNavigationOpen.value = false
}

function moveSection(offset: number) {
  const next = visibleSectionEntries.value[activeNavigationIndex.value + offset]
  if (next) selectSection(next.key)
}

function toggleAttentionOnly() {
  attentionOnly.value = !attentionOnly.value
  if (attentionOnly.value && !activeEntry.value?.needsAttention) {
    const nextAttention = sectionEntries.value.find(section => section.needsAttention)
    if (nextAttention) selectSection(nextAttention.key)
  }
}

function openEdit(item: DraftItem) {
  editingItem.value = item
  const content = structuredClone(toRaw(item.content))
  editValue.value = content !== null && typeof content === 'object' && !Array.isArray(content)
    ? { ...(SECTION_EDIT_TEMPLATES[item.section] ?? {}), ...content }
    : content
  showEditDialog.value = true
}

function saveEdit() {
  if (!editingItem.value) return
  const cleanedValue = removeEmptyFields(editValue.value)
  editingItem.value.editedValue = Object.assign({}, cleanedValue as Record<string, unknown>, editingItem.value.provenance)
  editingItem.value.content = cleanedValue
  editingItem.value.decision = 'EDIT'
  showEditDialog.value = false
  editingItem.value = null
}

function closeEdit() {
  showEditDialog.value = false
  editingItem.value = null
}

function removeEmptyFields(value: unknown): unknown {
  if (Array.isArray(value)) {
    return value.map(removeEmptyFields).filter(item => !isEmptyValue(item))
  }
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value)
        .map(([key, nestedValue]) => [key, removeEmptyFields(nestedValue)])
        .filter(([, nestedValue]) => !isEmptyValue(nestedValue)),
    )
  }
  return value
}

function isEmptyValue(value: unknown) {
  if (value === null || value === undefined || value === '') return true
  if (Array.isArray(value)) return value.length === 0
  return typeof value === 'object' && Object.keys(value).length === 0
}

function setDecision(item: DraftItem, decision: 'ACCEPT' | 'REJECT') {
  item.decision = decision
  if (decision === 'ACCEPT') item.editedValue = null
  if (attentionOnly.value) {
    const current = sectionEntries.value.find(section => section.key === activeSection.value)
    if (!current?.needsAttention) {
      const nextAttention = sectionEntries.value.find(section => section.needsAttention)
      if (nextAttention) selectSection(nextAttention.key)
    }
  }
}

async function handleConfirm() {
  if (pendingCount.value > 0) {
    error.value = t('generationConfirm.pendingItemsError').replace('{count}', String(pendingCount.value))
    return
  }

  // Check if same JD has existing resumes
  const jdId = extractJdId()
  if (jdId) {
    try {
      const res = await listResumesByJd(jdId)
      if (res.data.data && res.data.data.length > 0) {
        existingResumes.value = res.data.data
        showJdDialog.value = true
        return
      }
    } catch { /* ignore, proceed with new */ }
  }

  await doConfirm(null)
}

function extractJdId(): number | null {
  const jdId = task.value?.jobDescriptionId
  return typeof jdId === 'number' && jdId > 0 ? jdId : null
}

async function doConfirm(targetResumeId: number | null) {
  showJdDialog.value = false
  confirming.value = true
  error.value = ''

  try {
    const items = draftItems.value.map(item => ({
      outputPath: item.path,
      decision: item.decision ?? 'ACCEPT',
      ...(item.decision === 'EDIT' && item.editedValue ? { editedValue: item.editedValue } : {}),
    }))

    const idempotencyKey = `confirm-${task.value.id}-${Date.now()}`
    const res = await confirmTask(task.value.id, {
      taskUpdatedAt: task.value.updatedAt,
      items,
      resumeTitle: customTitle.value || undefined,
      targetResumeId,
    }, idempotencyKey)

    const data = res.data.data
    taskStore.clear()
    // Navigate to resume detail
    router.push(`/resumes/${data.resumeId}`)
  } catch (e: any) {
    error.value = e.response?.data?.message || t('generationConfirm.confirmFailed')
  } finally {
    confirming.value = false
  }
}

async function handleReject() {
  if (!window.confirm(t('generationConfirm.rejectConfirm'))) return
  rejecting.value = true
  try {
    await rejectTask(task.value.id, task.value.updatedAt)
    taskStore.clear()
    router.push('/generate')
  } catch (e: any) {
    error.value = e.response?.data?.message || t('generationConfirm.operationFailed')
  } finally {
    rejecting.value = false
  }
}

async function handleRetry() {
  error.value = ''
  try {
    await retryTask(task.value.id)
    task.value.status = 'PENDING'
    loading.value = true
    startPolling(task.value.id)
  } catch (e: any) {
    error.value = e.response?.data?.message || t('generationConfirm.retryFailed')
  }
}
</script>

<template>
  <div class="confirm-page">
    <header class="confirm-header">
      <p class="eyebrow"><ClipboardCheck :size="14" /> {{ t('generationConfirm.eyebrow') }}</p>
      <h1>{{ t('generationConfirm.pageTitle') }}</h1>
      <p class="subtitle">{{ t('generationConfirm.pageSubtitle') }}</p>
      <div class="confirm-route" aria-hidden="true"><span class="done"><Check :size="12" />{{ t('generationWorkbench.stepTargetJob') }}</span><i></i><span class="done"><Check :size="12" />{{ t('generationWorkbench.stepMaterialScope') }}</span><i></i><span class="active">{{ t('generationConfirm.reviewStep') }}</span></div>
    </header>

    <!-- Loading / Polling -->
    <div v-if="loading" class="status-card">
      <div class="spinner-lg"></div>
      <Sparkles :size="20" />
      <p>{{ t('generationConfirm.generating') }}</p>
      <p class="hint">{{ t('generationConfirm.generatingHint') }}</p>
    </div>

    <!-- Error / Failed -->
    <div v-else-if="error && (!task || task.status === 'FAILED')" class="status-card error">
      <p class="error-text">{{ error }}</p>
      <button class="btn-primary" @click="handleRetry">{{ t('generationConfirm.retryGenerate') }}</button>
    </div>

    <!-- Draft confirmation -->
    <div v-else-if="task && task.status === 'SUCCESS'" class="draft-container" @keydown.esc="mobileNavigationOpen = false">
      <section
        v-if="qualitySummary"
        :class="['quality-summary', `quality-summary--${qualitySummary.readiness.toLowerCase()}`]"
        :aria-label="t('generationConfirm.qualitySummaryAriaLabel')"
      >
        <div class="quality-summary__heading">
          <div>
            <h3>{{ t('generationConfirm.qualitySummaryTitle') }}</h3>
            <p>{{ processedCount }}/{{ draftItems.length }} {{ t('generationConfirm.reviewedCount') }}</p>
          </div>
          <div class="quality-summary__controls">
            <span class="quality-summary__status">{{ QUALITY_READINESS[qualitySummary.readiness].label }}</span>
            <button
              class="icon-button"
              :class="{ active: qualityExpanded }"
              :aria-expanded="qualityExpanded"
              :aria-label="t('generationConfirm.toggleQualityDetails')"
              :title="t('generationConfirm.toggleQualityDetails')"
              @click="qualityExpanded = !qualityExpanded"
            ><ChevronDown :size="16" /></button>
          </div>
        </div>
        <div class="quality-summary__metrics">
          <div><strong>{{ qualitySummary.sourcedItems }}</strong><span>{{ t('generationConfirm.hasSource') }}</span></div>
          <div><strong>{{ qualitySummary.draftGapCount }}</strong><span>{{ t('generationConfirm.draftPending') }}</span></div>
          <div><strong>{{ qualitySummary.unsupportedItems }}</strong><span>{{ t('generationConfirm.pendingReview') }}</span></div>
          <div><strong>{{ qualitySummary.missingRequirementCount }}</strong><span>{{ t('generationConfirm.uncovered') }}</span></div>
        </div>
        <div v-if="qualityExpanded" class="quality-summary__details">
          <p>{{ QUALITY_READINESS[qualitySummary.readiness].hint }}</p>
          <p v-for="warning in warnings" :key="warning" class="warning-item">{{ warning }}</p>
          <div v-if="missingInfo.length" class="missing-summary">
            <strong>{{ t('generationConfirm.missingInfoTitle') }}</strong>
            <ul>
              <li v-for="(missing, index) in missingInfo" :key="index">
                {{ missing.section }}：{{ missing.reason }}
              </li>
            </ul>
          </div>
        </div>
      </section>

      <div v-if="!qualitySummary && (warnings.length || missingInfo.length)" class="review-notices">
        <p v-for="warning in warnings" :key="warning" class="warning-item">{{ warning }}</p>
        <div v-if="missingInfo.length" class="missing-summary">
          <strong>{{ t('generationConfirm.missingInfoTitle') }}</strong>
          <ul>
            <li v-for="(missing, index) in missingInfo" :key="index">{{ missing.section }}：{{ missing.reason }}</li>
          </ul>
        </div>
      </div>

      <button
        class="mobile-outline-trigger"
        :aria-expanded="mobileNavigationOpen"
        @click="mobileNavigationOpen = true"
      >
        <Menu :size="17" />
        <span>{{ activeEntry?.label }}</span>
        <b>{{ activeSectionIndex + 1 }}/{{ sectionEntries.length }}</b>
      </button>

      <div class="review-workspace">
        <aside :class="['review-rail', { open: mobileNavigationOpen }]">
          <div class="review-rail__heading">
            <div>
              <span>{{ t('generationConfirm.sectionNavigator') }}</span>
              <strong>{{ processedCount }}/{{ draftItems.length }}</strong>
            </div>
            <button
              class="icon-button mobile-only"
              :aria-label="t('common.close')"
              :title="t('common.close')"
              @click="mobileNavigationOpen = false"
            ><X :size="17" /></button>
          </div>
          <button
            class="attention-filter"
            :class="{ active: attentionOnly }"
            :aria-pressed="attentionOnly"
            @click="toggleAttentionOnly"
          >
            <ListFilter :size="15" />
            <span>{{ t('generationConfirm.attentionOnly') }}</span>
            <b>{{ attentionSectionCount }}</b>
          </button>
          <nav class="section-navigation" :aria-label="t('generationConfirm.sectionNavigationAria')">
            <button
              v-for="section in visibleSectionEntries"
              :key="section.key"
              :class="['section-navigation__item', { active: activeEntry?.key === section.key }]"
              :aria-current="activeEntry?.key === section.key ? 'step' : undefined"
              @click="selectSection(section.key)"
            >
              <AlertCircle v-if="section.needsAttention" :size="15" class="attention" />
              <Circle v-else-if="section.rejected" :size="15" class="rejected" />
              <CheckCircle2 v-else :size="15" />
              <span>{{ section.label }}</span>
              <b>{{ section.count }}</b>
            </button>
          </nav>
          <p v-if="attentionOnly && !visibleSectionEntries.length" class="rail-empty">
            {{ t('generationConfirm.noAttentionItems') }}
          </p>
          <details v-if="unselectedInfo.length" class="unselected-details">
            <summary>{{ t('generationConfirm.unusedMaterials').replace('{count}', String(unselectedInfo.length)) }}</summary>
            <ul>
              <li v-for="(unused, index) in unselectedInfo" :key="index">
                {{ unused.title || t('generationConfirm.unnamedMaterial') }}：{{ unused.unselectedReason }}
              </li>
            </ul>
          </details>
        </aside>

        <section class="review-stage">
          <header class="review-stage__heading">
            <div>
              <p>{{ t('generationConfirm.currentSection') }}</p>
              <h2>{{ activeEntry?.label }}</h2>
            </div>
            <span>{{ activeItems.length }} {{ t('generationConfirm.itemUnit') }}</span>
          </header>

          <div class="review-stage__scroll">
            <div v-if="activeMissingInfo.length" class="missing-section">
              <h3><AlertCircle :size="16" />{{ t('generationConfirm.missingInfoTitle') }}</h3>
              <ul>
                <li v-for="(missing, index) in activeMissingInfo" :key="index">{{ missing.reason }}</li>
              </ul>
            </div>

            <div v-if="activeEntry" class="draft-section">
              <h3 class="sr-only" aria-hidden="true">{{ activeEntry.label }}</h3>
              <div v-for="(item, itemIndex) in activeItems" :key="item.path" :class="['draft-item', item.decision?.toLowerCase()]">
                <div class="item-header">
                  <span class="item-number">{{ t('generationConfirm.itemNumber').replace('{index}', String(itemIndex + 1)) }}</span>
                  <span v-if="item.source" class="source-badge">{{ t('generationConfirm.sourceBadge') }}</span>
                  <span v-if="item.pending" class="pending-badge">{{ t('generationConfirm.pendingBadge').replace('{pending}', item.pending) }}</span>
                </div>
                <DraftContentFields :model-value="item.content" />
                <div class="item-actions">
                  <button
                    :class="['action-btn accept', { active: item.decision === 'ACCEPT' }]"
                    :aria-pressed="item.decision === 'ACCEPT'"
                    @click="setDecision(item, 'ACCEPT')"
                  ><Check :size="15" /><span>{{ t('generationConfirm.accept') }}</span></button>
                  <button class="action-btn edit" @click="openEdit(item)"><Pencil :size="15" /><span>{{ t('generationConfirm.editAction') }}</span></button>
                  <button
                    :class="['action-btn reject', { active: item.decision === 'REJECT' }]"
                    :aria-pressed="item.decision === 'REJECT'"
                    @click="setDecision(item, 'REJECT')"
                  ><Trash2 :size="15" /><span>{{ t('generationConfirm.deleteAction') }}</span></button>
                </div>
              </div>
              <div v-if="!activeItems.length" class="section-empty">
                <AlertCircle :size="24" />
                <p>{{ t('generationConfirm.noDraftForSection') }}</p>
              </div>
            </div>
          </div>

          <div class="section-pagination">
            <button
              class="btn-secondary"
              :disabled="activeNavigationIndex <= 0"
              @click="moveSection(-1)"
            ><ChevronLeft :size="15" />{{ t('generationConfirm.previousSection') }}</button>
            <span>{{ Math.max(0, activeNavigationIndex + 1) }} / {{ visibleSectionEntries.length }}</span>
            <button
              class="btn-secondary"
              :disabled="activeNavigationIndex < 0 || activeNavigationIndex >= visibleSectionEntries.length - 1"
              @click="moveSection(1)"
            >{{ t('generationConfirm.nextSection') }}<ChevronRight :size="15" /></button>
          </div>

          <footer class="confirm-actions">
            <div class="title-input">
              <label for="generated-resume-title">{{ t('generationConfirm.resumeNameShortLabel') }}</label>
              <input id="generated-resume-title" v-model="customTitle" :placeholder="t('generationConfirm.resumeNamePlaceholder')" class="input" />
            </div>
            <p v-if="error" class="error-msg">{{ error }}</p>
            <div class="confirm-actions__buttons">
              <button class="btn-secondary" @click="handleReject" :disabled="rejecting">
                {{ t('generationConfirm.rejectDraft') }}
              </button>
              <button class="btn-primary" @click="handleConfirm" :disabled="confirming || pendingCount > 0">
                <span v-if="confirming" class="spinner"></span>
                {{ confirming ? t('generationConfirm.creating') : `${t('generationConfirm.confirmAndCreate')}${pendingCount > 0 ? `（${pendingCount}）` : ''}` }}
              </button>
            </div>
          </footer>
        </section>
      </div>
      <button v-if="mobileNavigationOpen" class="mobile-nav-scrim" :aria-label="t('common.close')" @click="mobileNavigationOpen = false"></button>
    </div>

    <!-- Same-JD Dialog -->
    <Teleport to="body">
      <div v-if="showJdDialog" class="dialog-overlay" @click.self="showJdDialog = false">
        <div class="dialog">
          <h3>{{ t('generationConfirm.existingResumeTitle') }}</h3>
          <p>{{ t('generationConfirm.existingResumeDesc') }}</p>
          <div class="existing-list">
            <div v-for="r in existingResumes" :key="r.id" class="existing-item">
              <span>{{ r.title }}</span>
              <button class="btn-small" @click="doConfirm(r.id)">{{ t('generationConfirm.updateResume') }}</button>
            </div>
          </div>
          <div class="dialog-actions">
            <button class="btn-primary" @click="doConfirm(null)">{{ t('generationConfirm.createNewResume') }}</button>
            <button class="btn-secondary" @click="showJdDialog = false">{{ t('generationConfirm.cancel') }}</button>
          </div>
        </div>
      </div>
    </Teleport>

    <!-- Edit Dialog -->
    <Teleport to="body">
      <div v-if="showEditDialog" class="dialog-overlay edit-overlay" @click.self="closeEdit" @keydown.esc="closeEdit">
        <div class="dialog edit-dialog" role="dialog" aria-modal="true" aria-labelledby="draft-edit-title">
          <header><h3 id="draft-edit-title">{{ t('generationConfirm.editContentTitle') }}</h3><button class="icon-button" :aria-label="t('common.close')" :title="t('common.close')" @click="closeEdit"><X :size="18" /></button></header>
          <div class="edit-dialog__body"><DraftContentFields v-model="editValue" editable /></div>
          <div class="dialog-actions">
            <button class="btn-primary" @click="saveEdit">{{ t('generationConfirm.save') }}</button>
            <button class="btn-secondary" @click="closeEdit">{{ t('generationConfirm.cancel') }}</button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>

<style scoped>
.confirm-page {
  max-width: 760px;
  margin: 0 auto;
  padding: 2rem 1rem;
}
header h1 {
  font-size: 1.5rem;
  font-weight: 700;
}
.subtitle {
  color: #6b7280;
  margin-bottom: 1.5rem;
}
.status-card {
  text-align: center;
  padding: 3rem;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
}
.status-card.error {
  border-color: #fca5a5;
}
.error-text {
  color: #dc2626;
  margin-bottom: 1rem;
}
.spinner-lg {
  width: 32px;
  height: 32px;
  border: 3px solid #e5e7eb;
  border-top-color: #0e7490;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin: 0 auto 1rem;
}
.hint {
  color: #9ca3af;
  font-size: 0.85rem;
}
.warning-item {
  font-size: 0.85rem;
  color: #92400e;
}
.quality-summary {
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #eff6ff;
  padding: 1rem;
  margin-bottom: 1rem;
}
.quality-summary--review_recommended {
  border-color: #fde68a;
  background: #fffbeb;
}
.quality-summary--requires_action {
  border-color: #fecaca;
  background: #fef2f2;
}
.quality-summary__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 0.85rem;
}
.quality-summary__heading h3 {
  margin: 0 0 0.25rem;
  color: #0f3d75;
  font-size: 0.95rem;
}
.quality-summary--review_recommended .quality-summary__heading h3 { color: #92400e; }
.quality-summary--requires_action .quality-summary__heading h3 { color: #991b1b; }
.quality-summary__heading p {
  margin: 0;
  color: #475569;
  font-size: 0.82rem;
  line-height: 1.45;
}
.quality-summary__status {
  flex: 0 0 auto;
  padding: 0.2rem 0.45rem;
  border-radius: 4px;
  color: #1d4ed8;
  background: #dbeafe;
  font-size: 0.75rem;
  font-weight: 600;
}
.quality-summary--review_recommended .quality-summary__status {
  color: #92400e;
  background: #fef3c7;
}
.quality-summary--requires_action .quality-summary__status {
  color: #b91c1c;
  background: #fee2e2;
}
.quality-summary__metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.5rem;
}
.quality-summary__metrics > div {
  min-width: 0;
  padding: 0.55rem;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.65);
}
.quality-summary__metrics strong,
.quality-summary__metrics span {
  display: block;
}
.quality-summary__metrics strong {
  color: #1e293b;
  font-size: 1.05rem;
}
.quality-summary__metrics span {
  margin-top: 0.15rem;
  color: #64748b;
  font-size: 0.72rem;
  line-height: 1.3;
}
@media (max-width: 560px) {
  .quality-summary__heading { flex-direction: column; gap: 0.5rem; }
  .quality-summary__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
.missing-section {
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
  padding: 0.75rem 1rem;
  margin-bottom: 1.5rem;
}
.missing-section h3 {
  font-size: 0.9rem;
  color: #991b1b;
  margin-bottom: 0.5rem;
}
.missing-section li {
  font-size: 0.85rem;
  color: #7f1d1d;
}
.draft-section {
  margin-bottom: 1.5rem;
}
.draft-section h3 {
  font-size: 1rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
  padding-bottom: 0.25rem;
  border-bottom: 1px solid #e5e7eb;
}
.draft-item {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 0.75rem;
  margin-bottom: 0.5rem;
  transition: border-color 0.15s;
}
.draft-item.accept {
  border-color: #a7f3d0;
}
.draft-item.reject {
  border-color: #fca5a5;
  opacity: 0.6;
}
.draft-item.edit {
  border-color: #93c5fd;
}
.item-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.4rem;
  flex-wrap: wrap;
}
.item-number {
  font-size: 0.75rem;
  color: #64748b;
  font-weight: 600;
}
.source-badge {
  font-size: 0.7rem;
  padding: 0.1rem 0.4rem;
  background: #dbeafe;
  color: #1e40af;
  border-radius: 3px;
}
.pending-badge {
  font-size: 0.7rem;
  padding: 0.1rem 0.4rem;
  background: #fef3c7;
  color: #92400e;
  border-radius: 3px;
}
.item-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid #edf1f5;
}
.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 76px;
  height: 34px;
  padding: 0 12px;
  border: 1px solid #d9e0e8;
  border-radius: 6px;
  background: #fff;
  color: #475569;
  font: 600 13px/1 inherit;
  white-space: nowrap;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease, color 0.15s ease, transform 0.15s ease;
}
.action-btn svg {
  flex: 0 0 auto;
}
.action-btn:hover {
  border-color: #94a3b8;
  background: #f8fafc;
  transform: translateY(-1px);
}
.action-btn:focus-visible {
  outline: 2px solid rgba(14, 116, 144, 0.28);
  outline-offset: 2px;
}
.action-btn.accept {
  color: #047857;
}
.action-btn.accept:hover {
  border-color: #6ee7b7;
  background: #ecfdf5;
}
.action-btn.accept.active {
  background: #059669;
  border-color: #059669;
  color: #fff;
}
.action-btn.edit {
  color: #1d4ed8;
}
.action-btn.reject.active {
  background: #dc2626;
  border-color: #dc2626;
  color: #fff;
}
.action-btn.reject {
  color: #b91c1c;
}
.action-btn.reject:hover {
  border-color: #fca5a5;
  background: #fef2f2;
}
.unselected-details {
  margin-bottom: 1.5rem;
  font-size: 0.85rem;
  color: #6b7280;
}
.unselected-details summary {
  cursor: pointer;
  font-weight: 500;
}
.title-input {
  margin-bottom: 1.5rem;
}
.title-input label {
  display: block;
  font-size: 0.85rem;
  color: #6b7280;
  margin-bottom: 0.4rem;
}
.input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.9rem;
}
.error-msg {
  color: #dc2626;
  font-size: 0.85rem;
  margin-bottom: 1rem;
}
.confirm-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
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
  display: flex;
  align-items: center;
  gap: 0.4rem;
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
.btn-small {
  font-size: 0.75rem;
  padding: 0.2rem 0.5rem;
  background: #0e7490;
  color: #fff;
  border: none;
  border-radius: 4px;
  cursor: pointer;
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
.dialog-overlay {
  position: fixed;
  inset: 0;
  background: rgba(0,0,0,0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
}
.dialog {
  background: #fff;
  border-radius: 12px;
  padding: 1.5rem;
  max-width: 480px;
  width: 90%;
  max-height: 80vh;
  overflow-y: auto;
}
.dialog h3 {
  margin-bottom: 0.75rem;
}
.edit-dialog {
  max-width: 640px;
}
.existing-list {
  margin: 1rem 0;
}
.existing-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0.5rem 0;
  border-bottom: 1px solid #f3f4f6;
}
.dialog-actions {
  display: flex;
  gap: 0.75rem;
  margin-top: 1rem;
}
@media (max-width: 560px) {
  .item-actions {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .action-btn {
    width: 100%;
    min-width: 0;
    padding: 0 8px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .action-btn {
    transition: none;
  }
  .action-btn:hover {
    transform: none;
  }
}

/* Human review surface for generated content. */
.confirm-page { display: grid; gap: 24px; width: min(100%, 920px); max-width: 920px; margin: 0 auto; padding: 8px 0 52px; }
.confirm-header { display: grid; gap: 0; padding-bottom: 22px; border-bottom: 1px solid var(--border); }
.confirm-header .eyebrow { justify-self: start; }
.confirm-header h1 { margin: 5px 0 7px; color: var(--text-primary); font-family: var(--font-display); font-size: 34px; font-weight: 700; letter-spacing: 0; }
.confirm-header .subtitle { max-width: 690px; margin: 0; color: var(--text-secondary); font-size: 12px; line-height: 1.65; }
.confirm-route { display: grid; grid-template-columns: auto 34px auto 34px auto; align-items: center; justify-content: end; gap: 7px; margin-top: 20px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.confirm-route span { display: inline-flex; align-items: center; gap: 4px; }
.confirm-route i { height: 1px; background: var(--border); }
.confirm-route .done { color: var(--text-primary); }
.confirm-route .active { color: var(--accent); }
.draft-container { display: grid; gap: 18px; }
.status-card { display: grid; justify-items: center; gap: 9px; padding: 48px 20px; border: 1px solid var(--border); border-radius: 7px; color: var(--accent); background: var(--bg-surface); text-align: center; }
.status-card p { margin: 0; color: var(--text-primary); font-size: 13px; font-weight: 650; }
.status-card .hint { color: var(--text-tertiary); font-size: 10px; font-weight: 500; }
.status-card.error { border-color: color-mix(in srgb, var(--danger) 28%, var(--border)); color: var(--danger); background: var(--danger-light); }
.spinner-lg { width: 30px; height: 30px; margin: 0 0 5px; border-color: var(--border); border-top-color: var(--accent); }
.warning-item { margin: 0; color: var(--warning); font-size: 10px; }
.quality-summary { margin: 0; padding: 20px; border: 1px solid color-mix(in srgb, var(--info) 25%, var(--border)); border-radius: 7px; background: var(--info-light); }
.quality-summary--review_recommended { border-color: color-mix(in srgb, var(--warning) 28%, var(--border)); background: var(--warning-light); }
.quality-summary--requires_action { border-color: color-mix(in srgb, var(--danger) 25%, var(--border)); background: var(--danger-light); }
.quality-summary__heading { margin-bottom: 14px; }
.quality-summary__heading h3 { color: var(--text-primary); font-size: 14px; }
.quality-summary--review_recommended .quality-summary__heading h3, .quality-summary--requires_action .quality-summary__heading h3 { color: var(--text-primary); }
.quality-summary__heading p { color: var(--text-secondary); font-size: 10px; }
.quality-summary__status { border: 1px solid color-mix(in srgb, var(--info) 25%, var(--border)); border-radius: 4px; color: var(--info); background: #fff; font-size: 9px; }
.quality-summary--review_recommended .quality-summary__status { border-color: color-mix(in srgb, var(--warning) 25%, var(--border)); color: var(--warning); background: #fff; }
.quality-summary--requires_action .quality-summary__status { border-color: color-mix(in srgb, var(--danger) 25%, var(--border)); color: var(--danger); background: #fff; }
.quality-summary__metrics { gap: 7px; }
.quality-summary__metrics > div { padding: 9px; border: 1px solid color-mix(in srgb, var(--border) 70%, transparent); border-radius: 5px; background: color-mix(in srgb, #fff 82%, transparent); }
.quality-summary__metrics strong { color: var(--text-primary); font-family: var(--font-utility); font-size: 15px; }
.quality-summary__metrics span { color: var(--text-secondary); font-size: 9px; }
.missing-section { margin: 0; padding: 15px 18px; border: 1px solid color-mix(in srgb, var(--danger) 25%, var(--border)); border-left: 4px solid var(--danger); border-radius: 7px; background: var(--danger-light); }
.missing-section h3 { margin: 0 0 7px; color: var(--text-primary); font-size: 12px; }
.missing-section ul { margin: 0; padding-left: 18px; }
.missing-section li { color: var(--text-secondary); font-size: 10px; }
.draft-section { display: grid; gap: 0; margin: 0; padding: 20px 22px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.draft-section > h3 { margin: 0; padding: 0 0 13px; border-bottom: 1px solid var(--border); color: var(--text-primary); font-size: 14px; }
.draft-item { margin: 0; padding: 16px 4px; border: 0; border-bottom: 1px solid var(--border-soft); border-radius: 0; background: transparent; }
.draft-item:last-child { border-bottom: 0; }
.draft-item.accept { border-color: var(--border-soft); background: color-mix(in srgb, var(--success-light) 38%, transparent); box-shadow: inset 3px 0 0 var(--success); }
.draft-item.reject { border-color: var(--border-soft); background: color-mix(in srgb, var(--danger-light) 45%, transparent); box-shadow: inset 3px 0 0 var(--danger); opacity: .68; }
.draft-item.edit { border-color: var(--border-soft); background: color-mix(in srgb, var(--info-light) 40%, transparent); box-shadow: inset 3px 0 0 var(--info); }
.item-header { margin-bottom: 8px; }
.item-number { color: var(--text-tertiary); font-size: 9px; }
.source-badge, .pending-badge { min-height: 20px; padding: 3px 6px; border-radius: 3px; font-size: 9px; }
.source-badge { color: var(--info); background: var(--info-light); }
.pending-badge { color: var(--warning); background: var(--warning-light); }
.item-actions { margin-top: 13px; padding-top: 10px; border-top-color: var(--border-soft); }
.action-btn { min-width: 74px; height: 32px; padding: 0 10px; border-color: var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); font-size: 10px; }
.action-btn:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-light); transform: none; }
.action-btn.accept { color: var(--success); }
.action-btn.accept.active { border-color: var(--success); color: #fff; background: var(--success); }
.action-btn.edit { color: var(--info); }
.action-btn.reject { color: var(--danger); }
.action-btn.reject.active { border-color: var(--danger); color: #fff; background: var(--danger); }
.unselected-details { margin: 0; padding: 13px 16px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-secondary); background: var(--bg-surface); font-size: 10px; }
.title-input { display: grid; gap: 6px; margin: 0; padding: 18px 20px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); }
.title-input label { margin: 0; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.title-input .input { padding: 10px; border-color: var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font-size: 13px; }
.title-input .input:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.error-msg { margin: 0; color: var(--danger); font-size: 11px; }
.confirm-actions { gap: 8px; padding: 12px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.btn-primary, .btn-secondary, .btn-small { display: inline-flex; align-items: center; justify-content: center; min-height: 36px; padding: 0 13px; border: 1px solid var(--border); border-radius: 6px; font-size: 11px; font-weight: 650; cursor: pointer; }
.btn-primary, .btn-small { border-color: var(--accent); color: #fff; background: var(--accent); }
.btn-secondary { color: var(--text-secondary); background: var(--bg-surface); }
.btn-secondary:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.dialog-overlay { padding: 20px; background: rgba(18, 36, 27, .48); backdrop-filter: blur(5px); }
.dialog { max-width: 500px; padding: 24px; border: 1px solid var(--border); border-radius: 8px; background: var(--bg-surface); box-shadow: var(--shadow-lg); }
.dialog h3 { margin: 0 0 8px; color: var(--text-primary); font-family: var(--font-display); font-size: 22px; }
.dialog p { color: var(--text-secondary); font-size: 11px; }
.existing-item { border-bottom-color: var(--border-soft); color: var(--text-primary); font-size: 12px; }
.dialog-actions { justify-content: flex-end; gap: 8px; }
@media (max-width: 560px) { .confirm-page { padding-top: 0; } .confirm-header h1 { font-size: 29px; } .confirm-route { grid-template-columns: auto 15px auto 15px auto; justify-content: stretch; font-size: 8px; } .quality-summary { padding: 16px; } .draft-section { padding: 17px 14px; } .item-actions { grid-template-columns: repeat(3, minmax(0, 1fr)); } .confirm-actions { display: grid; grid-template-columns: 1fr; } .confirm-actions button { width: 100%; } .dialog-overlay { align-items: end; padding: 0; } .dialog { width: 100%; max-width: none; border-bottom: 0; border-radius: 8px 8px 0 0; } }

.confirm-page { gap: 14px; width: min(100%, 1180px); max-width: 1180px; padding-bottom: 28px; }
.confirm-header { grid-template-columns: minmax(0, 1fr) auto; padding-bottom: 13px; }
.confirm-header .eyebrow,
.confirm-header h1,
.confirm-header .subtitle { grid-column: 1; }
.confirm-header h1 { margin: 3px 0 4px; font-size: 28px; }
.confirm-header .subtitle { font-size: 11px; }
.confirm-route { grid-column: 2; grid-row: 1 / 4; align-self: center; margin: 0 0 0 28px; }
.draft-container { gap: 10px; min-width: 0; }
.quality-summary { display: grid; grid-template-columns: minmax(210px, .7fr) minmax(420px, 1.3fr); gap: 10px 18px; padding: 11px 14px; border-color: var(--border); border-left: 3px solid var(--info); background: var(--bg-surface); }
.quality-summary--review_recommended { border-color: var(--border); border-left-color: var(--warning); background: var(--bg-surface); }
.quality-summary--requires_action { border-color: var(--border); border-left-color: var(--danger); background: var(--bg-surface); }
.quality-summary__heading { align-items: center; margin: 0; }
.quality-summary__heading h3 { margin: 0 0 2px; font-size: 12px; }
.quality-summary__heading p { margin: 0; }
.quality-summary__controls { display: flex; align-items: center; gap: 6px; }
.quality-summary__metrics { gap: 0; border-left: 1px solid var(--border-soft); }
.quality-summary__metrics > div { display: grid; grid-template-columns: auto 1fr; align-items: baseline; gap: 5px; padding: 4px 10px; border: 0; border-right: 1px solid var(--border-soft); border-radius: 0; background: transparent; }
.quality-summary__metrics strong { font-size: 14px; }
.quality-summary__metrics span { margin: 0; white-space: nowrap; }
.quality-summary__details { grid-column: 1 / -1; padding: 10px 0 2px; border-top: 1px solid var(--border-soft); }
.quality-summary__details > p { margin: 0 0 5px; color: var(--text-secondary); font-size: 10px; }
.missing-summary { margin-top: 8px; color: var(--text-secondary); font-size: 10px; }
.missing-summary ul { margin: 5px 0 0; padding-left: 18px; }
.review-notices { padding: 11px 14px; border: 1px solid color-mix(in srgb, var(--warning) 30%, var(--border)); border-left: 3px solid var(--warning); border-radius: 6px; background: var(--bg-surface); }
.review-notices .missing-summary:first-child { margin-top: 0; }
.icon-button { display: inline-grid; width: 30px; height: 30px; flex: 0 0 30px; padding: 0; place-items: center; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); cursor: pointer; }
.icon-button:hover,
.icon-button.active { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.icon-button.active svg { transform: rotate(180deg); }
.review-workspace { display: grid; grid-template-columns: 220px minmax(0, 1fr); height: clamp(540px, calc(100dvh - 248px), 740px); min-height: 540px; overflow: hidden; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.review-rail { display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; gap: 10px; min-width: 0; padding: 16px 12px; overflow: hidden; border-right: 1px solid var(--border); background: color-mix(in srgb, var(--bg-page) 70%, var(--bg-surface)); }
.review-rail__heading { display: flex; align-items: center; justify-content: space-between; padding: 0 4px 8px; }
.review-rail__heading > div { display: flex; align-items: baseline; justify-content: space-between; width: 100%; gap: 10px; }
.review-rail__heading span { color: var(--text-secondary); font-size: 10px; font-weight: 700; }
.review-rail__heading strong { color: var(--accent); font-family: var(--font-utility); font-size: 11px; }
.attention-filter { display: grid; grid-template-columns: 18px 1fr auto; align-items: center; width: 100%; min-height: 34px; padding: 0 9px; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); font-size: 10px; font-weight: 650; text-align: left; cursor: pointer; }
.attention-filter b { display: grid; min-width: 19px; height: 19px; place-items: center; border-radius: 10px; color: var(--text-tertiary); background: var(--bg-page); font-size: 9px; }
.attention-filter.active { border-color: color-mix(in srgb, var(--warning) 38%, var(--border)); color: var(--warning); background: var(--warning-light); }
.section-navigation { display: grid; align-content: start; gap: 2px; min-height: 0; overflow-y: auto; padding-right: 3px; scrollbar-width: thin; }
.section-navigation__item { display: grid; grid-template-columns: 18px minmax(0, 1fr) auto; align-items: center; width: 100%; min-height: 36px; padding: 0 9px; border: 0; border-radius: 5px; color: var(--text-secondary); background: transparent; font-size: 10px; font-weight: 650; text-align: left; cursor: pointer; }
.section-navigation__item svg { color: var(--success); }
.section-navigation__item svg.attention { color: var(--warning); }
.section-navigation__item svg.rejected { color: var(--text-tertiary); }
.section-navigation__item b { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; }
.section-navigation__item:hover { color: var(--text-primary); background: var(--bg-surface); }
.section-navigation__item.active { color: var(--accent); background: var(--accent-light); box-shadow: inset 3px 0 0 var(--accent); }
.section-navigation__item.active svg,
.section-navigation__item.active b { color: var(--accent); }
.rail-empty { margin: 8px; color: var(--text-tertiary); font-size: 10px; line-height: 1.5; }
.review-rail .unselected-details { padding: 9px; background: transparent; }
.review-rail .unselected-details ul { max-height: 120px; overflow-y: auto; padding-left: 16px; }
.review-stage { display: grid; grid-template-rows: auto minmax(0, 1fr) auto auto; min-width: 0; min-height: 0; }
.review-stage__heading { display: flex; align-items: center; justify-content: space-between; min-height: 62px; padding: 11px 20px; border-bottom: 1px solid var(--border); }
.review-stage__heading p { margin: 0 0 2px; color: var(--text-tertiary); font-size: 9px; font-weight: 700; }
.review-stage__heading h2 { margin: 0; color: var(--text-primary); font-size: 17px; }
.review-stage__heading > span { color: var(--text-tertiary); font-size: 10px; }
.review-stage__scroll { min-height: 0; overflow-y: auto; padding: 12px 20px 24px; scrollbar-width: thin; }
.review-stage__scroll .missing-section { margin-bottom: 12px; padding: 11px 13px; border-left-width: 3px; }
.review-stage__scroll .missing-section h3 { display: flex; align-items: center; gap: 6px; }
.review-stage__scroll .draft-section { padding: 0; border: 0; border-radius: 0; box-shadow: none; }
.review-stage__scroll .draft-item { padding: 15px 12px; }
.review-stage__scroll .draft-item:first-of-type { padding-top: 8px; }
.section-empty { display: grid; justify-items: center; gap: 7px; padding: 48px 20px; color: var(--warning); text-align: center; }
.section-empty p { max-width: 360px; margin: 0; color: var(--text-secondary); font-size: 11px; line-height: 1.6; }
.section-pagination { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 12px; padding: 8px 20px; border-top: 1px solid var(--border-soft); }
.section-pagination > span { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; text-align: center; }
.section-pagination .btn-secondary { min-height: 32px; gap: 4px; padding: 0 10px; }
.section-pagination button:disabled { opacity: .45; cursor: not-allowed; }
.confirm-actions { display: grid; grid-template-columns: minmax(210px, 1fr) auto; align-items: end; gap: 10px 16px; padding: 10px 14px; border: 0; border-top: 1px solid var(--border); border-radius: 0; box-shadow: 0 -5px 14px rgba(28, 48, 37, .04); }
.confirm-actions .title-input { grid-template-columns: auto minmax(160px, 1fr); align-items: center; gap: 9px; padding: 0; border: 0; background: transparent; }
.confirm-actions .title-input label { white-space: nowrap; font-size: 10px; }
.confirm-actions .title-input .input { min-width: 0; height: 36px; padding: 0 10px; }
.confirm-actions__buttons { display: flex; gap: 8px; }
.confirm-actions .error-msg { grid-column: 1 / -1; grid-row: 1; }
.mobile-outline-trigger,
.mobile-only,
.mobile-nav-scrim { display: none; }
.sr-only { position: absolute; width: 1px; height: 1px; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
.edit-overlay { align-items: stretch; justify-content: flex-end; padding: 0; }
.edit-overlay .edit-dialog { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; width: min(440px, 100%); max-width: 440px; max-height: none; height: 100%; padding: 20px; overflow: hidden; border-block: 0; border-right: 0; border-radius: 8px 0 0 8px; }
.edit-dialog > header { display: flex; align-items: center; justify-content: space-between; padding-bottom: 12px; border-bottom: 1px solid var(--border); }
.edit-dialog > header h3 { margin: 0; font-size: 20px; }
.edit-dialog__body { min-height: 0; overflow-y: auto; padding: 16px 4px; }
.edit-dialog .dialog-actions { margin: 0; padding-top: 12px; border-top: 1px solid var(--border); }

@media (max-width: 900px) {
  .confirm-route { display: none; }
  .quality-summary { grid-template-columns: 1fr; }
  .quality-summary__metrics { border-left: 0; }
  .quality-summary__details { grid-column: auto; }
  .review-workspace { grid-template-columns: 190px minmax(0, 1fr); }
  .confirm-actions { grid-template-columns: 1fr; }
  .confirm-actions__buttons { justify-content: flex-end; }
}

@media (max-width: 767px) {
  .confirm-page { gap: 10px; padding-top: 0; }
  .confirm-header { display: block; padding-bottom: 10px; }
  .confirm-header h1 { font-size: 25px; }
  .confirm-header .subtitle { display: none; }
  .quality-summary { padding: 10px 12px; }
  .quality-summary__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .quality-summary__metrics > div { border-bottom: 1px solid var(--border-soft); }
  .mobile-outline-trigger { display: grid; grid-template-columns: 20px minmax(0, 1fr) auto; align-items: center; min-height: 42px; padding: 0 12px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-surface); font-size: 11px; font-weight: 700; text-align: left; }
  .mobile-outline-trigger b { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; }
  .review-workspace { display: block; height: auto; min-height: 0; overflow: visible; }
  .review-rail { position: fixed; inset: 0 auto 0 0; z-index: 1002; width: min(310px, calc(100vw - 48px)); padding-top: 18px; transform: translateX(-105%); transition: transform .18s ease; box-shadow: var(--shadow-lg); }
  .review-rail.open { transform: translateX(0); }
  .review-rail__heading > div { width: auto; flex: 1; }
  .mobile-only { display: inline-grid; }
  .mobile-nav-scrim { position: fixed; inset: 0; z-index: 1001; display: block; width: 100%; height: 100%; padding: 0; border: 0; background: rgba(18, 36, 27, .42); }
  .review-stage { min-height: calc(100dvh - 220px); }
  .review-stage__heading { min-height: 56px; padding: 10px 14px; }
  .review-stage__scroll { overflow: visible; padding: 12px 14px 22px; }
  .review-stage__scroll .draft-item { padding-inline: 6px; }
  .section-pagination { padding: 8px 12px; }
  .section-pagination .btn-secondary { width: auto; }
  .confirm-actions { position: sticky; z-index: 8; bottom: 0; grid-template-columns: 1fr; padding: 10px 12px; background: var(--bg-surface); }
  .confirm-actions__buttons { display: grid; grid-template-columns: minmax(0, .75fr) minmax(0, 1.25fr); }
  .confirm-actions button { width: 100%; }
  .edit-overlay .edit-dialog { width: 100%; max-width: none; border-left: 0; border-radius: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .review-rail { transition: none; }
}
</style>
