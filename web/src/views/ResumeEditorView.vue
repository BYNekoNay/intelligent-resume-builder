<!--
  简历编辑器主页面（/resumes/:id/edit）。

  【这个页面是干嘛的】用户在这里逐节填写/修改简历：左侧是章节导航（基本信息、工作经历、技能……），
  中间是表单，右侧可打开「设计面板」调字体字号、换模板、拖动章节顺序。用户视角的三个入口：
  ①直接编辑现有简历；②从「素材库」里挑一条素材（如一段工作经历）一键插进对应章节；
  ③从 ATS 检测报告（ATS = 简历解析系统，会按招聘方关键词给简历打分）点某个问题「去修改」跳到这里定向修复。

  【数据模型】唯一数据源是 JSON 原文 content（简历整体就是一段 JSON 文本），各分节表单只是对它的受控编辑：
  updateResume 深拷贝 resume 后按字段改写，再整体序列化回 content，因此 JSON 非法时所有表单编辑都会被拦住并要求先修源码。
  「保存」= 新建一个版本（createManualVersion，后端每次保存都存快照，方便对比/回滚历史版本），成功后跳 resume-detail。

  【依赖】@/api/resume（版本读取/创建/恢复）、@/api/ats（ATS 交接校验）、@/api/ai（单段优化 + 任务轮询）、
  @/api/careerMaterial（素材库插入）、@/api/interviewAsset（章节关联的面试素材）。

  【状态分支与易错点】
  - loading → 正常编辑 / loadFailed → 显示重试页。
  - dirty（内容变化或填写了版本说明）会触发离开路由与 beforeunload 的二次确认，防止误关页面丢稿。
  - 页面有多个异步源（加载、素材、保存、AI），统一用 editorContextEpoch / editorLoadSequence / relatedAssetsEpoch
    做「代次校验」（每次页面上下文切换就自增一个编号，晚到的旧响应编号对不上就丢弃，防止旧数据覆盖新状态）。
  - ATS 交接进入历史版本时页面为只读（historicalSourceReadOnly），必须先创建「可编辑后继版本」才能继续编辑。
  - 草稿恢复：编辑中内容会存 localStorage，下次进来若检测到未保存草稿会弹 DraftRestoreBanner 询问是否恢复。
-->
<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRoute, useRouter } from 'vue-router'
import { ArrowLeft, BookOpen, GripVertical, PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen, Sparkles, WandSparkles, X } from 'lucide-vue-next'
import { createManualVersion, getResume, getResumeVersion, listVersions, restoreResumeVersion, type ResumeVersion } from '@/api/resume'
import { getAtsCheck, type AtsCheckResponse } from '@/api/ats'
import { getMaterial, listMaterials, type CareerMaterial, type CareerMaterialSummary, type MaterialType } from '@/api/careerMaterial'
import { AiTaskTimeoutError, inlineOptimize, waitForAiTaskResult, type InlineOptimizeResponse } from '@/api/ai'
import { useLocale } from '@/i18n'
import { resolveApiError } from '@/utils/errorMessage'
import { extractErrorCode } from '@/utils/errorCodes'
import { useAuthStore } from '@/stores/auth'
import sampleResume from '@/data/sampleResume'
import ResumeEditorNavigation, { type ResumeEditorSection } from '@/components/resume/ResumeEditorNavigation.vue'
import ResumePaper from '@/components/resume/ResumePaper.vue'
import AtsHandoffReturnNav from '@/components/resume/AtsHandoffReturnNav.vue'
import AtsVersionLockBanner from '@/components/resume/AtsVersionLockBanner.vue'
import TemplatePickerDialog from '@/components/resume/TemplatePickerDialog.vue'
import SampleConfirmDialog from '@/components/resume/SampleConfirmDialog.vue'
import DraftRestoreBanner from '@/components/resume/DraftRestoreBanner.vue'
import { useResumeEditorDraft } from '@/composables/useResumeEditorDraft'
import { SECTION_KEYS, DEFAULT_SECTION_ORDER, mapAtsSection, resolveSectionOrder, type SectionKey, type ContentSectionKey } from '@/resume/sectionRegistry'
import { listInterviewAssets, type InterviewAsset } from '@/api/interviewAsset'
import { newIdempotencyKey } from '@/utils/idempotency'

const { t } = useLocale()
function message(key: string, values: Record<string, string | number> = {}) {
  return Object.entries(values).reduce((text, [name, value]) => text.replace(`{${name}}`, String(value)), t(`resumeEditor.${key}`))
}
const props = defineProps<{ id: string }>()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const content = ref('')
const summary = ref('')
const loading = ref(true)
const loadFailed = ref(false)
const saving = ref(false)
const error = ref('')
const initialContent = ref('')
const showSource = ref(false)
const showPreview = ref(false)
const showDesign = ref(false)
const showTemplatePicker = ref(false)
const sidebarCollapsed = ref(false)
const editorPanelCollapsed = ref(false)
const previewPaperRef = ref<{ paperElement: HTMLElement | null } | null>(null)
const previewBackButtonRef = ref<HTMLButtonElement | null>(null)
const designBackButtonRef = ref<HTMLButtonElement | null>(null)
const previewPageCount = ref(1)
let previewResizeObserver: ResizeObserver | undefined
let editorScrollPosition = 0
let previewReturnFocus: HTMLElement | null = null
let designScrollPosition = 0
let designReturnFocus: HTMLElement | null = null
const aiConsentHref = computed(() => `/ai-consent?redirect=${encodeURIComponent(`/resumes/${props.id}/edit`)}`)

const currentVersionId = ref<number | null>(null)
const atsSourceVersionId = ref<number | null>(null)
const historicalSourceReadOnly = ref(false)
const creatingEditableVersion = ref(false)
const editableCreationError = ref('')
const pendingEditableSuccessorId = ref<number | null>(null)
const atsAnnouncement = ref('')
type AtsHandoffContext = {
  resultId: number
  section: SectionKey
  item: string
}
const atsContext = ref<AtsHandoffContext | null>(null)
const sectionKeys = SECTION_KEYS
type SortableSection = ContentSectionKey
const activeSection = ref<SectionKey>('basics')
const relatedSectionAssets = ref<InterviewAsset[]>([])
const relatedAssetsLoading = ref(false)
// #65：章节关联资产请求世代。快速切换章节时，旧章节的响应可能晚到，
// 只有最新一次请求可以写入列表/加载态，避免关联资产与当前章节不匹配。
let relatedAssetsEpoch = 0
watch(activeSection, () => { void loadRelatedSectionAssets() }, { immediate: true })
async function loadRelatedSectionAssets() {
  const epoch = ++relatedAssetsEpoch
  const sectionKey = activeSection.value
  if (!sectionKey) return
  relatedAssetsLoading.value = true
  try {
    const response = (await listInterviewAssets({ sectionKey }))
    if (epoch !== relatedAssetsEpoch) return
    relatedSectionAssets.value = response
  } catch {
    if (epoch !== relatedAssetsEpoch) return
    relatedSectionAssets.value = []
  } finally {
    if (epoch === relatedAssetsEpoch) relatedAssetsLoading.value = false
  }
}
type DragLocation = { section: SortableSection; index: number; after: boolean }
const collapsedSections = ref<Set<SectionKey>>(new Set(sectionKeys.filter((section) => section !== 'basics')))
const draggedItem = ref<{ section: SortableSection; index: number } | null>(null)
const dragTarget = ref<DragLocation | null>(null)
const draggedContentSection = ref<SortableSection | null>(null)
const contentSectionDropTarget = ref<{ section: SortableSection; after: boolean } | null>(null)
type AiAssistantState = {
  idempotencyKey: string
  scope: 'field' | 'section'
  label: string
  section: string
  content: string
  loading: boolean
  result: InlineOptimizeResponse | null
  error: string
  needsConsent: boolean
  apply?: (value: string) => void
}
const aiAssistant = ref<AiAssistantState | null>(null)
const undoRemoval = ref<{ content: string; label: string } | null>(null)
let undoTimer: ReturnType<typeof setTimeout> | undefined
const showSampleConfirm = ref(false)
const materialLibrary = ref<CareerMaterialSummary[]>([])
const materialLibraryLoading = ref(false)
const materialInsertLoading = ref(false)
const selectedMaterialId = ref<number | null>(null)
let editorLoadSequence = 0
/**
 * 路由级「代次」编号（KTD-4/KTD-5）。loadEditor() 每次因简历 ID 变化或 ATS 交接参数
 * 变化而重载前都会把它 +1。之后每个异步操作（保存、插素材、AI 优化等）在发起时记下
 * 当时的编号，等响应回来时先核对编号是否还是当前值——不是就说明用户已经切到了
 * 另一个页面上下文，这次响应必须全部丢弃。作用：防止一个慢的旧请求把结果写进
 * 已经换了一份简历的新页面（内容、草稿、报错、加载态都不许污染）。
 */
let editorContextEpoch = 0

const userId = computed(() => auth.currentUser?.id)
const resumeId = computed(() => props.id)

function toggleSidebar() { sidebarCollapsed.value = !sidebarCollapsed.value }
function toggleEditorPanel() { editorPanelCollapsed.value = !editorPanelCollapsed.value }
function selectSection(section: string) {
  const target = section as SectionKey
  if (!sectionKeys.includes(target)) return
  activeSection.value = target
  expandSection(target)
}
function previewPageEstimate() {
  return t('resumeEditor.estimatedPages').replace('{count}', String(previewPageCount.value))
}
function openPreview() {
  editorScrollPosition = window.scrollY
  previewReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  showPreview.value = true
  void nextTick(() => previewBackButtonRef.value?.focus())
}
function openDesign() {
  if (historicalSourceReadOnly.value) return
  designScrollPosition = window.scrollY
  designReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
  showDesign.value = true
  void nextTick(() => {
    window.scrollTo(0, 0)
    designBackButtonRef.value?.focus()
  })
}
async function closeDesign() {
  showDesign.value = false
  await nextTick()
  window.scrollTo(0, designScrollPosition)
  designReturnFocus?.focus()
}
function openTemplatePicker() { showTemplatePicker.value = true }
function closeTemplatePicker() { showTemplatePicker.value = false }
async function closePreview() {
  showPreview.value = false
  await nextTick()
  previewReturnFocus?.focus({ preventScroll: true })
  window.scrollTo(0, editorScrollPosition)
}
function applyDraft() {
  const restoredSection = restoreDraft()
  if (restoredSection) selectSection(restoredSection)
}
function nextSection() {
  const index = sectionKeys.indexOf(activeSection.value)
  selectSection(sectionKeys[Math.min(index + 1, sectionKeys.length - 1)])
}
watch(sidebarCollapsed, (collapsed) => {
  try { localStorage.setItem('resume-editor-sidebar-collapsed', String(collapsed)) } catch { /* storage is optional */ }
})
watch(editorPanelCollapsed, (collapsed) => {
  try { localStorage.setItem('resume-editor-property-panel-collapsed', String(collapsed)) } catch { /* storage is optional */ }
})

function loadSample() {
  if (dirty.value && !showSampleConfirm.value) {
    showSampleConfirm.value = true
    return
  }
  content.value = JSON.stringify(sampleResume, null, 2)
  showSampleConfirm.value = false
}

const templateOptions = [
  { code: 'classic', name: () => t('resumeEditor.templateClassic'), description: () => t('resumeEditor.templateClassicDesc') },
  { code: 'modern', name: () => t('resumeEditor.templateModern'), description: () => t('resumeEditor.templateModernDesc') },
  { code: 'minimal', name: () => t('resumeEditor.templateMinimal'), description: () => t('resumeEditor.templateMinimalDesc') },
  { code: 'ats', name: () => t('resumeEditor.templateAts'), description: () => t('resumeEditor.templateAtsDesc') },
  { code: 'executive', name: () => t('resumeEditor.templateExecutive'), description: () => t('resumeEditor.templateExecutiveDesc') },
  { code: 'compact', name: () => t('resumeEditor.templateCompact'), description: () => t('resumeEditor.templateCompactDesc') },
  { code: 'academic', name: () => t('resumeEditor.templateAcademic'), description: () => t('resumeEditor.templateAcademicDesc') },
] as const

