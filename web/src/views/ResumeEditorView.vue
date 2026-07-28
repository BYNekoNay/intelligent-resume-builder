<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRouter } from 'vue-router'
import { ArrowLeft, BookOpen, GripVertical, PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen, Sparkles, WandSparkles, X } from 'lucide-vue-next'
import type { AxiosError } from 'axios'
import { createManualVersion, getResume, getResumeVersion, listVersions } from '@/api/resume'
import { getMaterial, listMaterials, type CareerMaterial, type CareerMaterialSummary, type MaterialType } from '@/api/careerMaterial'
import { inlineOptimize, waitForAiTaskResult, type InlineOptimizeResponse } from '@/api/ai'
import { useLocale } from '@/i18n'
import { useAuthStore } from '@/stores/auth'
import sampleResume from '@/data/sampleResume'
import ResumeEditorNavigation, { type ResumeEditorSection } from '@/components/resume/ResumeEditorNavigation.vue'
import ResumePaper from '@/components/resume/ResumePaper.vue'
import { useResumeEditorDraft } from '@/composables/useResumeEditorDraft'
import { SECTION_KEYS, DEFAULT_SECTION_ORDER, resolveSectionOrder, type SectionKey, type ContentSectionKey } from '@/resume/sectionRegistry'

const { t } = useLocale()
function message(key: string, values: Record<string, string | number> = {}) {
  return Object.entries(values).reduce((text, [name, value]) => text.replace(`{${name}}`, String(value)), t(`resumeEditor.${key}`))
}
const props = defineProps<{ id: string }>()
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
const sectionKeys = SECTION_KEYS
type SortableSection = ContentSectionKey
const activeSection = ref<SectionKey>('basics')
type DragLocation = { section: SortableSection; index: number; after: boolean }
const collapsedSections = ref<Set<SectionKey>>(new Set(sectionKeys.filter((section) => section !== 'basics')))
const draggedItem = ref<{ section: SortableSection; index: number } | null>(null)
const dragTarget = ref<DragLocation | null>(null)
const draggedContentSection = ref<SortableSection | null>(null)
const contentSectionDropTarget = ref<{ section: SortableSection; after: boolean } | null>(null)
type AiAssistantState = {
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
  const targetResumeId = props.id
  materialLibraryLoading.value = true
  error.value = ''
  try {
    const materials = (await listMaterials()).data.data
    if (props.id === targetResumeId) materialLibrary.value = materials
  } catch {
    if (props.id === targetResumeId) error.value = t('resumeEditor.materialLibraryLoadFailed')
  } finally {
    if (props.id === targetResumeId) materialLibraryLoading.value = false
  }
}
function textFromMaterial(material: CareerMaterial, keys: string[]) {
  const content = material.contentJson ?? {}
  for (const key of keys) if (typeof content[key] === 'string' && content[key].trim()) return content[key] as string
  return material.sourceText?.trim() || ''
}
async function insertMaterial() {
  const id = selectedMaterialId.value
  if (!id || materialInsertLoading.value) return
  const targetSection = activeSection.value
  const targetResumeId = props.id
  materialInsertLoading.value = true
  error.value = ''
  try {
    const { data } = await getMaterial(id)
    if (props.id !== targetResumeId) return
    const material = data.data
    const outcome = textFromMaterial(material, ['outcome', 'result', 'outcomeEvidence'])
    const description = textFromMaterial(material, ['description', 'summary', 'applicationDescription', 'responsibilityScope'])
    if (targetSection === 'work') updateResume((d) => { d.work = [...(Array.isArray(d.work) ? d.work : []), { company: textFromMaterial(material, ['company', 'organization']) || material.title, position: textFromMaterial(material, ['position', 'role']), description, highlights: outcome ? [outcome] : [] }] })
    if (targetSection === 'projects') updateResume((d) => { d.projects = [...(Array.isArray(d.projects) ? d.projects : []), { name: textFromMaterial(material, ['name']) || material.title, role: textFromMaterial(material, ['role', 'position']), description, highlights: outcome ? [outcome] : [] }] })
    if (targetSection === 'skills') updateResume((d) => { d.skills = [...(Array.isArray(d.skills) ? d.skills : []), { name: textFromMaterial(material, ['skillName', 'name']) || material.title }] })
    if (targetSection === 'education') updateResume((d) => { d.education = [...(Array.isArray(d.education) ? d.education : []), { school: textFromMaterial(material, ['school', 'institution']) || material.title, degree: textFromMaterial(material, ['degree']), major: textFromMaterial(material, ['major', 'area']) }] })
    if (targetSection === 'volunteering') updateResume((d) => { d.volunteering = [...(Array.isArray(d.volunteering) ? d.volunteering : []), { organization: textFromMaterial(material, ['organization', 'company']) || material.title, role: textFromMaterial(material, ['role', 'position']), description, highlights: outcome ? [outcome] : [] }] })
    if (targetSection === 'courses') updateResume((d) => { d.courses = [...(Array.isArray(d.courses) ? d.courses : []), { name: textFromMaterial(material, ['name', 'courseName']) || material.title, provider: textFromMaterial(material, ['provider', 'institution']), date: textFromMaterial(material, ['date']), description }] })
    if (targetSection === 'certificates') updateResume((d) => { d.certificates = [...(Array.isArray(d.certificates) ? d.certificates : []), { name: textFromMaterial(material, ['name', 'title']) || material.title, issuer: textFromMaterial(material, ['issuer', 'organization']), date: textFromMaterial(material, ['date']) }] })
    if (targetSection === 'publications') updateResume((d) => { d.publications = [...(Array.isArray(d.publications) ? d.publications : []), { title: textFromMaterial(material, ['title', 'name']) || material.title, publisher: textFromMaterial(material, ['publisher', 'issuer']), date: textFromMaterial(material, ['date']), url: textFromMaterial(material, ['url', 'link']), description }] })
    if (targetSection === 'awards') updateResume((d) => { d.awards = [...(Array.isArray(d.awards) ? d.awards : []), { name: textFromMaterial(material, ['name', 'title']) || material.title, issuer: textFromMaterial(material, ['issuer', 'organization']), date: textFromMaterial(material, ['date']), description }] })
    if (targetSection === 'customSections') updateResume((d) => { d.customSections = [...(Array.isArray(d.customSections) ? d.customSections : []), { title: material.title, entries: [{ name: material.title, organization: textFromMaterial(material, ['organization', 'company']), role: textFromMaterial(material, ['role', 'position']), description, highlights: outcome ? [outcome] : [] }] }] })
    selectedMaterialId.value = null
  } catch {
    if (props.id === targetResumeId) error.value = t('resumeEditor.materialInsertFailed')
  } finally {
    if (props.id === targetResumeId) materialInsertLoading.value = false
  }
}

