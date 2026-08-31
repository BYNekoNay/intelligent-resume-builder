/**
 * AI 草稿人工确认页（GenerationConfirmView）共享状态与逻辑。
 *
 * 从 GenerationConfirmView.vue 纯搬迁（不改逻辑），模块级 ref 单例共享，
 * 供视图与 QualitySummaryCard / DraftSectionReview / DraftEditDialog /
 * ExistingJdDialog 直接消费，避免多层 props/emits 穿透。
 */
import { computed, ref, toRaw } from 'vue'
import type { ResumeSummary } from '@/api/resume'
import { useLocale } from '@/i18n'

// data identifier for material-library source
const MATERIAL_SOURCE = '\u8D44\u6599\u5E93'
const REVIEWABLE_SECTIONS = [
  'basics', 'work', 'education', 'skills', 'projects', 'certificates',
  'objective', 'volunteering', 'courses', 'publications', 'customSections',
] as const
export type ReviewableSection = (typeof REVIEWABLE_SECTIONS)[number]

export interface DraftItem {
  path: string
  section: ReviewableSection
  content: any
  provenance: Record<string, unknown>
  source: string | null
  pending: string | null
  decision: 'ACCEPT' | 'EDIT' | 'REJECT' | null
  editedValue: any
}

export interface QualitySummary {
  totalDraftItems: number
  sourcedItems: number
  pendingItems: number
  unsupportedItems: number
  draftGapCount: number
  missingRequirementCount: number
  readiness: 'READY' | 'REVIEW_RECOMMENDED' | 'REQUIRES_ACTION'
}

export interface MissingInfo {
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

// Edit dialog
const showEditDialog = ref(false)
const editingItem = ref<DraftItem | null>(null)
const editValue = ref<unknown>(null)
const activeSection = ref<ReviewableSection | null>(null)
const attentionOnly = ref(false)
const mobileNavigationOpen = ref(false)

// Resume title input
const customTitle = ref('')

const { t } = useLocale()

function parseDraft(resultJson: any) {
  if (!resultJson) return
  const draft = resultJson.draftResumeJson
  selectedInfo.value = resultJson.selected ?? []
  unselectedInfo.value = resultJson.unselected ?? []
  missingInfo.value = resultJson.missing ?? []
  warnings.value = resultJson.warnings ?? []
  qualitySummary.value = normalizeQualitySummary(resultJson.qualitySummary)

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
  work: { company: '', position: '', startDate: '', endDate: '', period: '', description: '', highlights: [] },
  education: { school: '', degree: '', major: '', startDate: '', endDate: '', period: '' },
  skills: { name: '', level: '' },
  projects: { name: '', role: '', startDate: '', endDate: '', period: '', description: '', highlights: [] },
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

function openJdDialog(resumes: ResumeSummary[]) {
  existingResumes.value = resumes
  showJdDialog.value = true
}

function closeJdDialog() {
  showJdDialog.value = false
}

/**
 * 重置全部草稿评审状态为初始值。
 *
 * 视图每次挂载（onMounted）时调用，等价于原视图 setup 中 ref 初始值语义，
 * 避免模块级单例状态在跨路由挂载间残留（如编辑弹窗、JD 弹窗、自定义标题）。
 */
function resetDraftReview() {
  draftItems.value = []
  selectedInfo.value = []
  unselectedInfo.value = []
  missingInfo.value = []
  warnings.value = []
  qualitySummary.value = null
  showJdDialog.value = false
  existingResumes.value = []
  showEditDialog.value = false
  editingItem.value = null
  editValue.value = null
  activeSection.value = null
  attentionOnly.value = false
  mobileNavigationOpen.value = false
  customTitle.value = ''
}

export function useDraftReview() {
  return {
    // types
    REVIEWABLE_SECTIONS,
    // state
    draftItems,
    selectedInfo,
    unselectedInfo,
    missingInfo,
    warnings,
    qualitySummary,
    showJdDialog,
    existingResumes,
    showEditDialog,
    editingItem,
    editValue,
    activeSection,
    attentionOnly,
    mobileNavigationOpen,
    customTitle,
    // computeds
    groupedItems,
    pendingCount,
    processedCount,
    sectionEntries,
    visibleSectionEntries,
    attentionSectionCount,
    activeEntry,
    activeItems,
    activeMissingInfo,
    activeSectionIndex,
    activeNavigationIndex,
    // actions
    parseDraft,
    normalizeQualitySummary,
    selectSection,
    moveSection,
    toggleAttentionOnly,
    openEdit,
    saveEdit,
    closeEdit,
    setDecision,
    openJdDialog,
    closeJdDialog,
    resetDraftReview,
  }
}