const resume = computed<Record<string, any>>(() => { try { return JSON.parse(content.value || '{}') } catch { return {} } })
const basics = computed<Record<string, any>>(() => resume.value.basics ?? {})
const skills = computed<any[]>(() => Array.isArray(resume.value.skills) ? resume.value.skills : [])
const work = computed<any[]>(() => Array.isArray(resume.value.work) ? resume.value.work : [])
const education = computed<any[]>(() => Array.isArray(resume.value.education) ? resume.value.education : [])
const projects = computed<any[]>(() => Array.isArray(resume.value.projects) ? resume.value.projects : [])
const certificates = computed<any[]>(() => Array.isArray(resume.value.certificates) ? resume.value.certificates : [])
const awards = computed<any[]>(() => Array.isArray(resume.value.awards) ? resume.value.awards : [])
const languages = computed<any[]>(() => Array.isArray(resume.value.languages) ? resume.value.languages : [])
const links = computed<any[]>(() => Array.isArray(resume.value.links) ? resume.value.links : [])
const volunteering = computed<any[]>(() => Array.isArray(resume.value.volunteering) ? resume.value.volunteering : [])
const courses = computed<any[]>(() => Array.isArray(resume.value.courses) ? resume.value.courses : [])
const publications = computed<any[]>(() => Array.isArray(resume.value.publications) ? resume.value.publications : [])
const customSections = computed<any[]>(() => Array.isArray(resume.value.customSections) ? resume.value.customSections : [])
const objective = computed<Record<string, any>>(() => resume.value.objective ?? {})
const templateCode = computed(() => {
  const code = resume.value.template?.code
  return templateOptions.some((opt) => opt.code === code) ? code : 'classic'
})
const templateName = computed(() => templateOptions.find((opt) => opt.code === templateCode.value)?.name() ?? 'Classic')
const fontOptions = [
  { code: 'sans', name: () => t('resumeEditor.fontSans'), family: '"Microsoft YaHei", Arial, sans-serif' },
  { code: 'songti', name: () => t('resumeEditor.fontSongti'), family: '"Songti SC", SimSun, serif' },
  { code: 'serif', name: () => t('resumeEditor.fontSerif'), family: 'Georgia, "Times New Roman", serif' },
  { code: 'mono', name: () => t('resumeEditor.fontMono'), family: '"Cascadia Mono", "Courier New", monospace' },
] as const
const defaultLayout = {
  fontFamily: 'sans',
  bodyFontSize: 13,
  headingFontSize: 13,
  lineHeight: 1.65,
  sectionSpacing: 20,
  entrySpacing: 12,
  pagePadding: 58,
}
const layout = computed(() => ({ ...defaultLayout, ...(resume.value.layout ?? {}) }))
const contentSectionOrder = computed<SortableSection[]>(() => resolveSectionOrder(resume.value.layout?.sectionOrder))
const layoutStyle = computed(() => ({
  '--resume-font-family': fontOptions.find((option) => option.code === layout.value.fontFamily)?.family ?? fontOptions[0].family,
  '--resume-body-size': `${layout.value.bodyFontSize}px`,
  '--resume-heading-size': `${layout.value.headingFontSize}px`,
  '--resume-name-size': `${layout.value.headingFontSize * 30 / 13}px`,
  '--resume-role-size': `${layout.value.headingFontSize * 14 / 13}px`,
  '--resume-line-height': String(layout.value.lineHeight),
  '--resume-section-gap': `${layout.value.sectionSpacing}px`,
  '--resume-entry-gap': `${layout.value.entrySpacing}px`,
  '--paper-pad': `${layout.value.pagePadding}px`,
}))
const sourceValid = computed(() => { try { JSON.parse(content.value); return true } catch { return false } })
const dirty = computed(() => !loading.value && (content.value !== initialContent.value || summary.value.trim().length > 0))
const { restoreCandidate, read: readDraft, restore: restoreDraft, clear: clearDraft, clearFor: clearDraftFor, reset: resetDraft } = useResumeEditorDraft(
  userId, resumeId, currentVersionId, content, summary, activeSection, dirty,
)
const previewItemCount = computed(() => links.value.length + work.value.length + volunteering.value.length + projects.value.length + education.value.length + courses.value.length + skills.value.length + certificates.value.length + publications.value.length + awards.value.length + languages.value.length + customSections.value.length)
const previewTextLength = computed(() => {
  const values = [basics.value.summary, objective.value.summary, ...links.value, ...work.value, ...volunteering.value, ...projects.value, ...education.value, ...courses.value, ...skills.value, ...certificates.value, ...publications.value, ...awards.value, ...languages.value, ...customSections.value]
  return values.reduce((total, val) => total + JSON.stringify(val ?? '').length, 0)
})
const previewDensity = computed(() => previewItemCount.value + previewTextLength.value / 180)
const previewMayOverflow = computed(() => previewDensity.value > 12)
const hasBodyContent = computed(() => Boolean(basics.value.summary || objective.value.summary) || previewItemCount.value > 0)
const completionChecks = computed(() => [
  Boolean(basics.value.name), Boolean(basics.value.title || basics.value.position),
  Boolean(basics.value.email || basics.value.phone), Boolean(basics.value.summary),
  work.value.length > 0, projects.value.length > 0, skills.value.length > 0, education.value.length > 0,
])
const completionScore = computed(() => Math.round(completionChecks.value.filter(Boolean).length / completionChecks.value.length * 100))
const nextSuggestion = computed(() => {
  if (!basics.value.name) return t('resumeEditor.nameRequired')
  if (!basics.value.title && !basics.value.position) return t('resumeEditor.suggestionRole')
  if (!basics.value.summary) return t('resumeEditor.suggestionSummary')
  if (!work.value.length && !projects.value.length) return t('resumeEditor.suggestionExperience')
  if (!skills.value.length) return t('resumeEditor.suggestionSkills')
  return t('resumeEditor.suggestionComplete')
})
function localizedItemCount(count: number) {
  return t('resumeEditor.itemCount').replace('{count}', String(count)).replace('{plural}', count === 1 ? '' : 's')
}

const navigationSections = computed<ResumeEditorSection[]>(() => [
  { key: 'basics', label: t('resumeEditor.basicsLabel'), complete: Boolean(basics.value.name && (basics.value.title || basics.value.position)), meta: basics.value.name ? t('resumeEditor.detailsAdded') : t('resumeEditor.addDetails') },
  { key: 'objective', label: t('resumeEditor.objectiveLabel'), meta: objective.value.summary ? t('resumeEditor.detailsAdded') : t('resumeEditor.optional'), complete: Boolean(objective.value.summary) },
  { key: 'links', label: t('resumeEditor.linksLabel'), meta: links.value.length ? localizedItemCount(links.value.length) : t('resumeEditor.optional'), complete: links.value.length > 0 },
  { key: 'work', label: t('resumeEditor.workLabel'), meta: work.value.length ? localizedItemCount(work.value.length) : t('resumeEditor.addExperience'), complete: work.value.length > 0 },
  { key: 'volunteering', label: t('resumeEditor.volunteeringLabel'), meta: volunteering.value.length ? localizedItemCount(volunteering.value.length) : t('resumeEditor.optional'), complete: volunteering.value.length > 0 },
  { key: 'skills', label: t('resumeEditor.skillsLabel'), meta: skills.value.length ? localizedItemCount(skills.value.length) : t('resumeEditor.addSkills'), complete: skills.value.length > 0 },
  { key: 'projects', label: t('resumeEditor.projectsLabel'), meta: projects.value.length ? localizedItemCount(projects.value.length) : t('resumeEditor.addProjects'), complete: projects.value.length > 0 },
  { key: 'education', label: t('resumeEditor.educationLabel'), meta: education.value.length ? localizedItemCount(education.value.length) : t('resumeEditor.optional'), complete: education.value.length > 0 },
  { key: 'courses', label: t('resumeEditor.coursesLabel'), meta: courses.value.length ? localizedItemCount(courses.value.length) : t('resumeEditor.optional'), complete: courses.value.length > 0 },
  { key: 'certificates', label: t('resumeEditor.certificatesLabel'), meta: certificates.value.length ? localizedItemCount(certificates.value.length) : t('resumeEditor.optional'), complete: certificates.value.length > 0 },
  { key: 'publications', label: t('resumeEditor.publicationsLabel'), meta: publications.value.length ? localizedItemCount(publications.value.length) : t('resumeEditor.optional'), complete: publications.value.length > 0 },
  { key: 'awards', label: t('resumeEditor.awardsLabel'), meta: awards.value.length ? localizedItemCount(awards.value.length) : t('resumeEditor.optional'), complete: awards.value.length > 0 },
  { key: 'languages', label: t('resumeEditor.languagesLabel'), meta: languages.value.length ? localizedItemCount(languages.value.length) : t('resumeEditor.optional'), complete: languages.value.length > 0 },
  { key: 'customSections', label: t('resumeEditor.customSectionsLabel'), meta: customSections.value.length ? localizedItemCount(customSections.value.length) : t('resumeEditor.optional'), complete: customSections.value.length > 0 },
])

function positiveQueryInteger(value: unknown) {
  const raw = Array.isArray(value) ? value[0] : value
  const parsed = typeof raw === 'string' ? Number(raw) : NaN
  return Number.isInteger(parsed) && parsed > 0 ? parsed : null
}

function sectionLabel(section: SectionKey) {
  return navigationSections.value.find(item => item.key === section)?.label ?? section
}

function handoffItem(result: AtsCheckResponse, rawItem: unknown, section: SectionKey): AtsHandoffContext | null {
  // 校验 URL 上的 atsItem（格式如 "evidence:0" / "action:2"，即 ATS 报告里的第 N 条证据/建议）
  // 确实属于目标章节，防止用伪造参数让页面定位到不存在的条目。
  const value = Array.isArray(rawItem) ? rawItem[0] : rawItem
  if (typeof value !== 'string') return null
  const match = /^(evidence|action):(0|[1-9]\d*)$/.exec(value)
  if (!match) return null
  const index = Number(match[2])
  if (match[1] === 'evidence') {
    const item = result.aiInsights?.evidenceFindings[index]
    if (!item || mapAtsSection(item.section) !== section) return null
    return { resultId: result.id, section, item: value }
  }
  const item = result.aiInsights?.prioritizedActions[index]
  if (!item || mapAtsSection(item.section) !== section) return null
  return { resultId: result.id, section, item: value }
}

async function resolveAtsHandoff(requestedResumeId: number) {
  // 解析「从 ATS 检测报告跳过来编辑」的 URL 参数（section / atsResultId / sourceVersionId / atsItem）。
  // 缺参直接返回 null（普通编辑入口）；有参则拉取 ATS 报告并核对它确实属于当前简历和源版本，
  // 属实才返回交接上下文，供 loadEditor 决定加载哪个版本、跳到哪个章节。
  const section = mapAtsSection(Array.isArray(route.query.section) ? route.query.section[0] : route.query.section)
  const resultId = positiveQueryInteger(route.query.atsResultId)
  const sourceVersionId = positiveQueryInteger(route.query.sourceVersionId)
  if (!section || !resultId || !sourceVersionId) return null
  try {
    const result = (await getAtsCheck(resultId))
    if (result.resumeId !== requestedResumeId || result.resumeVersionId !== sourceVersionId) return null
    const context = handoffItem(result, route.query.atsItem, section)
    return context ? { context, sourceVersionId } : null
  } catch {
    error.value = t('resumeEditor.atsContextUnavailable')
    return null
  }
}

function isAtsEditableSuccessor(version: ResumeVersion | null, context: AtsHandoffContext, sourceVersionId: number) {
  if (!version || version.restoredFromVersionId !== sourceVersionId) return false
  const provenance = version.generationContext?.atsProvenance
  const match = /^(evidence|action):(0|[1-9]\d*)$/.exec(context.item)
  if (!provenance || typeof provenance !== 'object' || Array.isArray(provenance) || !match) return false
  const fields = provenance as Record<string, unknown>
  return fields.resultId === context.resultId
    && fields.sourceVersionId === sourceVersionId
    && fields.mappedSection === context.section
    && fields.itemKind === match[1]
    && fields.itemIndex === Number(match[2])
}

const materialTypesBySection: Partial<Record<SectionKey, MaterialType[]>> = {
  work: ['WORK_EXPERIENCE', 'HIGHLIGHT', 'ACHIEVEMENT', 'LEADERSHIP_EXPERIENCE'],
  volunteering: ['VOLUNTEER_EXPERIENCE', 'LEADERSHIP_EXPERIENCE', 'ACHIEVEMENT'],
  projects: ['PROJECT_EXPERIENCE', 'HIGHLIGHT', 'ACHIEVEMENT'],
  skills: ['SKILL', 'SKILL_EVIDENCE'],
  education: ['EDUCATION'], courses: ['COURSE'], certificates: ['CERTIFICATE'], publications: ['PUBLICATION'], awards: ['AWARD'], customSections: ['LEADERSHIP_EXPERIENCE', 'ACHIEVEMENT'],
}
const materialCandidates = computed(() => {
  const types = materialTypesBySection[activeSection.value] ?? []
  return materialLibrary.value.filter((item) => types.includes(item.materialType))
})
const canInsertMaterial = computed(() => materialCandidates.value.length > 0 && selectedMaterialId.value !== null && !materialInsertLoading.value)

async function loadMaterialLibrary() {
  if (materialLibraryLoading.value || materialLibrary.value.length) return
  const epoch = editorContextEpoch
  const targetResumeId = props.id
  materialLibraryLoading.value = true
  error.value = ''
  try {
    const materials = (await listMaterials())
    if (props.id === targetResumeId && epoch === editorContextEpoch) materialLibrary.value = materials
  } catch {
    if (props.id === targetResumeId && epoch === editorContextEpoch) error.value = t('resumeEditor.materialLibraryLoadFailed')
  } finally {
    if (props.id === targetResumeId && epoch === editorContextEpoch) materialLibraryLoading.value = false
  }
}
function textFromMaterial(material: CareerMaterial, keys: string[]) {
  const content = material.contentJson ?? {}
  for (const key of keys) if (typeof content[key] === 'string' && content[key].trim()) return content[key] as string
  return material.sourceText?.trim() || ''
}
/**
 * 把素材（careerMaterial = 用户在日常记录里沉淀的经历片段，如一段工作/项目描述）
 * 携带的结构化时间区间（以及旧版 period 字段）搬进插入后的简历条目。
 * 空值一律省略，避免手动插入时凭空造出一个空日期字段。
 */