function isSectionCollapsed(section: SectionKey) { return collapsedSections.value.has(section) }
function toggleSection(section: SectionKey) {
  if (isSectionCollapsed(section)) { collapsedSections.value = new Set(sectionKeys.filter((k) => k !== section)); return }
  collapsedSections.value = new Set(sectionKeys)
}
function expandSection(section: SectionKey) { collapsedSections.value = new Set(sectionKeys.filter((k) => k !== section)) }
async function jumpToSection(section: SectionKey) {
  expandSection(section); await nextTick()
  document.getElementById(`resume-${section}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function openAiAssistant(scope: 'field' | 'section', label: string, section: string, value: unknown, apply?: (value: string) => void) {
  const contentValue = Array.isArray(value) ? value.filter(Boolean).join('\n') : String(value ?? '').trim()
  aiAssistant.value = { scope, label, section, content: contentValue, loading: false, result: null, error: '', needsConsent: false, apply }
  if (!contentValue || !currentVersionId.value) return
  aiAssistant.value.loading = true
  try {
    const createdTask = (await inlineOptimize({ resumeVersionId: currentVersionId.value, section, content: contentValue })).data.data
    const result = await waitForAiTaskResult<InlineOptimizeResponse>(createdTask.id)
    if (aiAssistant.value?.section === section && aiAssistant.value.content === contentValue) aiAssistant.value.result = result
  } catch (requestError: any) {
    if (aiAssistant.value?.section === section) {
      aiAssistant.value.needsConsent = requestError?.response?.data?.code === 40302
      aiAssistant.value.error = aiAssistant.value.needsConsent
        ? t('resumeEditor.consentRequired')
        : t('resumeEditor.aiUnavailable')
    }
  } finally { if (aiAssistant.value?.section === section) aiAssistant.value.loading = false }
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
  if (dirty.value && sourceValid.value && !saving.value) void save()
}
function goBack() { router.back() }

onBeforeRouteLeave(() => {
  if (!dirty.value) return true
  return window.confirm(t('resumeEditor.leaveConfirm'))
})
onBeforeRouteUpdate((to, from) => {
  if (to.params.id === from.params.id || !dirty.value) return true
  return window.confirm(t('resumeEditor.leaveConfirm'))
})

function updateResume(mutator: (draft: Record<string, any>) => void) {
  if (!sourceValid.value) { showSource.value = true; error.value = t('resumeEditor.sourceInvalidEdit'); return }
  const draft = JSON.parse(JSON.stringify(resume.value || {})) as Record<string, any>
  mutator(draft)
  content.value = JSON.stringify(draft, null, 2)
  error.value = ''
}

function removeWithUndo(label: string, mutator: (draft: Record<string, any>) => void) {
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
function addProject() { expandSection('projects'); updateResume((d) => { d.projects = [...(Array.isArray(d.projects) ? d.projects : []), { name: '', role: '', description: '', highlights: [] }] }) }
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
function removeSimpleItem(section: 'certificates' | 'awards' | 'languages', index: number) { const col = section === 'certificates' ? certificates.value : section === 'awards' ? awards.value : languages.value; const key = section === 'certificates' ? 'certificateItem' : section === 'awards' ? 'awardItem' : 'languageItem'; const label = col[index]?.name || message(key, { index: index + 1 }); removeSectionItem(section, index, label) }
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
  const loadSequence = ++editorLoadSequence
  const requestedResumeId = Number(props.id)
  loading.value = true
  loadFailed.value = false
  error.value = ''
  currentVersionId.value = null
  try {
    const resumeResponse = await getResume(requestedResumeId)
    let loadedVersionId = resumeResponse.data.data.currentVersionId
    if (loadedVersionId == null) {
      const versionsResponse = await listVersions(requestedResumeId)
      loadedVersionId = versionsResponse.data.data[0]?.id ?? null
    }
    const currentVersion = loadedVersionId
      ? (await getResumeVersion(loadedVersionId)).data.data
      : null
    if (loadSequence !== editorLoadSequence) return
    currentVersionId.value = loadedVersionId
    content.value = JSON.stringify(currentVersion?.resumeJson ?? defaultResumeJson(), null, 2)
    initialContent.value = content.value
    readDraft()
    void connectPreviewObserver()
  } catch {
    if (loadSequence !== editorLoadSequence) return
    loadFailed.value = true
  } finally {
    if (loadSequence === editorLoadSequence) loading.value = false
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

onMounted(() => {
  try { sidebarCollapsed.value = localStorage.getItem('resume-editor-sidebar-collapsed') === 'true' } catch { /* storage is optional */ }
  // The content-first editor no longer supports a collapsed editing canvas.
  editorPanelCollapsed.value = false
  window.addEventListener('beforeunload', handleBeforeUnload); window.addEventListener('keydown', handleKeydown)
  void loadEditor()
})

onBeforeUnmount(() => { window.removeEventListener('beforeunload', handleBeforeUnload); window.removeEventListener('keydown', handleKeydown); previewResizeObserver?.disconnect(); if (undoTimer) clearTimeout(undoTimer) })

async function save() {
  let resumeJson: Record<string, unknown>
  try { resumeJson = JSON.parse(content.value) as Record<string, unknown> } catch { error.value = t('resumeEditor.resumeJsonInvalid'); return }
  const submittedResumeId = props.id
  saving.value = true; error.value = ''
  try {
    await createManualVersion(Number(submittedResumeId), resumeJson, summary.value.trim() || undefined)
    clearDraftFor(submittedResumeId)
    if (props.id !== submittedResumeId) return
    initialContent.value = content.value
    await router.push({ name: 'resume-detail', params: { id: submittedResumeId } })
  } catch (requestError) {
    if (props.id === submittedResumeId) {
      const apiMessage = (requestError as AxiosError<{ message?: string }>).response?.data?.message?.trim()
      error.value = apiMessage || t('resumeEditor.saveFailed')
    }
  }
  finally { if (props.id === submittedResumeId) saving.value = false }
}
</script>

<template>
  <section class="resume-studio">
    <header v-if="!loadFailed && !showPreview" class="studio-head">
      <div><p class="eyebrow">{{ t('resumeEditor.studioEyebrow') }}</p><h1>{{ t('resumeEditor.title') }}</h1><p>{{ t('resumeEditor.studioSubtitle') }}</p></div>
      <div class="studio-actions">
        <button class="btn-neon btn-ghost btn-sample" type="button" @click="loadSample"><BookOpen :size="16" /> {{ t('resumeEditor.loadSample') }}</button>
        <button class="btn-neon btn-ghost" type="button" @click="openTemplatePicker">{{ t('resumeEditor.chooseTemplate') }}</button>
        <button class="btn-neon btn-ghost" type="button" @click="showDesign ? closeDesign() : openDesign()">{{ showDesign ? t('resumeEditor.backToContent') : t('resumeEditor.designAdvanced') }}</button>
        <button v-if="showDesign" class="btn-neon btn-ghost" type="button" @click="showSource = !showSource">{{ showSource ? t('resumeEditor.closeJson') : t('resumeEditor.editJson') }}</button>
        <button class="btn-neon btn-ghost" type="button" @click="openPreview">{{ t('resumeEditor.preview') }}</button>
        <button class="btn-neon btn-ghost" type="button" @click="goBack">{{ t('common.back') }}</button>
        <button v-if="showDesign" class="btn-neon btn-primary" type="button" :disabled="saving || !sourceValid || !dirty" @click="save">{{ saving ? t('common.saving') : dirty ? t('resumeEditor.saveNewVersion') : t('resumeEditor.contentSynced') }}</button>
        <button v-else form="resume-form" class="btn-neon btn-primary" :disabled="saving || !sourceValid || !dirty">{{ saving ? t('common.saving') : dirty ? t('resumeEditor.saveNewVersion') : t('resumeEditor.contentSynced') }}</button>
      </div>
    </header>
    <div v-if="!loadFailed && showTemplatePicker" class="template-picker-overlay" role="dialog" :aria-label="t('resumeEditor.templateChooserTitle')" @click.self="closeTemplatePicker">
      <section class="template-picker-dialog">
        <header><div><p class="eyebrow">{{ t('resumeEditor.templateLabel') }}</p><h2>{{ t('resumeEditor.templateChooserTitle') }}</h2><p>{{ t('resumeEditor.templateChooserDescription') }}</p></div><button class="btn-neon btn-ghost" type="button" @click="closeTemplatePicker">{{ t('common.close') }}</button></header>
        <div class="template-options template-options-dialog">
          <button v-for="opt in templateOptions" :key="opt.code" type="button" :class="{ active: templateCode === opt.code }" :aria-pressed="templateCode === opt.code" @click="setTemplate(opt.code)"><span class="template-swatch" :class="`swatch-${opt.code}`" /><strong>{{ opt.name() }}</strong><small>{{ opt.description() }}</small><span v-if="templateCode === opt.code" class="template-selected">{{ t('resumeEditor.templateSelected') }}</span></button>
        </div>
      </section>
    </div>
    <p v-if="error && !loadFailed" class="form-error" role="alert">{{ error }}</p>
    <div v-if="!loadFailed && showSampleConfirm" class="sample-confirm-overlay" @click.self="showSampleConfirm = false">
      <div class="sample-confirm-card">
        <h3>{{ t('resumeEditor.loadSampleTitle') }}</h3>
        <p>{{ t('resumeEditor.loadSampleDesc') }}</p>
        <div class="sample-confirm-actions">
          <button class="btn-neon btn-ghost" type="button" @click="showSampleConfirm = false">{{ t('common.cancel') }}</button>
          <button class="btn-neon btn-primary" type="button" @click="loadSample">{{ t('resumeEditor.confirmLoadSample') }}</button>
        </div>
      </div>
    </div>
    <div v-if="!loadFailed && restoreCandidate" class="draft-restore-banner" role="dialog" :aria-label="t('resumeEditor.restoreDraftTitle')">
      <div><strong>{{ t('resumeEditor.restoreDraftTitle') }}</strong><p>{{ t('resumeEditor.restoreDraftDescription') }}</p></div>
      <div><button class="btn-neon btn-ghost" type="button" @click="clearDraft">{{ t('resumeEditor.discardDraft') }}</button><button class="btn-neon btn-primary" type="button" @click="applyDraft">{{ t('resumeEditor.restoreDraft') }}</button></div>
    </div>
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
        <form v-if="!showDesign" id="resume-form" class="studio-editor" :class="[{ 'is-collapsed': editorPanelCollapsed }, `active-${activeSection}`]" @submit.prevent="save">
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
        <fieldset id="resume-basics" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('basics') }"><legend><span>{{ t('resumeEditor.basicsLabel') }}</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', t('resumeEditor.personalSummary'), 'summary', sectionAiContent('basics'))"><Sparkles :size="13" /> {{ t('resumeEditor.aiOptimize') }}</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('basics')" @click="toggleSection('basics')">{{ isSectionCollapsed('basics') ? t('resumeEditor.expand') : t('resumeEditor.collapse') }}</button></span></legend><div class="field-grid">
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
        <div class="editor-save-dock"><span><strong>{{ dirty ? t('resumeEditor.unsavedChanges') : t('resumeEditor.contentSynced') }}</strong><small>{{ t('resumeEditor.shortcutSave') }}</small></span><div class="editor-save-actions"><button v-if="activeSection !== 'languages'" class="btn-neon btn-ghost" type="button" @click="nextSection">{{ t('resumeEditor.nextSection') }}</button><button class="btn-neon btn-primary" :disabled="saving || !sourceValid || !dirty">{{ saving ? t('common.saving') : t('resumeEditor.saveNewVersion') }}</button></div></div>
        </form>
      </div>
    </div>
    <section v-else class="resume-preview-workspace" :aria-label="t('resumeEditor.previewWorkspaceLabel')">
      <header class="resume-preview-toolbar">
        <div><p class="eyebrow">{{ t('resumeEditor.previewMode') }}</p><h1>{{ t('resumeEditor.previewWorkspaceLabel') }}</h1></div>
        <div class="preview-toolbar-actions">
          <button ref="previewBackButtonRef" class="btn-neon btn-ghost" type="button" @click="closePreview"><ArrowLeft :size="16" /> {{ t('resumeEditor.returnToEditing') }}</button>
          <button class="btn-neon btn-ghost" type="button" @click="openTemplatePicker">{{ t('resumeEditor.chooseTemplate') }}</button>
          <button class="btn-neon btn-primary" type="button" :disabled="saving || !sourceValid || !dirty" @click="save">{{ saving ? t('common.saving') : dirty ? t('resumeEditor.saveNewVersion') : t('resumeEditor.contentSynced') }}</button>
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