function timeFieldsFromMaterial(material: CareerMaterial) {
  const content = material.contentJson ?? {}
  const startDate = typeof content.startDate === 'string' ? content.startDate.trim() : ''
  const endDate = typeof content.endDate === 'string' ? content.endDate.trim() : ''
  const period = typeof content.period === 'string' ? content.period.trim() : ''
  return {
    ...(startDate ? { startDate } : {}),
    ...(endDate ? { endDate } : {}),
    ...(period ? { period } : {}),
  }
}
async function insertMaterial() {
  // 把选中的素材插入当前章节。整体流程：
  // ①取选中素材 ID → ②拉取素材详情 → ③按当前章节类型把素材字段映射成简历条目（如 work 映射 company/position/时间区间）
  // → ④写入 JSON 源码并清空选中态。
  // 每一步 await 之后都要核对「简历 ID 没换 + epoch 还是当前值」再写状态，
  // 防止用户已切换简历后，旧素材插入结果写进新页面。
  const id = selectedMaterialId.value
  if (!id || materialInsertLoading.value) return
  const epoch = editorContextEpoch
  const targetSection = activeSection.value
  const targetResumeId = props.id
  materialInsertLoading.value = true
  error.value = ''
  try {
    const material = await getMaterial(id)
    if (props.id !== targetResumeId || epoch !== editorContextEpoch) return
    const outcome = textFromMaterial(material, ['outcome', 'result', 'outcomeEvidence'])
    const description = textFromMaterial(material, ['description', 'summary', 'applicationDescription', 'responsibilityScope'])
    if (targetSection === 'work') updateResume((d) => { d.work = [...(Array.isArray(d.work) ? d.work : []), { company: textFromMaterial(material, ['company', 'organization']) || material.title, position: textFromMaterial(material, ['position', 'role']), ...timeFieldsFromMaterial(material), description, highlights: outcome ? [outcome] : [] }] })
    if (targetSection === 'projects') updateResume((d) => { d.projects = [...(Array.isArray(d.projects) ? d.projects : []), { name: textFromMaterial(material, ['name']) || material.title, role: textFromMaterial(material, ['role', 'position']), ...timeFieldsFromMaterial(material), description, highlights: outcome ? [outcome] : [] }] })
    if (targetSection === 'skills') updateResume((d) => { d.skills = [...(Array.isArray(d.skills) ? d.skills : []), { name: textFromMaterial(material, ['skillName', 'name']) || material.title }] })
    if (targetSection === 'education') updateResume((d) => { d.education = [...(Array.isArray(d.education) ? d.education : []), { school: textFromMaterial(material, ['school', 'institution']) || material.title, degree: textFromMaterial(material, ['degree']), major: textFromMaterial(material, ['major', 'area']), ...timeFieldsFromMaterial(material) }] })
    if (targetSection === 'volunteering') updateResume((d) => { d.volunteering = [...(Array.isArray(d.volunteering) ? d.volunteering : []), { organization: textFromMaterial(material, ['organization', 'company']) || material.title, role: textFromMaterial(material, ['role', 'position']), ...timeFieldsFromMaterial(material), description, highlights: outcome ? [outcome] : [] }] })
    if (targetSection === 'courses') updateResume((d) => { d.courses = [...(Array.isArray(d.courses) ? d.courses : []), { name: textFromMaterial(material, ['name', 'courseName']) || material.title, provider: textFromMaterial(material, ['provider', 'institution']), date: textFromMaterial(material, ['date']), description }] })
    if (targetSection === 'certificates') updateResume((d) => { d.certificates = [...(Array.isArray(d.certificates) ? d.certificates : []), { name: textFromMaterial(material, ['name', 'title']) || material.title, issuer: textFromMaterial(material, ['issuer', 'organization']), date: textFromMaterial(material, ['date']) }] })
    if (targetSection === 'publications') updateResume((d) => { d.publications = [...(Array.isArray(d.publications) ? d.publications : []), { title: textFromMaterial(material, ['title', 'name']) || material.title, publisher: textFromMaterial(material, ['publisher', 'issuer']), date: textFromMaterial(material, ['date']), url: textFromMaterial(material, ['url', 'link']), description }] })
    if (targetSection === 'awards') updateResume((d) => { d.awards = [...(Array.isArray(d.awards) ? d.awards : []), { name: textFromMaterial(material, ['name', 'title']) || material.title, issuer: textFromMaterial(material, ['issuer', 'organization']), date: textFromMaterial(material, ['date']), description }] })
    if (targetSection === 'customSections') updateResume((d) => { d.customSections = [...(Array.isArray(d.customSections) ? d.customSections : []), { title: material.title, entries: [{ name: material.title, organization: textFromMaterial(material, ['organization', 'company']), role: textFromMaterial(material, ['role', 'position']), ...timeFieldsFromMaterial(material), description, highlights: outcome ? [outcome] : [] }] }] })
    selectedMaterialId.value = null
  } catch {
    if (props.id === targetResumeId && epoch === editorContextEpoch) error.value = t('resumeEditor.materialInsertFailed')
  } finally {
    if (props.id === targetResumeId && epoch === editorContextEpoch) materialInsertLoading.value = false
  }
}

function isSectionCollapsed(section: SectionKey) { return collapsedSections.value.has(section) }
function toggleSection(section: SectionKey) {
  if (isSectionCollapsed(section)) { collapsedSections.value = new Set(sectionKeys.filter((k) => k !== section)); return }
  collapsedSections.value = new Set(sectionKeys)
}
function expandSection(section: SectionKey) { collapsedSections.value = new Set(sectionKeys.filter((k) => k !== section)) }
async function jumpToSection(section: SectionKey) {
  activeSection.value = section
  expandSection(section)
  await nextTick()
  const target = document.getElementById(`resume-${section}`)
  if (!target) return
  target.tabIndex = -1
  target.focus({ preventScroll: true })
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  target.scrollIntoView({ behavior: reduceMotion ? 'auto' : 'smooth', block: 'start' })
}

async function applyAtsArrival() {
  const context = atsContext.value
  if (!context) return
  await nextTick()
  await jumpToSection(context.section)
  atsAnnouncement.value = message('atsArrivalAnnouncement', { section: sectionLabel(context.section) })
}

async function openAiAssistant(scope: 'field' | 'section', label: string, section: string, value: unknown, apply?: (value: string) => void) {
  // ① 打开 AI 助手弹层，生成一个幂等键（同一操作重复提交时后端靠它识别并只执行一次）
  const contentValue = Array.isArray(value) ? value.filter(Boolean).join('\n') : String(value ?? '').trim()
  const assistant = { idempotencyKey: newIdempotencyKey(), scope, label, section, content: contentValue, loading: false, result: null, error: '', needsConsent: false, apply }
  aiAssistant.value = assistant
  if (!contentValue || !currentVersionId.value) return
  const epoch = editorContextEpoch
  aiAssistant.value.loading = true
  try {
    // ② 先创建一个 AI 任务，拿到任务 ID；③ 再轮询这个任务直到后端算完并返回结果。
    //    （轮询 = 每隔几秒问一次「好了吗」，直到结果出来或超时，因为 AI 生成是异步的、无法一次性返回）
    const createdTask = (await inlineOptimize({ resumeVersionId: currentVersionId.value, section, content: contentValue }, assistant.idempotencyKey))
    const result = await waitForAiTaskResult<InlineOptimizeResponse>(createdTask.id)
    // ④ 结果回来时双重校验：页面上下文没换过（epoch 相同）且弹层还是同一个任务（idempotencyKey 相同）
    //    才允许写入结果。用户中途关弹层或切换简历时，迟到的 AI 结果不会误写。
    if (epoch === editorContextEpoch && aiAssistant.value?.idempotencyKey === assistant.idempotencyKey) aiAssistant.value.result = result
  } catch (requestError: any) {
    if (epoch === editorContextEpoch && aiAssistant.value?.idempotencyKey === assistant.idempotencyKey) {
      // 错误码 40302 = 用户还没同意 AI 服务条款，引导去授权页；超时则给出任务 ID 让用户稍后查看
      aiAssistant.value.needsConsent = extractErrorCode(requestError) === 40302
      aiAssistant.value.error = aiAssistant.value.needsConsent
        ? t('resumeEditor.consentRequired')
        : requestError instanceof AiTaskTimeoutError
          ? t('common.taskStillProcessing', { taskId: requestError.taskId })
          : t('resumeEditor.aiUnavailable')
    }
  } finally { if (epoch === editorContextEpoch && aiAssistant.value?.idempotencyKey === assistant.idempotencyKey) aiAssistant.value.loading = false }
}

function applyAiCandidate(value: string) {
  const assistant = aiAssistant.value
  if (!assistant?.apply) return
  assistant.apply(value)
  summary.value = summary.value || message('aiAdoptedSummary', { label: assistant.label })
  closeAiAssistant()
}
function closeAiAssistant() { aiAssistant.value = null }

function sectionAiContent(section: 'basics' | 'work' | 'skills' | 'projects') {
  if (section === 'basics') return basics.value.summary ?? ''
  if (section === 'skills') return skills.value.map((item) => typeof item === 'string' ? item : item.name ?? item.keyword ?? '').filter(Boolean)
  const collection = section === 'work' ? work.value : projects.value
  return collection.flatMap((item) => [item.description, ...(Array.isArray(item.highlights) ? item.highlights : [])]).filter(Boolean)
}

function handleBeforeUnload(event: BeforeUnloadEvent) { if (!dirty.value) return; event.preventDefault(); event.returnValue = '' }

function updatePreviewPageCount() {
  const paper = previewPaperRef.value?.paperElement
  if (!paper) return
  if (!hasBodyContent.value) {
    previewPageCount.value = 1
    return
  }
  const a4Height = paper.clientWidth * 297 / 210
  const children = Array.from(paper.children) as HTMLElement[]
  const paperTop = paper.getBoundingClientRect().top
  const contentBottom = Math.max(0, ...children.map((child) => child.getBoundingClientRect().bottom - paperTop))
  const paddingBottom = Number.parseFloat(getComputedStyle(paper).paddingBottom) || 0
  previewPageCount.value = Math.max(1, Math.ceil((contentBottom + paddingBottom) / a4Height))
}

watch(content, async () => { await nextTick(); updatePreviewPageCount() })
async function connectPreviewObserver() {
  previewResizeObserver?.disconnect()
  if (!showPreview.value) return
  await nextTick()
  updatePreviewPageCount()
  const paper = previewPaperRef.value?.paperElement
  if (paper) {
    previewResizeObserver = new ResizeObserver(updatePreviewPageCount)
    previewResizeObserver.observe(paper)
  }
}
watch(showPreview, () => { void connectPreviewObserver() })
function handleKeydown(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 's') return
  event.preventDefault()
  if (!historicalSourceReadOnly.value && dirty.value && sourceValid.value && !saving.value) void save()
}
function goBack() { router.back() }

onBeforeRouteLeave(() => {
  if (!dirty.value) return true
  return window.confirm(t('resumeEditor.leaveConfirm'))
})
onBeforeRouteUpdate((to, from) => {
  if (!dirty.value) return true
  if (to.params.id === from.params.id && to.fullPath === from.fullPath) return true
  return window.confirm(t('resumeEditor.leaveConfirm'))
})

function updateResume(mutator: (draft: Record<string, any>) => void) {
  // JSON 原文写坏时表单无法解析出结构，改为提示并打开源码面板，而不是静默覆盖用户输入
  if (!sourceValid.value) { showSource.value = true; error.value = t('resumeEditor.sourceInvalidEdit'); return }
  const draft = JSON.parse(JSON.stringify(resume.value || {})) as Record<string, any>
  mutator(draft)
  content.value = JSON.stringify(draft, null, 2)
  error.value = ''
}

function removeWithUndo(label: string, mutator: (draft: Record<string, any>) => void) {
  // 删除条目时先把删除前的 JSON 全文存起来，弹一个「撤销」提示，6 秒后自动消失。
  // 用户点撤销就把旧全文写回去（等于整体回滚），比逐字段反向恢复更简单可靠。
  if (!sourceValid.value) { updateResume(() => undefined); return }
  const prev = content.value
  updateResume(mutator)
  undoRemoval.value = { content: prev, label }
  if (undoTimer) clearTimeout(undoTimer)
  undoTimer = setTimeout(() => { undoRemoval.value = null }, 6000)
}
function restoreRemoval() {
  if (!undoRemoval.value) return
  content.value = undoRemoval.value.content; undoRemoval.value = null
  if (undoTimer) clearTimeout(undoTimer)
}

function setBasic(field: string, value: string) { updateResume((d) => { d.basics = { ...(d.basics ?? {}), [field]: value } }) }
function setTemplate(code: string) { updateResume((d) => { d.template = { code } }) }
function setLayout<K extends keyof typeof defaultLayout>(field: K, value: typeof defaultLayout[K]) {
  updateResume((d) => { d.layout = { ...defaultLayout, ...(d.layout ?? {}), [field]: value } })
}
function applyLayoutPreset(preset: 'compact' | 'balanced' | 'spacious') {
  const presets = {
    compact: { bodyFontSize: 12, headingFontSize: 12, lineHeight: 1.45, sectionSpacing: 14, entrySpacing: 8, pagePadding: 42 },
    balanced: defaultLayout,
    spacious: { bodyFontSize: 14, headingFontSize: 14, lineHeight: 1.85, sectionSpacing: 28, entrySpacing: 16, pagePadding: 68 },
  }
  updateResume((d) => { d.layout = { ...presets[preset], fontFamily: d.layout?.fontFamily ?? defaultLayout.fontFamily } })
}
function resetLayout() { updateResume((d) => { d.layout = defaultLayout }) }
function contentSectionStyle(section: SortableSection) { return { order: String(10 + contentSectionOrder.value.indexOf(section)) } }
function startContentSectionDrag(section: SortableSection, event: DragEvent) {
  draggedContentSection.value = section
  contentSectionDropTarget.value = null
  event.dataTransfer?.setData('text/plain', `section:${section}`)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function updateContentSectionTarget(section: SortableSection, event: DragEvent) {
  if (!draggedContentSection.value || draggedContentSection.value === section) return
  const bounds = (event.currentTarget as HTMLElement).getBoundingClientRect()
  contentSectionDropTarget.value = { section, after: event.clientY > bounds.top + bounds.height / 2 }
}
function isContentSectionTarget(section: SortableSection, after: boolean) {
  return contentSectionDropTarget.value?.section === section && contentSectionDropTarget.value.after === after
}
function dropContentSection(section: SortableSection) {
  const dragged = draggedContentSection.value
  const target = contentSectionDropTarget.value
  if (!dragged || !target || target.section !== section) return endContentSectionDrag()
  const order = [...contentSectionOrder.value]
  const from = order.indexOf(dragged)
  let insertion = order.indexOf(section) + (target.after ? 1 : 0)
  if (from < insertion) insertion--
  if (from !== insertion) {
    order.splice(from, 1)
    order.splice(insertion, 0, dragged)
    updateResume((d) => { d.layout = { ...defaultLayout, ...(d.layout ?? {}), sectionOrder: order } })
  }
  endContentSectionDrag()
}
function endContentSectionDrag() { draggedContentSection.value = null; contentSectionDropTarget.value = null }
function addWork() { expandSection('work'); updateResume((d) => { d.work = [...(Array.isArray(d.work) ? d.work : []), { company: '', position: '', startDate: '', endDate: '' }] }) }
function setWork(index: number, field: string, value: unknown) { updateResume((d) => { const items = Array.isArray(d.work) ? [...d.work] : []; items[index] = { ...(items[index] ?? {}), [field]: value }; d.work = items }) }
function removeWork(index: number) { const label = work.value[index]?.company || work.value[index]?.position || message('workItem', { index: index + 1 }); removeWithUndo(label, (d) => { d.work = (Array.isArray(d.work) ? d.work : []).filter((_: unknown, i: number) => i !== index) }) }
function setWorkHighlights(index: number, value: string) { setWork(index, 'highlights', value.split('\n').map((i) => i.trim()).filter(Boolean)) }
function addSkill() { expandSection('skills'); updateResume((d) => { d.skills = [...(Array.isArray(d.skills) ? d.skills : []), { name: '' }] }) }
function setSkill(index: number, value: string) { updateResume((d) => { const items = Array.isArray(d.skills) ? [...d.skills] : []; items[index] = typeof items[index] === 'string' ? value : { ...(items[index] ?? {}), name: value }; d.skills = items }) }
function removeSkill(index: number) { const item = skills.value[index]; const label = (typeof item === 'string' ? item : item?.name || item?.keyword) || message('skillItem', { index: index + 1 }); removeWithUndo(label, (d) => { d.skills = (Array.isArray(d.skills) ? d.skills : []).filter((_: unknown, i: number) => i !== index) }) }
function addEducation() { expandSection('education'); updateResume((d) => { d.education = [...(Array.isArray(d.education) ? d.education : []), { school: '', degree: '', major: '', startDate: '', endDate: '' }] }) }
function setEducation(index: number, field: string, value: string) { updateResume((d) => { const items = Array.isArray(d.education) ? [...d.education] : []; items[index] = { ...(items[index] ?? {}), [field]: value }; d.education = items }) }
function removeEducation(index: number) { const label = education.value[index]?.school || message('educationItem', { index: index + 1 }); removeWithUndo(label, (d) => { d.education = (Array.isArray(d.education) ? d.education : []).filter((_: unknown, i: number) => i !== index) }) }
function addProject() { expandSection('projects'); updateResume((d) => { d.projects = [...(Array.isArray(d.projects) ? d.projects : []), { name: '', role: '', startDate: '', endDate: '', description: '', highlights: [] }] }) }
function setProject(index: number, field: string, value: unknown) { updateResume((d) => { const items = Array.isArray(d.projects) ? [...d.projects] : []; items[index] = { ...(items[index] ?? {}), [field]: value }; d.projects = items }) }
function removeProject(index: number) { const label = projects.value[index]?.name || message('projectItem', { index: index + 1 }); removeWithUndo(label, (d) => { d.projects = (Array.isArray(d.projects) ? d.projects : []).filter((_: unknown, i: number) => i !== index) }) }
function moveItem(section: SortableSection, index: number, direction: -1 | 1) {
  updateResume((d) => { const items = Array.isArray(d[section]) ? [...d[section]] : []; const ti = index + direction; if (ti < 0 || ti >= items.length) return; [items[index], items[ti]] = [items[ti], items[index]]; d[section] = items })
}
function startItemDrag(section: SortableSection, index: number, event: DragEvent) {
  draggedItem.value = { section, index }
  dragTarget.value = null
  event.dataTransfer?.setData('text/plain', `${section}:${index}`)
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'move'
}
function updateDragTarget(section: SortableSection, index: number, event: DragEvent) {
  if (!draggedItem.value || draggedItem.value.section !== section) return
  const bounds = (event.currentTarget as HTMLElement).getBoundingClientRect()
  dragTarget.value = { section, index, after: event.clientY > bounds.top + bounds.height / 2 }
}
function isDragTarget(section: SortableSection, index: number, after: boolean) {
  return dragTarget.value?.section === section && dragTarget.value.index === index && dragTarget.value.after === after
}
function dropItem(section: SortableSection, index: number, event: DragEvent) {
  const dragging = draggedItem.value
  const target = dragTarget.value
  if (!dragging || !target || dragging.section !== section || target.section !== section) return endItemDrag()
  let insertionIndex = index + (target.after ? 1 : 0)
  if (dragging.index < insertionIndex) insertionIndex--
  if (dragging.index !== insertionIndex) {
    updateResume((d) => {
      const items = Array.isArray(d[section]) ? [...d[section]] : []
      const [moved] = items.splice(dragging.index, 1)
      if (moved !== undefined) items.splice(insertionIndex, 0, moved)
      d[section] = items
    })
  }
  endItemDrag()
}
function endItemDrag() { draggedItem.value = null; dragTarget.value = null }
function addSimpleItem(section: 'certificates' | 'awards' | 'languages') { expandSection(section); updateResume((d) => { const item = section === 'certificates' ? { name: '', issuer: '', date: '' } : section === 'awards' ? { name: '', issuer: '', date: '' } : { name: '', level: '' }; d[section] = [...(Array.isArray(d[section]) ? d[section] : []), item] }) }
function setSimpleItem(section: 'certificates' | 'awards' | 'languages', index: number, field: string, value: string) { setSectionItem(section, index, field, value) }
function removeSimpleItem(section: 'certificates' | 'awards' | 'languages', index: number) { const col = section === 'certificates' ? certificates.value : section === 'awards' ? awards.value : languages.value; const label = col[index]?.name || (section === 'certificates' ? message('certificateItem', { index: index + 1 }) : section === 'awards' ? message('awardItem', { index: index + 1 }) : message('languageItem', { index: index + 1 })); removeSectionItem(section, index, label) }
function setSectionItem(section: SortableSection, index: number, field: string, value: unknown) { updateResume((d) => { const items = Array.isArray(d[section]) ? [...d[section]] : []; items[index] = { ...(items[index] ?? {}), [field]: value }; d[section] = items }) }
function removeSectionItem(section: SortableSection, index: number, label: string) { removeWithUndo(label, (d) => { d[section] = (Array.isArray(d[section]) ? d[section] : []).filter((_: unknown, i: number) => i !== index) }) }
function addStructuredItem(section: 'volunteering' | 'courses' | 'publications') {
  expandSection(section)
  const item = section === 'volunteering' ? { organization: '', role: '', startDate: '', endDate: '', description: '', highlights: [] }
    : section === 'courses' ? { name: '', provider: '', date: '', description: '' }
      : { title: '', publisher: '', date: '', url: '', description: '' }
  updateResume((d) => { d[section] = [...(Array.isArray(d[section]) ? d[section] : []), item] })
}
function setObjective(field: string, value: string) { updateResume((d) => { d.objective = { ...(d.objective ?? {}), [field]: value } }) }
function addLink() { expandSection('links'); updateResume((d) => { d.links = [...(Array.isArray(d.links) ? d.links : []), { label: '', url: '' }] }) }
function addCustomSection() { expandSection('customSections'); updateResume((d) => { d.customSections = [...(Array.isArray(d.customSections) ? d.customSections : []), { title: '', entries: [{ name: '', organization: '', role: '', startDate: '', endDate: '', description: '', highlights: [] }] }] }) }
function setCustomSection(index: number, field: string, value: unknown) { updateResume((d) => { const sections = Array.isArray(d.customSections) ? [...d.customSections] : []; sections[index] = { ...(sections[index] ?? {}), [field]: value }; d.customSections = sections }) }
function addCustomEntry(index: number) { updateResume((d) => { const sections = Array.isArray(d.customSections) ? [...d.customSections] : []; const section = { ...(sections[index] ?? {}) }; section.entries = [...(Array.isArray(section.entries) ? section.entries : []), { name: '', organization: '', role: '', startDate: '', endDate: '', description: '', highlights: [] }]; sections[index] = section; d.customSections = sections }) }
function setCustomEntry(sectionIndex: number, entryIndex: number, field: string, value: unknown) { updateResume((d) => { const sections = Array.isArray(d.customSections) ? [...d.customSections] : []; const section = { ...(sections[sectionIndex] ?? {}) }; const entries = Array.isArray(section.entries) ? [...section.entries] : []; entries[entryIndex] = { ...(entries[entryIndex] ?? {}), [field]: value }; section.entries = entries; sections[sectionIndex] = section; d.customSections = sections }) }
function removeCustomEntry(sectionIndex: number, entryIndex: number) { removeWithUndo(t('resumeEditor.entryName'), (d) => { const sections = Array.isArray(d.customSections) ? [...d.customSections] : []; const section = { ...(sections[sectionIndex] ?? {}) }; section.entries = (Array.isArray(section.entries) ? section.entries : []).filter((_: unknown, index: number) => index !== entryIndex); sections[sectionIndex] = section; d.customSections = sections }) }
function moveCustomEntry(sectionIndex: number, entryIndex: number, direction: -1 | 1) { updateResume((d) => { const sections = Array.isArray(d.customSections) ? [...d.customSections] : []; const section = { ...(sections[sectionIndex] ?? {}) }; const entries = Array.isArray(section.entries) ? [...section.entries] : []; const target = entryIndex + direction; if (target < 0 || target >= entries.length) return; [entries[entryIndex], entries[target]] = [entries[target], entries[entryIndex]]; section.entries = entries; sections[sectionIndex] = section; d.customSections = sections }) }
function defaultResumeJson() { return { basics: { name: '' }, objective: { targetRole: '', targetIndustry: '', location: '', summary: '' }, links: [], work: [], volunteering: [], education: [], skills: [], projects: [], courses: [], certificates: [], publications: [], awards: [], languages: [], customSections: [], template: { code: 'classic' }, layout: defaultLayout } }

async function loadEditor() {
  // 编辑器加载总入口。流程：
  // ①自增两个代次（editorLoadSequence 管「本次加载是否还有效」，editorContextEpoch 管
  //   「本页面上下文是否还有效」），之后每个 await 分支都要核对，否则旧上下文的响应会污染新页面；
  // ②清空上一份简历留下的所有状态（版本号、ATS 交接、素材、AI 弹层、草稿读取态等）；
  // ③并行拉取简历详情和 ATS 交接上下文（从 ATS 报告跳转过来时 URL 上带 section/atsResultId 等参数）；
  // ④决定加载哪个版本：正常情况用 currentVersionId；ATS 交接默认加载历史「源版本」（只读），
  //   只有 URL 带了合法的 editVersionId（可编辑后继版本）才加载它；
  // ⑤把版本 JSON 序列化进编辑器，记录 initialContent 作为 dirty 判断基准；非只读时尝试读本地草稿；
  // ⑥catch 里区分两种失败：创建可编辑后继版本失败（回退并清掉 editVersionId 参数）和真正的加载失败（显示重试页）。
  // 先自增两个代次：之后所有 await 回来的分支都要再核对，否则旧上下文的响应会污染新页面
  const loadSequence = ++editorLoadSequence
  editorContextEpoch += 1
  const requestedResumeId = Number(props.id)
  loading.value = true
  loadFailed.value = false
  error.value = ''
  currentVersionId.value = null
  atsSourceVersionId.value = null
  historicalSourceReadOnly.value = false
  atsContext.value = null
  atsAnnouncement.value = ''
  // Invalidate route-scoped async state from the previous editor context:
  // stale material/insert/save/inline completions must not affect this load.
  materialLibraryLoading.value = false
  materialInsertLoading.value = false
  selectedMaterialId.value = null
  saving.value = false
  aiAssistant.value = null
  let loadedPendingSuccessor = false
  try {
    const [resumeResponse, handoff] = await Promise.all([
      getResume(requestedResumeId),
      resolveAtsHandoff(requestedResumeId),
    ])
    const currentResumeVersionId = resumeResponse.currentVersionId
    let loadedVersionId = currentResumeVersionId
    let requestedEditableSuccessor = false
    if (handoff) {
      const requestedEditVersionId = positiveQueryInteger(route.query.editVersionId)
      requestedEditableSuccessor = requestedEditVersionId === currentResumeVersionId
        && requestedEditVersionId !== handoff.sourceVersionId
      loadedVersionId = requestedEditableSuccessor ? requestedEditVersionId : handoff.sourceVersionId
      atsSourceVersionId.value = handoff.sourceVersionId
      atsContext.value = handoff.context
    }
    if (loadedVersionId == null) {
      const versionsResponse = await listVersions(requestedResumeId)
      loadedVersionId = versionsResponse[0]?.id ?? null
    }
    let currentVersion = loadedVersionId
      ? (await getResumeVersion(loadedVersionId))
      : null
    if (handoff && requestedEditableSuccessor
      && !isAtsEditableSuccessor(currentVersion, handoff.context, handoff.sourceVersionId)) {
      loadedVersionId = handoff.sourceVersionId
      currentVersion = (await getResumeVersion(loadedVersionId))
    }
    if (loadSequence !== editorLoadSequence) return
    historicalSourceReadOnly.value = Boolean(handoff && loadedVersionId !== currentResumeVersionId)
    currentVersionId.value = loadedVersionId
    content.value = JSON.stringify(currentVersion?.resumeJson ?? defaultResumeJson(), null, 2)
    initialContent.value = content.value
    if (!historicalSourceReadOnly.value) readDraft()
    loadedPendingSuccessor = currentVersionId.value === pendingEditableSuccessorId.value && !historicalSourceReadOnly.value
    if (loadedPendingSuccessor) {
      pendingEditableSuccessorId.value = null
      editableCreationError.value = ''
    }
    void connectPreviewObserver()
  } catch {
    if (loadSequence !== editorLoadSequence) return
    const requestedEditableSuccessorId = positiveQueryInteger(route.query.editVersionId)
    if (atsContext.value && requestedEditableSuccessorId && requestedEditableSuccessorId === pendingEditableSuccessorId.value) {
      editableCreationError.value = t('resumeEditor.atsCreateEditableFailed')
      const query = { ...route.query }
      delete query.editVersionId
      await router.replace({ query })
      return
    }
    loadFailed.value = true
  } finally {
    if (loadSequence === editorLoadSequence) {
      loading.value = false
      if (atsContext.value) await applyAtsArrival()
      if (loadedPendingSuccessor) atsAnnouncement.value = t('resumeEditor.atsEditableCreated')
    }
  }
}

async function createEditableSuccessor() {
  // ATS 交接时加载的是历史「源版本」（只读）。用户点「创建可编辑版本」后：
  // ①调 restoreResumeVersion 把源版本复制成一个新版本（后端会写入 ATS 溯源信息 atsProvenance）；
  // ②把新版本 ID 存进 pendingEditableSuccessorId，然后通过 router.replace 把 editVersionId
  //   写进 URL → 触发 watch → 重新 loadEditor 加载这个可编辑版本。
  // pendingEditableSuccessorId 的作用：若后端其实创建成功了但随后的重新加载失败，
  // 用户再点一次按钮时直接复用已创建的版本 ID 重试加载，不会重复创建第二个版本。
  const sourceVersionId = atsSourceVersionId.value
  const context = atsContext.value
  if (!sourceVersionId || !context || creatingEditableVersion.value) return
  creatingEditableVersion.value = true
  editableCreationError.value = ''
  try {
    const successorId = pendingEditableSuccessorId.value
      ?? (await restoreResumeVersion(Number(props.id), sourceVersionId, {
        atsResultId: context.resultId,
        atsItem: context.item,
      })).id
    pendingEditableSuccessorId.value = successorId
    resetDraft()
    await router.replace({
      query: { ...route.query, editVersionId: String(successorId) },
    })
  } catch {
    editableCreationError.value = t('resumeEditor.atsCreateEditableFailed')
  } finally {
    creatingEditableVersion.value = false
  }
}

watch(() => props.id, () => {
  resetDraft()
  summary.value = ''
  activeSection.value = 'basics'
  collapsedSections.value = new Set(sectionKeys.filter((section) => section !== 'basics'))
  selectedMaterialId.value = null
  materialLibraryLoading.value = false
  materialInsertLoading.value = false
  saving.value = false
  aiAssistant.value = null
  showPreview.value = false
  showDesign.value = false
  showTemplatePicker.value = false
  void loadEditor()
})

watch(
  () => [route.query.section, route.query.atsResultId, route.query.sourceVersionId, route.query.atsItem, route.query.editVersionId],
  (next, previous) => {
    if (next.every((value, index) => value === previous[index])) return
    resetDraft()
    summary.value = ''
    void loadEditor()
  },
)

onMounted(() => {
  try { sidebarCollapsed.value = localStorage.getItem('resume-editor-sidebar-collapsed') === 'true' } catch { /* storage is optional */ }
  // The content-first editor no longer supports a collapsed editing canvas.
  editorPanelCollapsed.value = false
  window.addEventListener('beforeunload', handleBeforeUnload); window.addEventListener('keydown', handleKeydown)
  void loadEditor()
})

onBeforeUnmount(() => { window.removeEventListener('beforeunload', handleBeforeUnload); window.removeEventListener('keydown', handleKeydown); previewResizeObserver?.disconnect(); if (undoTimer) clearTimeout(undoTimer) })

async function save() {
  // 手动保存 = 把当前 JSON 原文提交成一个新版本（后端存快照，可回滚）。流程：
  // ①守卫：只读的历史版本不允许写新版本；JSON 非法时不提交，避免把坏数据写入版本；
  // ②记下当时的 epoch 和简历 ID（提交时快照）；
  // ③调 createManualVersion 创建版本，成功后清掉这份简历的本地草稿（草稿已无用）；
  // ④核对 epoch/简历 ID 仍有效后，更新 initialContent（dirty 归零）并跳转详情页；
  // ⑤失败则把后端错误翻译给用户。所有后续步骤都先核对代次，慢的旧保存不能影响新上下文。
  // 只读的历史版本不允许写新版本；JSON 非法时不提交，避免把坏数据写入版本
  if (historicalSourceReadOnly.value) return
  let resumeJson: Record<string, unknown>
  try { resumeJson = JSON.parse(content.value) as Record<string, unknown> } catch { error.value = t('resumeEditor.resumeJsonInvalid'); return }
  const epoch = editorContextEpoch
  const submittedResumeId = props.id
  saving.value = true; error.value = ''
  try {
    await createManualVersion(Number(submittedResumeId), resumeJson, summary.value.trim() || undefined)
    clearDraftFor(submittedResumeId)
    if (props.id !== submittedResumeId || epoch !== editorContextEpoch) return
    initialContent.value = content.value
    await router.push({ name: 'resume-detail', params: { id: submittedResumeId } })
  } catch (requestError) {
    if (props.id === submittedResumeId && epoch === editorContextEpoch) {
      error.value = resolveApiError(requestError, 'resumeEditor.saveFailed')
    }
  }
  finally { if (props.id === submittedResumeId && epoch === editorContextEpoch) saving.value = false }
}
</script>

<template>
  <section class="resume-studio">
    <header v-if="!loadFailed && !showPreview" class="studio-head">
      <div><p class="eyebrow">{{ t('resumeEditor.studioEyebrow') }}</p><h1>{{ t('resumeEditor.title') }}</h1><p>{{ t('resumeEditor.studioSubtitle') }}</p></div>
      <div class="studio-actions">
        <button v-if="!historicalSourceReadOnly" class="btn-neon btn-ghost btn-sample" type="button" @click="loadSample"><BookOpen :size="16" /> {{ t('resumeEditor.loadSample') }}</button>
        <button v-if="!historicalSourceReadOnly" class="btn-neon btn-ghost" type="button" @click="openTemplatePicker">{{ t('resumeEditor.chooseTemplate') }}</button>
        <button v-if="!historicalSourceReadOnly" class="btn-neon btn-ghost" type="button" @click="showDesign ? closeDesign() : openDesign()">{{ showDesign ? t('resumeEditor.backToContent') : t('resumeEditor.designAdvanced') }}</button>
        <button v-if="showDesign" class="btn-neon btn-ghost" type="button" @click="showSource = !showSource">{{ showSource ? t('resumeEditor.closeJson') : t('resumeEditor.editJson') }}</button>
        <button class="btn-neon btn-ghost" type="button" @click="openPreview">{{ t('resumeEditor.preview') }}</button>
        <button class="btn-neon btn-ghost" type="button" @click="goBack">{{ t('common.back') }}</button>
        <button v-if="showDesign && !historicalSourceReadOnly" class="btn-neon btn-primary" type="button" :disabled="saving || !sourceValid || !dirty" @click="save">{{ saving ? t('common.saving') : dirty ? t('resumeEditor.saveNewVersion') : t('resumeEditor.contentSynced') }}</button>
        <button v-else-if="!historicalSourceReadOnly" form="resume-form" class="btn-neon btn-primary" :disabled="saving || !sourceValid || !dirty">{{ saving ? t('common.saving') : dirty ? t('resumeEditor.saveNewVersion') : t('resumeEditor.contentSynced') }}</button>
      </div>
    </header>
    <p class="sr-only" role="status" aria-live="polite">{{ atsAnnouncement }}</p>
    <AtsHandoffReturnNav v-if="!loadFailed && atsContext" :result-id="atsContext.resultId" />
    <AtsVersionLockBanner v-if="!loadFailed && historicalSourceReadOnly" :creating="creatingEditableVersion" :error="editableCreationError" @create="createEditableSuccessor" />
    <TemplatePickerDialog v-if="!loadFailed && showTemplatePicker" :template-code="templateCode" :template-options="templateOptions" @select="setTemplate" @close="closeTemplatePicker" />
    <p v-if="error && !loadFailed" class="form-error" role="alert">{{ error }}</p>
    <SampleConfirmDialog v-if="!loadFailed && showSampleConfirm" @confirm="loadSample" @close="showSampleConfirm = false" />
    <DraftRestoreBanner v-if="!loadFailed && restoreCandidate" @restore="applyDraft" @discard="clearDraft" />
    <p v-if="loading">{{ t('common.loading') }}</p>
    <section v-else-if="loadFailed" class="editor-load-error" role="alert">
      <p class="eyebrow">{{ t('common.error') }}</p><h1>{{ t('resumeEditor.loadErrorTitle') }}</h1><p>{{ t('resumeEditor.loadErrorDescription') }}</p>
      <div><button class="btn-neon btn-primary" type="button" @click="loadEditor">{{ t('resumeEditor.retryLoad') }}</button><RouterLink class="btn-neon btn-ghost" to="/resumes">{{ t('resumeEditor.returnToResumeList') }}</RouterLink></div>
    </section>
    <div v-else-if="!showPreview" class="studio-grid" :class="{ 'sidebar-collapsed': sidebarCollapsed, 'editor-panel-collapsed': editorPanelCollapsed }">
      <div class="editor-shell">
        <ResumeEditorNavigation
          v-if="!showDesign"
          :sections="navigationSections"
          :active-section="activeSection"
          :completion-score="completionScore"
          :next-suggestion="nextSuggestion"
          :completion-label="t('resumeEditor.completion')"
          :design-label="t('resumeEditor.designAdvanced')"
          :preview-label="t('resumeEditor.previewResume')"
          :sections-label="t('resumeEditor.studioEyebrow')"
          :content-label="t('resumeEditor.title')"
          @select="selectSection"
          @open-design="openDesign"
          @open-preview="openPreview"
        />
        <aside v-if="showDesign" class="editor-sidebar" :aria-label="t('resumeEditor.designSettingsLabel')">
          <div class="design-workspace-actions">
            <button ref="designBackButtonRef" class="btn-neon btn-ghost" type="button" @click="closeDesign"><ArrowLeft :size="16" /> {{ t('resumeEditor.backToContent') }}</button>
            <button class="btn-neon btn-primary" type="button" @click="openPreview">{{ t('resumeEditor.previewResume') }}</button>
          </div>
          <button class="sidebar-toggle" type="button" :aria-expanded="!sidebarCollapsed" :title="sidebarCollapsed ? t('resumeEditor.expandSidebar') : t('resumeEditor.collapseSidebar')" @click="toggleSidebar">
            <PanelLeftOpen v-if="sidebarCollapsed" :size="16" /><PanelLeftClose v-else :size="16" />
            <span>{{ sidebarCollapsed ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</span>
          </button>
          <div class="design-workspace-layout">
          <div class="editor-command-center">
          <label class="version-note">{{ t('resumeEditor.versionNote') }}<input v-model.trim="summary" maxlength="1000" :placeholder="t('resumeEditor.versionNotePlaceholder')" /></label>
          <fieldset class="layout-controls">
            <legend>{{ t('resumeEditor.layoutSettings') }}</legend>
            <div class="layout-presets" role="group" :aria-label="t('resumeEditor.densityPresets')">
              <button type="button" @click="applyLayoutPreset('compact')">{{ t('resumeEditor.compact') }}</button>
              <button type="button" @click="applyLayoutPreset('balanced')">{{ t('resumeEditor.balanced') }}</button>
              <button type="button" @click="applyLayoutPreset('spacious')">{{ t('resumeEditor.spacious') }}</button>
            </div>
            <label class="font-family-select">{{ t('resumeEditor.fontStyle') }}
              <select :aria-label="t('resumeEditor.fontStyle')" :value="layout.fontFamily" @change="setLayout('fontFamily', ($event.target as HTMLSelectElement).value)">
                <option v-for="option in fontOptions" :key="option.code" :value="option.code">{{ option.name() }}</option>
              </select>
            </label>
            <label>{{ t('resumeEditor.bodyFontSize') }} <output>{{ layout.bodyFontSize }} px</output><input :aria-label="t('resumeEditor.bodyFontSize')" type="range" min="11" max="16" step="1" :value="layout.bodyFontSize" @input="setLayout('bodyFontSize', Number(($event.target as HTMLInputElement).value))" /></label>
            <label>{{ t('resumeEditor.headingFontSize') }} <output>{{ layout.headingFontSize }} px</output><input :aria-label="t('resumeEditor.headingFontSize')" type="range" min="11" max="18" step="1" :value="layout.headingFontSize" @input="setLayout('headingFontSize', Number(($event.target as HTMLInputElement).value))" /></label>
            <label>{{ t('resumeEditor.lineHeight') }} <output>{{ layout.lineHeight.toFixed(2) }}</output><input :aria-label="t('resumeEditor.lineHeight')" type="range" min="1.30" max="2.00" step="0.05" :value="layout.lineHeight" @input="setLayout('lineHeight', Number(($event.target as HTMLInputElement).value))" /></label>
            <label>{{ t('resumeEditor.sectionSpacing') }} <output>{{ layout.sectionSpacing }} px</output><input :aria-label="t('resumeEditor.sectionSpacing')" type="range" min="10" max="32" step="2" :value="layout.sectionSpacing" @input="setLayout('sectionSpacing', Number(($event.target as HTMLInputElement).value))" /></label>
            <label>{{ t('resumeEditor.entrySpacing') }} <output>{{ layout.entrySpacing }} px</output><input :aria-label="t('resumeEditor.entrySpacing')" type="range" min="6" max="22" step="2" :value="layout.entrySpacing" @input="setLayout('entrySpacing', Number(($event.target as HTMLInputElement).value))" /></label>
            <label>{{ t('resumeEditor.pagePadding') }} <output>{{ layout.pagePadding }} px</output><input :aria-label="t('resumeEditor.pagePadding')" type="range" min="32" max="80" step="2" :value="layout.pagePadding" @input="setLayout('pagePadding', Number(($event.target as HTMLInputElement).value))" /></label>
            <button class="layout-reset" type="button" @click="resetLayout">{{ t('resumeEditor.resetLayout') }}</button>
          </fieldset>
          </div>
          <section class="design-live-preview" :aria-label="t('resumeEditor.previewWorkspaceLabel')">
            <header><span>{{ t('resumeEditor.previewResume') }}</span><small>{{ previewPageEstimate() }}</small></header>
            <div class="design-live-preview-stage">
              <ResumePaper :document="resume" :template-code="templateCode" :layout-style="layoutStyle" design />
            </div>
          </section>
          </div>
        </aside>
        <form v-if="!showDesign" id="resume-form" class="studio-editor" :class="[{ 'is-collapsed': editorPanelCollapsed, 'is-read-only': historicalSourceReadOnly }, `active-${activeSection}`]" :aria-label="historicalSourceReadOnly ? t('resumeEditor.atsReadOnlyFormLabel') : t('resumeEditor.editorFormLabel')" :aria-readonly="historicalSourceReadOnly" @submit.prevent="save">
        <fieldset class="editor-fields" :disabled="historicalSourceReadOnly">
        <header class="property-panel-heading"><div><span>{{ t('resumeEditor.contentProperties') }}</span><strong>{{ t('resumeEditor.editResumeContent') }}</strong><small>{{ t('resumeEditor.contentPanelDescription') }}</small></div><button class="property-toggle" type="button" :aria-expanded="!editorPanelCollapsed" :title="editorPanelCollapsed ? t('resumeEditor.expandEditor') : t('resumeEditor.collapseEditor')" @click="toggleEditorPanel"><PanelRightOpen v-if="editorPanelCollapsed" :size="16" /><PanelRightClose v-else :size="16" /></button></header>
        <div v-if="materialTypesBySection[activeSection]?.length" class="material-insert-bar">
          <button type="button" class="btn-neon btn-ghost" @click="loadMaterialLibrary">{{ materialLibraryLoading ? t('resumeEditor.loadingMaterials') : t('resumeEditor.loadMaterials') }}</button>
          <select v-if="materialLibrary.length" v-model="selectedMaterialId" :aria-label="t('resumeEditor.materialPickerLabel')">
            <option :value="null">{{ t('resumeEditor.selectMaterial') }}</option>
            <option v-for="item in materialCandidates" :key="item.id" :value="item.id">{{ item.title }} · {{ item.updatedAt.slice(0, 10) }}</option>
          </select>
          <button v-if="materialLibrary.length" type="button" class="btn-neon btn-primary" :disabled="!canInsertMaterial" @click="insertMaterial">{{ materialInsertLoading ? t('resumeEditor.insertingMaterial') : t('resumeEditor.insertMaterial') }}</button>
          <small v-if="materialLibrary.length && !materialCandidates.length">{{ t('resumeEditor.noMaterialsForSection') }}</small>
        </div>
        <div v-if="relatedSectionAssets.length" class="related-assets-bar">
          <span class="related-assets-title">{{ t('resumeEditor.relatedAssetsLabel') }}</span>
          <p v-for="asset in relatedSectionAssets" :key="asset.id" class="related-asset-item">
            <strong>{{ asset.questionText }}</strong>{{ asset.originalAnswerText }}
          </p>
        </div>
        <aside v-if="aiAssistant" class="ai-assistant-panel" aria-live="polite">
          <header><span class="ai-orb"><Sparkles :size="15" /></span><div><small>{{ aiAssistant.scope === 'field' ? t('resumeEditor.aiFieldPolish') : t('resumeEditor.aiSectionOptimize') }}</small><strong>{{ aiAssistant.label }}</strong></div><button type="button" :aria-label="t('resumeEditor.closeAiAssistant')" @click="closeAiAssistant"><X :size="16" /></button></header>
          <p v-if="aiAssistant.content">{{ t('resumeEditor.aiContentNotice') }}</p>
          <p v-else>{{ t('resumeEditor.aiEmptyNotice') }}</p>
          <div class="ai-guardrails"><span>{{ t('resumeEditor.aiKeepFacts') }}</span><span>{{ t('resumeEditor.aiOptionalJd') }}</span><span>{{ t('resumeEditor.aiManualApply') }}</span></div>
          <div v-if="aiAssistant.loading" class="ai-candidate-placeholder"><WandSparkles :size="16" /><div><strong>{{ t('resumeEditor.aiGenerating') }}</strong><small>{{ t('resumeEditor.aiNoNewFacts') }}</small></div></div>
          <p v-else-if="aiAssistant.error" class="ai-inline-error" role="alert">{{ aiAssistant.error }}</p>
          <div v-else-if="aiAssistant.result" class="ai-candidate-list">
            <article v-for="(c, ci) in aiAssistant.result.candidates" :key="ci">
              <span>{{ message('aiCandidate', { index: ci + 1 }) }}</span><p>{{ c.content }}</p><small>{{ c.suggestion }}</small>
              <button v-if="aiAssistant.apply" type="button" @click="applyAiCandidate(c.content)">{{ t('resumeEditor.aiApply') }}</button>
            </article>
            <div v-if="!aiAssistant.result.candidates.length" class="ai-candidate-placeholder">
              <WandSparkles :size="16" /><div><strong>{{ t('resumeEditor.aiNoSafeRewrite') }}</strong><small>{{ aiAssistant.result.emptyReason || t('resumeEditor.aiNoSafeRewriteHint') }}</small></div>
            </div>
          </div>
          <div v-else class="ai-candidate-placeholder"><WandSparkles :size="16" /><div><strong>{{ aiAssistant.content ? t('resumeEditor.aiWaiting') : t('resumeEditor.aiWaitingContent') }}</strong><small>{{ aiAssistant.content ? t('resumeEditor.aiConsentRetry') : t('resumeEditor.aiFillFirst') }}</small></div></div>
          <footer v-if="aiAssistant.needsConsent"><a class="ai-consent-action" :href="aiConsentHref">{{ t('resumeEditor.reauthorize') }}</a></footer>
        </aside>
        <!-- Basics -->
        <fieldset id="resume-basics" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('basics') }"><legend><span>{{ t('resumeEditor.basicsLabel') }}</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', t('resumeEditor.personalSummary'), 'summary', sectionAiContent('basics'), value => setBasic('summary', value))"><Sparkles :size="13" /> {{ t('resumeEditor.aiOptimize') }}</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('basics')" @click="toggleSection('basics')">{{ isSectionCollapsed('basics') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button></span></legend><div class="field-grid">
          <label>{{ t('resumeEditor.name') }}<input :value="basics.name ?? ''" :placeholder="t('resumeEditor.namePlaceholder')" @input="setBasic('name', ($event.target as HTMLInputElement).value)" /></label>
          <label>{{ t('resumeEditor.targetRole') }}<input :value="basics.title ?? basics.position ?? ''" :placeholder="t('resumeEditor.targetRolePlaceholder')" @input="setBasic('title', ($event.target as HTMLInputElement).value)" /></label>
          <label>{{ t('resumeEditor.email') }}<input :value="basics.email ?? ''" type="email" placeholder="name@example.com" @input="setBasic('email', ($event.target as HTMLInputElement).value)" /></label>
          <label>{{ t('resumeEditor.phone') }}<input :value="basics.phone ?? ''" placeholder="138 0000 0000" @input="setBasic('phone', ($event.target as HTMLInputElement).value)" /></label>
          <label class="span-two">{{ t('resumeEditor.location') }}<input :value="basics.location ?? ''" :placeholder="t('resumeEditor.locationPlaceholder')" @input="setBasic('location', ($event.target as HTMLInputElement).value)" /></label>
          <label class="span-two"><span class="field-label-row"><span>{{ t('resumeEditor.personalSummary') }}</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', t('resumeEditor.personalSummary'), 'summary', basics.summary, value => setBasic('summary', value))"><WandSparkles :size="13" /> {{ t('resumeEditor.polish') }}</button></span><textarea :value="basics.summary ?? ''" rows="4" :placeholder="t('resumeEditor.summaryPlaceholder')" @input="setBasic('summary', ($event.target as HTMLTextAreaElement).value)" /></label>
        </div></fieldset>
        <fieldset id="resume-objective" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('objective') }"><legend><span>{{ t('resumeEditor.objectiveLabel') }}</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('objective')" @click="toggleSection('objective')">{{ isSectionCollapsed('objective') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button></span></legend><div class="field-grid">
          <label>{{ t('resumeEditor.targetRole') }}<input :value="objective.targetRole ?? ''" @input="setObjective('targetRole', ($event.target as HTMLInputElement).value)" /></label><label>{{ t('resumeEditor.targetIndustry') }}<input :value="objective.targetIndustry ?? ''" @input="setObjective('targetIndustry', ($event.target as HTMLInputElement).value)" /></label><label class="span-two">{{ t('resumeEditor.locationPreference') }}<input :value="objective.location ?? ''" @input="setObjective('location', ($event.target as HTMLInputElement).value)" /></label><label class="span-two"><span class="field-label-row"><span>{{ t('resumeEditor.objectiveSummary') }}</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', t('resumeEditor.objectiveLabel'), 'objectiveSummary', objective.summary, value => setObjective('summary', value))"><WandSparkles :size="13" /> {{ t('resumeEditor.polish') }}</button></span><textarea :value="objective.summary ?? ''" rows="3" @input="setObjective('summary', ($event.target as HTMLTextAreaElement).value)" /></label>
        </div></fieldset>
        <fieldset id="resume-links" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('links') }"><legend><span>{{ t('resumeEditor.linksLabel') }}</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('links')" @click="toggleSection('links')">{{ isSectionCollapsed('links') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addLink">＋ {{ t('resumeEditor.addLink') }}</button></span></legend><p v-if="!links.length" class="editor-empty">{{ t('resumeEditor.linksEmpty') }}</p><div v-for="(item, index) in links" :key="index" class="field-grid compact-fields"><label>{{ t('resumeEditor.linkLabel') }}<input :value="item.label ?? ''" @input="setSectionItem('links', index, 'label', ($event.target as HTMLInputElement).value)" /></label><label>{{ t('resumeEditor.linkUrl') }}<input :value="item.url ?? ''" type="url" @input="setSectionItem('links', index, 'url', ($event.target as HTMLInputElement).value)" /></label><button type="button" class="btn-neon btn-ghost" @click="removeSectionItem('links', index, item.label || t('resumeEditor.linkLabel'))">{{ t('common.delete') }}</button></div></fieldset>
        <!-- Work -->
        <fieldset id="resume-work" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('work'), 'is-section-drag-before': isContentSectionTarget('work', false), 'is-section-drag-after': isContentSectionTarget('work', true) }" :style="contentSectionStyle('work')" @dragover.prevent="updateContentSectionTarget('work', $event)" @drop.prevent="dropContentSection('work')"><legend><button type="button" class="section-drag-handle" draggable="true" :title="t('resumeEditor.dragSection')" :aria-label="t('resumeEditor.dragSection')" @dragstart="startContentSectionDrag('work', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>{{ t('resumeEditor.workLabel') }}</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', t('resumeEditor.workLabel'), 'workDescription', sectionAiContent('work'))"><Sparkles :size="13" /> {{ t('resumeEditor.aiOptimize') }}</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('work')" @click="toggleSection('work')">{{ isSectionCollapsed('work') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addWork">＋ {{ t('resumeEditor.addWork') }}</button></span></legend>
          <p v-if="!work.length" class="editor-empty">{{ t('resumeEditor.workEmpty') }}</p>
          <article v-for="(item, index) in work" :key="index" class="work-editor" :class="{ 'is-drag-over-before': isDragTarget('work', index, false), 'is-drag-over-after': isDragTarget('work', index, true) }" @dragover.prevent="updateDragTarget('work', index, $event)" @drop.prevent="dropItem('work', index, $event)"><div class="work-editor-head"><strong>{{ message('workItem', { index: index + 1 }) }}</strong><div class="item-order-actions"><button type="button" class="drag-handle" draggable="true" :title="t('resumeEditor.dragSort')" :aria-label="t('resumeEditor.dragSort')" @dragstart="startItemDrag('work', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="item-move" :disabled="index === 0" :title="t('resumeEditor.moveUp')" @click="moveItem('work', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === work.length - 1" :title="t('resumeEditor.moveDown')" @click="moveItem('work', index, 1)">↓</button><button type="button" @click="removeWork(index)">{{ t('common.delete') }}</button></div></div><div class="field-grid">
            <label>{{ t('resumeEditor.company') }}<input :value="item.company ?? item.name ?? ''" @input="setWork(index, 'company', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.position') }}<input :value="item.position ?? item.role ?? ''" @input="setWork(index, 'position', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.startDate') }}<input :value="item.startDate ?? ''" placeholder="2022-03" @input="setWork(index, 'startDate', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.endDate') }}<input :value="item.endDate ?? ''" :placeholder="t('resumeEditor.current')" @input="setWork(index, 'endDate', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>{{ t('resumeEditor.responsibility') }}</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', message('workItem', { index: index + 1 }) + ' · ' + t('resumeEditor.responsibility'), 'workDescription', item.description, value => setWork(index, 'description', value))"><WandSparkles :size="13" /> {{ t('resumeEditor.polish') }}</button></span><textarea :value="item.description ?? ''" rows="3" @input="setWork(index, 'description', ($event.target as HTMLTextAreaElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>{{ t('resumeEditor.outcomes') }}</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', message('workItem', { index: index + 1 }) + ' · ' + t('resumeEditor.outcomes'), 'workHighlights', item.highlights, value => setWorkHighlights(index, value))"><WandSparkles :size="13" /> {{ t('resumeEditor.strengthenOutcomes') }}</button></span><textarea :value="Array.isArray(item.highlights) ? item.highlights.join('\n') : ''" rows="4" :placeholder="t('resumeEditor.outcomesPlaceholder')" @input="setWorkHighlights(index, ($event.target as HTMLTextAreaElement).value)" /></label>
          </div></article>
        </fieldset>
        <fieldset id="resume-volunteering" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('volunteering') }"><legend><span>{{ t('resumeEditor.volunteeringLabel') }}</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('volunteering')" @click="toggleSection('volunteering')">{{ isSectionCollapsed('volunteering') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addStructuredItem('volunteering')">＋ {{ t('resumeEditor.addVolunteering') }}</button></span></legend><p v-if="!volunteering.length" class="editor-empty">{{ t('resumeEditor.volunteeringEmpty') }}</p><article v-for="(item, index) in volunteering" :key="index" class="work-editor"><div class="work-editor-head"><strong>{{ t('resumeEditor.volunteeringLabel') }} {{ index + 1 }}</strong><button type="button" @click="removeSectionItem('volunteering', index, item.organization || t('resumeEditor.volunteeringLabel'))">{{ t('common.delete') }}</button></div><div class="field-grid"><label>{{ t('resumeEditor.organization') }}<input :value="item.organization ?? ''" @input="setSectionItem('volunteering', index, 'organization', ($event.target as HTMLInputElement).value)" /></label><label>{{ t('resumeEditor.role') }}<input :value="item.role ?? ''" @input="setSectionItem('volunteering', index, 'role', ($event.target as HTMLInputElement).value)" /></label><label>{{ t('resumeEditor.startDate') }}<input :value="item.startDate ?? ''" @input="setSectionItem('volunteering', index, 'startDate', ($event.target as HTMLInputElement).value)" /></label><label>{{ t('resumeEditor.endDate') }}<input :value="item.endDate ?? ''" @input="setSectionItem('volunteering', index, 'endDate', ($event.target as HTMLInputElement).value)" /></label><label class="span-two"><span class="field-label-row"><span>{{ t('resumeEditor.description') }}</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', t('resumeEditor.volunteeringLabel'), 'volunteeringDescription', item.description, value => setSectionItem('volunteering', index, 'description', value))"><WandSparkles :size="13" /> {{ t('resumeEditor.polish') }}</button></span><textarea :value="item.description ?? ''" rows="3" @input="setSectionItem('volunteering', index, 'description', ($event.target as HTMLTextAreaElement).value)" /></label></div></article></fieldset>
        <!-- Skills -->
        <fieldset id="resume-skills" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('skills'), 'is-section-drag-before': isContentSectionTarget('skills', false), 'is-section-drag-after': isContentSectionTarget('skills', true) }" :style="contentSectionStyle('skills')" @dragover.prevent="updateContentSectionTarget('skills', $event)" @drop.prevent="dropContentSection('skills')"><legend><button type="button" class="section-drag-handle" draggable="true" :title="t('resumeEditor.dragSection')" :aria-label="t('resumeEditor.dragSection')" @dragstart="startContentSectionDrag('skills', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>{{ t('resumeEditor.skillsLabel') }}</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', t('resumeEditor.skillsLabel'), 'skillDescription', sectionAiContent('skills'))"><Sparkles :size="13" /> {{ t('resumeEditor.aiOptimize') }}</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('skills')" @click="toggleSection('skills')">{{ isSectionCollapsed('skills') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addSkill">＋ {{ t('resumeEditor.addSkill') }}</button></span></legend>
          <p v-if="!skills.length" class="editor-empty">{{ t('resumeEditor.skillsEmpty') }}</p>
          <div v-for="(skill, index) in skills" :key="index" class="inline-editor" :class="{ 'is-drag-over-before': isDragTarget('skills', index, false), 'is-drag-over-after': isDragTarget('skills', index, true) }" @dragover.prevent="updateDragTarget('skills', index, $event)" @drop.prevent="dropItem('skills', index, $event)"><input :value="typeof skill === 'string' ? skill : skill.name ?? skill.keyword ?? ''" :placeholder="t('resumeEditor.skillPlaceholder')" @input="setSkill(index, ($event.target as HTMLInputElement).value)" /><div class="inline-editor-actions"><button type="button" class="drag-handle" draggable="true" :title="t('resumeEditor.dragSort')" :aria-label="t('resumeEditor.dragSort')" @dragstart="startItemDrag('skills', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="ai-inline-action" :title="t('resumeEditor.aiFieldPolish')" @click="openAiAssistant('field', message('skillItem', { index: index + 1 }), 'skillDescription', typeof skill === 'string' ? skill : skill.name ?? skill.keyword, value => setSkill(index, value))"><WandSparkles :size="13" /></button><button type="button" class="item-move" :disabled="index === 0" :title="t('resumeEditor.moveUp')" @click="moveItem('skills', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === skills.length - 1" :title="t('resumeEditor.moveDown')" @click="moveItem('skills', index, 1)">↓</button><button type="button" @click="removeSkill(index)">{{ t('common.delete') }}</button></div></div>
        </fieldset>
        <!-- Projects -->
        <fieldset id="resume-projects" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('projects'), 'is-section-drag-before': isContentSectionTarget('projects', false), 'is-section-drag-after': isContentSectionTarget('projects', true) }" :style="contentSectionStyle('projects')" @dragover.prevent="updateContentSectionTarget('projects', $event)" @drop.prevent="dropContentSection('projects')"><legend><button type="button" class="section-drag-handle" draggable="true" :title="t('resumeEditor.dragSection')" :aria-label="t('resumeEditor.dragSection')" @dragstart="startContentSectionDrag('projects', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>{{ t('resumeEditor.projectsLabel') }}</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', t('resumeEditor.projectsLabel'), 'projectDescription', sectionAiContent('projects'))"><Sparkles :size="13" /> {{ t('resumeEditor.aiOptimize') }}</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('projects')" @click="toggleSection('projects')">{{ isSectionCollapsed('projects') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addProject">＋ {{ t('resumeEditor.addProject') }}</button></span></legend>
          <p v-if="!projects.length" class="editor-empty">{{ t('resumeEditor.projectsEmpty') }}</p>
          <article v-for="(item, index) in projects" :key="index" class="work-editor" :class="{ 'is-drag-over-before': isDragTarget('projects', index, false), 'is-drag-over-after': isDragTarget('projects', index, true) }" @dragover.prevent="updateDragTarget('projects', index, $event)" @drop.prevent="dropItem('projects', index, $event)"><div class="work-editor-head"><strong>{{ message('projectItem', { index: index + 1 }) }}</strong><div class="item-order-actions"><button type="button" class="drag-handle" draggable="true" :title="t('resumeEditor.dragSort')" :aria-label="t('resumeEditor.dragSort')" @dragstart="startItemDrag('projects', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="item-move" :disabled="index === 0" :title="t('resumeEditor.moveUp')" @click="moveItem('projects', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === projects.length - 1" :title="t('resumeEditor.moveDown')" @click="moveItem('projects', index, 1)">↓</button><button type="button" @click="removeProject(index)">{{ t('common.delete') }}</button></div></div><div class="field-grid">
            <label>{{ t('resumeEditor.projectName') }}<input :value="item.name ?? ''" @input="setProject(index, 'name', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.projectRole') }}<input :value="item.role ?? item.position ?? ''" @input="setProject(index, 'role', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.startDate') }}<input :value="item.startDate ?? ''" placeholder="2023-01" @input="setProject(index, 'startDate', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.endDate') }}<input :value="item.endDate ?? ''" :placeholder="t('resumeEditor.current')" @input="setProject(index, 'endDate', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>{{ t('resumeEditor.projectDescription') }}</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', message('projectItem', { index: index + 1 }) + ' · ' + t('resumeEditor.projectDescription'), 'projectDescription', item.description, value => setProject(index, 'description', value))"><WandSparkles :size="13" /> {{ t('resumeEditor.polish') }}</button></span><textarea :value="item.description ?? ''" rows="3" @input="setProject(index, 'description', ($event.target as HTMLTextAreaElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>{{ t('resumeEditor.projectOutcomes') }}</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', message('projectItem', { index: index + 1 }) + ' · ' + t('resumeEditor.projectOutcomes'), 'projectHighlights', item.highlights, value => setProject(index, 'highlights', value.split('\n').map(i => i.trim()).filter(Boolean)))"><WandSparkles :size="13" /> {{ t('resumeEditor.strengthenOutcomes') }}</button></span><textarea :value="Array.isArray(item.highlights) ? item.highlights.join('\n') : ''" rows="4" :placeholder="t('resumeEditor.projectOutcomesPlaceholder')" @input="setProject(index, 'highlights', ($event.target as HTMLTextAreaElement).value.split('\n').map(v => v.trim()).filter(Boolean))" /></label>
          </div></article>
        </fieldset>
        <!-- Education -->
        <fieldset id="resume-education" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('education'), 'is-section-drag-before': isContentSectionTarget('education', false), 'is-section-drag-after': isContentSectionTarget('education', true) }" :style="contentSectionStyle('education')" @dragover.prevent="updateContentSectionTarget('education', $event)" @drop.prevent="dropContentSection('education')"><legend><button type="button" class="section-drag-handle" draggable="true" :title="t('resumeEditor.dragSection')" :aria-label="t('resumeEditor.dragSection')" @dragstart="startContentSectionDrag('education', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>{{ t('resumeEditor.educationLabel') }}</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('education')" @click="toggleSection('education')">{{ isSectionCollapsed('education') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addEducation">＋ {{ t('resumeEditor.addEducation') }}</button></span></legend>
          <p v-if="!education.length" class="editor-empty">{{ t('resumeEditor.educationEmpty') }}</p>
          <article v-for="(item, index) in education" :key="index" class="work-editor" :class="{ 'is-drag-over-before': isDragTarget('education', index, false), 'is-drag-over-after': isDragTarget('education', index, true) }" @dragover.prevent="updateDragTarget('education', index, $event)" @drop.prevent="dropItem('education', index, $event)"><div class="work-editor-head"><strong>{{ message('educationItem', { index: index + 1 }) }}</strong><div class="item-order-actions"><button type="button" class="drag-handle" draggable="true" :title="t('resumeEditor.dragSort')" :aria-label="t('resumeEditor.dragSort')" @dragstart="startItemDrag('education', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="item-move" :disabled="index === 0" :title="t('resumeEditor.moveUp')" @click="moveItem('education', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === education.length - 1" :title="t('resumeEditor.moveDown')" @click="moveItem('education', index, 1)">↓</button><button type="button" @click="removeEducation(index)">{{ t('common.delete') }}</button></div></div><div class="field-grid">
            <label>{{ t('resumeEditor.school') }}<input :value="item.school ?? item.name ?? ''" @input="setEducation(index, 'school', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.degree') }}<input :value="item.degree ?? ''" :placeholder="t('resumeEditor.degreePlaceholder')" @input="setEducation(index, 'degree', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two">{{ t('resumeEditor.major') }}<input :value="item.major ?? item.area ?? ''" @input="setEducation(index, 'major', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.startDate') }}<input :value="item.startDate ?? ''" placeholder="2018-09" @input="setEducation(index, 'startDate', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.endDate') }}<input :value="item.endDate ?? ''" placeholder="2022-06" @input="setEducation(index, 'endDate', ($event.target as HTMLInputElement).value)" /></label>
          </div></article>
        </fieldset>
        <fieldset id="resume-courses" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('courses') }"><legend><span>{{ t('resumeEditor.coursesLabel') }}</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('courses')" @click="toggleSection('courses')">{{ isSectionCollapsed('courses') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addStructuredItem('courses')">＋ {{ t('resumeEditor.addCourse') }}</button></span></legend><p v-if="!courses.length" class="editor-empty">{{ t('resumeEditor.coursesEmpty') }}</p><article v-for="(item, index) in courses" :key="index" class="work-editor"><div class="work-editor-head"><strong>{{ t('resumeEditor.coursesLabel') }} {{ index + 1 }}</strong><button type="button" @click="removeSectionItem('courses', index, item.name || t('resumeEditor.coursesLabel'))">{{ t('common.delete') }}</button></div><div class="field-grid"><label>{{ t('resumeEditor.courseName') }}<input :value="item.name ?? ''" @input="setSectionItem('courses', index, 'name', ($event.target as HTMLInputElement).value)" /></label><label>{{ t('resumeEditor.provider') }}<input :value="item.provider ?? ''" @input="setSectionItem('courses', index, 'provider', ($event.target as HTMLInputElement).value)" /></label><label>{{ t('resumeEditor.date') }}<input :value="item.date ?? ''" @input="setSectionItem('courses', index, 'date', ($event.target as HTMLInputElement).value)" /></label><label class="span-two"><span class="field-label-row"><span>{{ t('resumeEditor.description') }}</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', t('resumeEditor.coursesLabel'), 'courseDescription', item.description, value => setSectionItem('courses', index, 'description', value))"><WandSparkles :size="13" /> {{ t('resumeEditor.polish') }}</button></span><textarea :value="item.description ?? ''" rows="3" @input="setSectionItem('courses', index, 'description', ($event.target as HTMLTextAreaElement).value)" /></label></div></article></fieldset>
        <!-- Certificates -->
        <fieldset id="resume-certificates" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('certificates'), 'is-section-drag-before': isContentSectionTarget('certificates', false), 'is-section-drag-after': isContentSectionTarget('certificates', true) }" :style="contentSectionStyle('certificates')" @dragover.prevent="updateContentSectionTarget('certificates', $event)" @drop.prevent="dropContentSection('certificates')"><legend><button type="button" class="section-drag-handle" draggable="true" :title="t('resumeEditor.dragSection')" :aria-label="t('resumeEditor.dragSection')" @dragstart="startContentSectionDrag('certificates', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>{{ t('resumeEditor.certificatesLabel') }}</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('certificates')" @click="toggleSection('certificates')">{{ isSectionCollapsed('certificates') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addSimpleItem('certificates')">＋ {{ t('resumeEditor.addCertificate') }}</button></span></legend>
          <p v-if="!certificates.length" class="editor-empty">{{ t('resumeEditor.certificatesEmpty') }}</p>
          <article v-for="(item, index) in certificates" :key="index" class="work-editor" :class="{ 'is-drag-over-before': isDragTarget('certificates', index, false), 'is-drag-over-after': isDragTarget('certificates', index, true) }" @dragover.prevent="updateDragTarget('certificates', index, $event)" @drop.prevent="dropItem('certificates', index, $event)"><div class="work-editor-head"><strong>{{ message('certificateItem', { index: index + 1 }) }}</strong><div class="item-order-actions"><button type="button" class="drag-handle" draggable="true" :title="t('resumeEditor.dragSort')" :aria-label="t('resumeEditor.dragSort')" @dragstart="startItemDrag('certificates', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="item-move" :disabled="index === 0" :title="t('resumeEditor.moveUp')" @click="moveItem('certificates', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === certificates.length - 1" :title="t('resumeEditor.moveDown')" @click="moveItem('certificates', index, 1)">↓</button><button type="button" @click="removeSimpleItem('certificates', index)">{{ t('common.delete') }}</button></div></div><div class="field-grid">
            <label>{{ t('resumeEditor.certificateName') }}<input :value="item.name ?? ''" :placeholder="t('resumeEditor.certificateNamePlaceholder')" @input="setSimpleItem('certificates', index, 'name', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.issuer') }}<input :value="item.issuer ?? ''" placeholder="Amazon Web Services" @input="setSimpleItem('certificates', index, 'issuer', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two">{{ t('resumeEditor.dateAwarded') }}<input :value="item.date ?? ''" placeholder="2024-06" @input="setSimpleItem('certificates', index, 'date', ($event.target as HTMLInputElement).value)" /></label>
          </div></article>
        </fieldset>
        <fieldset id="resume-publications" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('publications') }"><legend><span>{{ t('resumeEditor.publicationsLabel') }}</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('publications')" @click="toggleSection('publications')">{{ isSectionCollapsed('publications') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addStructuredItem('publications')">＋ {{ t('resumeEditor.addPublication') }}</button></span></legend><p v-if="!publications.length" class="editor-empty">{{ t('resumeEditor.publicationsEmpty') }}</p><article v-for="(item, index) in publications" :key="index" class="work-editor"><div class="work-editor-head"><strong>{{ t('resumeEditor.publicationsLabel') }} {{ index + 1 }}</strong><button type="button" @click="removeSectionItem('publications', index, item.title || t('resumeEditor.publicationsLabel'))">{{ t('common.delete') }}</button></div><div class="field-grid"><label>{{ t('resumeEditor.publicationTitle') }}<input :value="item.title ?? ''" @input="setSectionItem('publications', index, 'title', ($event.target as HTMLInputElement).value)" /></label><label>{{ t('resumeEditor.publisher') }}<input :value="item.publisher ?? ''" @input="setSectionItem('publications', index, 'publisher', ($event.target as HTMLInputElement).value)" /></label><label>{{ t('resumeEditor.date') }}<input :value="item.date ?? ''" @input="setSectionItem('publications', index, 'date', ($event.target as HTMLInputElement).value)" /></label><label>{{ t('resumeEditor.linkUrl') }}<input :value="item.url ?? ''" type="url" @input="setSectionItem('publications', index, 'url', ($event.target as HTMLInputElement).value)" /></label><label class="span-two"><span class="field-label-row"><span>{{ t('resumeEditor.description') }}</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', t('resumeEditor.publicationsLabel'), 'publicationDescription', item.description, value => setSectionItem('publications', index, 'description', value))"><WandSparkles :size="13" /> {{ t('resumeEditor.polish') }}</button></span><textarea :value="item.description ?? ''" rows="3" @input="setSectionItem('publications', index, 'description', ($event.target as HTMLTextAreaElement).value)" /></label></div></article></fieldset>
        <!-- Awards -->
        <fieldset id="resume-awards" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('awards'), 'is-section-drag-before': isContentSectionTarget('awards', false), 'is-section-drag-after': isContentSectionTarget('awards', true) }" :style="contentSectionStyle('awards')" @dragover.prevent="updateContentSectionTarget('awards', $event)" @drop.prevent="dropContentSection('awards')"><legend><button type="button" class="section-drag-handle" draggable="true" :title="t('resumeEditor.dragSection')" :aria-label="t('resumeEditor.dragSection')" @dragstart="startContentSectionDrag('awards', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>{{ t('resumeEditor.awardsLabel') }}</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('awards')" @click="toggleSection('awards')">{{ isSectionCollapsed('awards') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addSimpleItem('awards')">＋ {{ t('resumeEditor.addAward') }}</button></span></legend>
          <p v-if="!awards.length" class="editor-empty">{{ t('resumeEditor.awardsEmpty') }}</p>
          <article v-for="(item, index) in awards" :key="index" class="work-editor" :class="{ 'is-drag-over-before': isDragTarget('awards', index, false), 'is-drag-over-after': isDragTarget('awards', index, true) }" @dragover.prevent="updateDragTarget('awards', index, $event)" @drop.prevent="dropItem('awards', index, $event)"><div class="work-editor-head"><strong>{{ message('awardItem', { index: index + 1 }) }}</strong><div class="item-order-actions"><button type="button" class="drag-handle" draggable="true" :title="t('resumeEditor.dragSort')" :aria-label="t('resumeEditor.dragSort')" @dragstart="startItemDrag('awards', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="item-move" :disabled="index === 0" :title="t('resumeEditor.moveUp')" @click="moveItem('awards', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === awards.length - 1" :title="t('resumeEditor.moveDown')" @click="moveItem('awards', index, 1)">↓</button><button type="button" @click="removeSimpleItem('awards', index)">{{ t('common.delete') }}</button></div></div><div class="field-grid">
            <label>{{ t('resumeEditor.awardName') }}<input :value="item.name ?? item.title ?? ''" :placeholder="t('resumeEditor.awardNamePlaceholder')" @input="setSimpleItem('awards', index, 'name', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.awardIssuer') }}<input :value="item.issuer ?? item.organization ?? ''" @input="setSimpleItem('awards', index, 'issuer', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.dateAwarded') }}<input :value="item.date ?? ''" placeholder="2024-06" @input="setSimpleItem('awards', index, 'date', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two">{{ t('resumeEditor.awardDescription') }}<textarea :value="item.description ?? ''" rows="3" :placeholder="t('resumeEditor.awardDescriptionPlaceholder')" @input="setSimpleItem('awards', index, 'description', ($event.target as HTMLTextAreaElement).value)" /></label>
          </div></article>
        </fieldset>
        <!-- Languages -->
        <fieldset id="resume-languages" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('languages'), 'is-section-drag-before': isContentSectionTarget('languages', false), 'is-section-drag-after': isContentSectionTarget('languages', true) }" :style="contentSectionStyle('languages')" @dragover.prevent="updateContentSectionTarget('languages', $event)" @drop.prevent="dropContentSection('languages')"><legend><button type="button" class="section-drag-handle" draggable="true" :title="t('resumeEditor.dragSection')" :aria-label="t('resumeEditor.dragSection')" @dragstart="startContentSectionDrag('languages', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>{{ t('resumeEditor.languagesLabel') }}</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('languages')" @click="toggleSection('languages')">{{ isSectionCollapsed('languages') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addSimpleItem('languages')">＋ {{ t('resumeEditor.addLanguage') }}</button></span></legend>
          <p v-if="!languages.length" class="editor-empty">{{ t('resumeEditor.languagesEmpty') }}</p>
          <article v-for="(item, index) in languages" :key="index" class="work-editor" :class="{ 'is-drag-over-before': isDragTarget('languages', index, false), 'is-drag-over-after': isDragTarget('languages', index, true) }" @dragover.prevent="updateDragTarget('languages', index, $event)" @drop.prevent="dropItem('languages', index, $event)"><div class="work-editor-head"><strong>{{ message('languageItem', { index: index + 1 }) }}</strong><div class="item-order-actions"><button type="button" class="drag-handle" draggable="true" :title="t('resumeEditor.dragSort')" :aria-label="t('resumeEditor.dragSort')" @dragstart="startItemDrag('languages', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="item-move" :disabled="index === 0" :title="t('resumeEditor.moveUp')" @click="moveItem('languages', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === languages.length - 1" :title="t('resumeEditor.moveDown')" @click="moveItem('languages', index, 1)">↓</button><button type="button" @click="removeSimpleItem('languages', index)">{{ t('common.delete') }}</button></div></div><div class="field-grid">
            <label>{{ t('resumeEditor.language') }}<input :value="item.name ?? item.language ?? ''" :placeholder="t('resumeEditor.languagePlaceholder')" @input="setSimpleItem('languages', index, 'name', ($event.target as HTMLInputElement).value)" /></label>
            <label>{{ t('resumeEditor.proficiency') }}<input :value="item.level ?? item.fluency ?? ''" :placeholder="t('resumeEditor.proficiencyPlaceholder')" @input="setSimpleItem('languages', index, 'level', ($event.target as HTMLInputElement).value)" /></label>
          </div></article>
        </fieldset>
        <fieldset id="resume-customSections" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('customSections') }"><legend><span>{{ t('resumeEditor.customSectionsLabel') }}</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('customSections')" @click="toggleSection('customSections')">{{ isSectionCollapsed('customSections') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button><button type="button" class="section-add" @click="addCustomSection">＋ {{ t('resumeEditor.addCustomSection') }}</button></span></legend><p v-if="!customSections.length" class="editor-empty">{{ t('resumeEditor.customSectionsEmpty') }}</p><article v-for="(section, sectionIndex) in customSections" :key="sectionIndex" class="work-editor"><div class="work-editor-head"><input :value="section.title ?? ''" :placeholder="t('resumeEditor.customSectionTitle')" @input="setCustomSection(sectionIndex, 'title', ($event.target as HTMLInputElement).value)" /><button type="button" @click="removeSectionItem('customSections', sectionIndex, section.title || t('resumeEditor.customSectionsLabel'))">{{ t('common.delete') }}</button></div><article v-for="(item, entryIndex) in (section.entries ?? [])" :key="entryIndex" class="inline-editor custom-entry"><div class="field-grid"><label>{{ t('resumeEditor.entryName') }}<input :value="item.name ?? ''" @input="setCustomEntry(sectionIndex, entryIndex, 'name', ($event.target as HTMLInputElement).value)" /></label><label>{{ t('resumeEditor.organization') }}<input :value="item.organization ?? ''" @input="setCustomEntry(sectionIndex, entryIndex, 'organization', ($event.target as HTMLInputElement).value)" /></label><label class="span-two"><span class="field-label-row"><span>{{ t('resumeEditor.description') }}</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', section.title || t('resumeEditor.customSectionsLabel'), 'customSectionDescription', item.description, value => setCustomEntry(sectionIndex, entryIndex, 'description', value))"><WandSparkles :size="13" /> {{ t('resumeEditor.polish') }}</button></span><textarea :value="item.description ?? ''" rows="2" @input="setCustomEntry(sectionIndex, entryIndex, 'description', ($event.target as HTMLTextAreaElement).value)" /></label></div></article><button type="button" class="btn-neon btn-ghost" @click="addCustomEntry(sectionIndex)">{{ t('resumeEditor.addCustomEntry') }}</button></article></fieldset>
        <div class="editor-note"><strong>{{ t('resumeEditor.advancedEditing') }}</strong><span>{{ t('resumeEditor.advancedEditingDescription') }}</span></div>
        <label v-if="showSource" class="source-editor open"><span>{{ t('resumeEditor.resumeSource') }}</span><textarea v-model="content" :rows="28" required spellcheck="false" /><small :class="sourceValid ? 'source-ok' : 'source-invalid'">{{ sourceValid ? t('resumeEditor.jsonValid') : t('resumeEditor.jsonInvalidSave') }}</small></label>
        <div v-if="undoRemoval" class="editor-undo" role="status"><span>{{ message('undoRemoved', { label: undoRemoval.label }) }}</span><button type="button" @click="restoreRemoval">{{ t('resumeEditor.undo') }}</button></div>
        <div class="editor-save-dock"><span><strong>{{ historicalSourceReadOnly ? t('resumeEditor.atsReadOnlyDock') : dirty ? t('resumeEditor.unsavedChanges') : t('resumeEditor.contentSynced') }}</strong><small>{{ historicalSourceReadOnly ? t('resumeEditor.atsHistoricalShort') : t('resumeEditor.shortcutSave') }}</small></span><div v-if="!historicalSourceReadOnly" class="editor-save-actions"><button v-if="activeSection !== 'languages'" class="btn-neon btn-ghost" type="button" @click="nextSection">{{ t('resumeEditor.nextSection') }}</button><button class="btn-neon btn-primary" :disabled="saving || !sourceValid || !dirty">{{ saving ? t('common.saving') : t('resumeEditor.saveNewVersion') }}</button></div></div>
        </fieldset>
        </form>
      </div>
    </div>
    <section v-else class="resume-preview-workspace" :aria-label="t('resumeEditor.previewWorkspaceLabel')">
      <header class="resume-preview-toolbar">
        <div><p class="eyebrow">{{ t('resumeEditor.previewMode') }}</p><h1>{{ t('resumeEditor.previewWorkspaceLabel') }}</h1></div>
        <div class="preview-toolbar-actions">
          <button ref="previewBackButtonRef" class="btn-neon btn-ghost" type="button" @click="closePreview"><ArrowLeft :size="16" /> {{ t('resumeEditor.returnToEditing') }}</button>
          <button v-if="!historicalSourceReadOnly" class="btn-neon btn-ghost" type="button" @click="openTemplatePicker">{{ t('resumeEditor.chooseTemplate') }}</button>
          <button v-if="!historicalSourceReadOnly" class="btn-neon btn-primary" type="button" :disabled="saving || !sourceValid || !dirty" @click="save">{{ saving ? t('common.saving') : dirty ? t('resumeEditor.saveNewVersion') : t('resumeEditor.contentSynced') }}</button>
        </div>
      </header>
      <div class="preview-page-meta"><span>{{ templateName }}</span><span :class="{ 'preview-warning': previewPageCount > 1 }">{{ previewPageEstimate() }}</span></div>
      <div class="preview-document-stage">
      <aside class="preview-rail">
        <div class="preview-scroll">
        <ResumePaper ref="previewPaperRef" :document="resume" :template-code="templateCode" :layout-style="layoutStyle" show-empty-guide />
        </div>
      </aside>
      </div>
    </section>
  </section>
</template>

<style scoped>
.related-assets-bar { display: grid; gap: 7px; margin: 2px 0 10px; padding: 10px 12px; border: 1px solid var(--border-soft); border-left: 3px solid var(--info); border-radius: 6px; background: var(--bg-page); }
.related-assets-title { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; }
.related-asset-item { display: grid; gap: 2px; margin: 0; color: var(--text-secondary); font-size: 10px; line-height: 1.5; }
.related-asset-item strong { overflow: hidden; color: var(--text-primary); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
</style>
