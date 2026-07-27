<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { BookOpen, GripVertical, PanelLeftClose, PanelLeftOpen, PanelRightClose, PanelRightOpen, Sparkles, WandSparkles, X } from 'lucide-vue-next'
import type { AxiosError } from 'axios'
import { createManualVersion, getResume, getResumeVersion, listVersions } from '@/api/resume'
import { inlineOptimize, waitForAiTaskResult, type InlineOptimizeResponse } from '@/api/ai'
import { useLocale } from '@/i18n'
import sampleResume from '@/data/sampleResume'

const { t } = useLocale()
const props = defineProps<{ id: string }>()
const router = useRouter()
const content = ref('')
const summary = ref('')
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const initialContent = ref('')
const showSource = ref(false)
const previewOnly = ref(false)
const sidebarCollapsed = ref(false)
const editorPanelCollapsed = ref(false)
const splitRatio = ref(55)
const isDragging = ref(false)
const gridRef = ref<HTMLElement | null>(null)
const previewPaperRef = ref<HTMLElement | null>(null)
const previewPageCount = ref(1)
let previewResizeObserver: ResizeObserver | undefined
const aiConsentHref = computed(() => `/ai-consent?redirect=${encodeURIComponent(`/resumes/${props.id}/edit`)}`)

function onSplitterDown(e: MouseEvent) {
  e.preventDefault()
  isDragging.value = true
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', onDragEnd)
}

function onDrag(e: MouseEvent) {
  if (!isDragging.value || !gridRef.value) return
  const rect = gridRef.value.getBoundingClientRect()
  const pct = ((e.clientX - rect.left) / rect.width) * 100
  splitRatio.value = Math.min(75, Math.max(40, pct))
}

function onDragEnd() {
  isDragging.value = false
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', onDragEnd)
}
const currentVersionId = ref<number | null>(null)
const sectionKeys = ['basics', 'work', 'skills', 'projects', 'education', 'certificates', 'languages'] as const
type SectionKey = typeof sectionKeys[number]
type SortableSection = 'work' | 'projects' | 'skills' | 'education' | 'certificates' | 'languages'
type DragLocation = { section: SortableSection; index: number; after: boolean }
const defaultContentSectionOrder: SortableSection[] = ['work', 'skills', 'projects', 'education', 'certificates', 'languages']
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

function toggleSidebar() { sidebarCollapsed.value = !sidebarCollapsed.value }
function toggleEditorPanel() { editorPanelCollapsed.value = !editorPanelCollapsed.value }
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
  initialContent.value = content.value
  showSampleConfirm.value = false
}

const templateOptions = [
  { code: 'classic', name: () => t('resumeEditor.templateClassic'), description: () => t('resumeEditor.templateClassicDesc') },
  { code: 'modern', name: () => t('resumeEditor.templateModern'), description: () => t('resumeEditor.templateModernDesc') },
  { code: 'minimal', name: () => t('resumeEditor.templateMinimal'), description: () => t('resumeEditor.templateMinimalDesc') },
] as const

const resume = computed<Record<string, any>>(() => { try { return JSON.parse(content.value || '{}') } catch { return {} } })
const basics = computed<Record<string, any>>(() => resume.value.basics ?? {})
const skills = computed<any[]>(() => Array.isArray(resume.value.skills) ? resume.value.skills : [])
const work = computed<any[]>(() => Array.isArray(resume.value.work) ? resume.value.work : [])
const education = computed<any[]>(() => Array.isArray(resume.value.education) ? resume.value.education : [])
const projects = computed<any[]>(() => Array.isArray(resume.value.projects) ? resume.value.projects : [])
const certificates = computed<any[]>(() => Array.isArray(resume.value.certificates) ? resume.value.certificates : [])
const languages = computed<any[]>(() => Array.isArray(resume.value.languages) ? resume.value.languages : [])
const templateCode = computed(() => {
  const code = resume.value.template?.code
  return templateOptions.some((opt) => opt.code === code) ? code : 'classic'
})
const templateName = computed(() => templateOptions.find((opt) => opt.code === templateCode.value)?.name() ?? 'Classic')
const fontOptions = [
  { code: 'sans', name: '现代黑体', family: '"Microsoft YaHei", Arial, sans-serif' },
  { code: 'songti', name: '雅宋', family: '"Songti SC", SimSun, serif' },
  { code: 'serif', name: '经典衬线', family: 'Georgia, "Times New Roman", serif' },
  { code: 'mono', name: '简洁等宽', family: '"Cascadia Mono", "Courier New", monospace' },
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
const contentSectionOrder = computed<SortableSection[]>(() => {
  const saved = Array.isArray(resume.value.layout?.sectionOrder) ? resume.value.layout.sectionOrder : []
  const valid = saved.filter((section: unknown): section is SortableSection => defaultContentSectionOrder.includes(section as SortableSection))
  return [...valid, ...defaultContentSectionOrder.filter((section) => !valid.includes(section))]
})
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
const previewItemCount = computed(() => work.value.length + projects.value.length + education.value.length + skills.value.length + certificates.value.length + languages.value.length)
const previewTextLength = computed(() => {
  const values = [basics.value.summary, ...work.value, ...projects.value, ...education.value, ...skills.value, ...certificates.value, ...languages.value]
  return values.reduce((total, val) => total + JSON.stringify(val ?? '').length, 0)
})
const previewDensity = computed(() => previewItemCount.value + previewTextLength.value / 180)
const previewMayOverflow = computed(() => previewDensity.value > 12)
const hasBodyContent = computed(() => Boolean(basics.value.summary) || previewItemCount.value > 0)
const completionChecks = computed(() => [
  Boolean(basics.value.name), Boolean(basics.value.title || basics.value.position),
  Boolean(basics.value.email || basics.value.phone), Boolean(basics.value.summary),
  work.value.length > 0, projects.value.length > 0, skills.value.length > 0, education.value.length > 0,
])
const completionScore = computed(() => Math.round(completionChecks.value.filter(Boolean).length / completionChecks.value.length * 100))
const nextSuggestion = computed(() => {
  if (!basics.value.name) return t('resumeEditor.nameRequired')
  if (!basics.value.title && !basics.value.position) return '补充目标岗位，让内容有清晰方向。'
  if (!basics.value.summary) return '用三句话概括经验、专业方向与核心优势。'
  if (!work.value.length && !projects.value.length) return '添加一段最能证明能力的经历或项目。'
  if (!skills.value.length) return '补充与目标岗位直接相关的专业技能。'
  return '主体内容已经完整，继续精炼成果表达。'
})

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
        : 'AI 润色暂时不可用，请稍后重试。'
    }
  } finally { if (aiAssistant.value?.section === section) aiAssistant.value.loading = false }
}

function applyAiCandidate(value: string) {
  const assistant = aiAssistant.value
  if (!assistant?.apply) return
  assistant.apply(value)
  summary.value = summary.value || `采纳 AI 润色：${assistant.label}`
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
  const paper = previewPaperRef.value
  if (!paper) return
  const a4Height = paper.clientWidth * 297 / 210
  previewPageCount.value = Math.max(1, Math.ceil(paper.scrollHeight / a4Height))
}

function syncPreviewSectionOrder() {
  const paper = previewPaperRef.value
  if (!paper) return
  const sections = Array.from(paper.querySelectorAll(':scope > section')) as HTMLElement[]
  const hasSummary = Boolean(basics.value.summary)
  const visibleOrder = defaultContentSectionOrder.filter((section) => {
    if (section === 'work') return work.value.length > 0
    if (section === 'skills') return skills.value.length > 0
    if (section === 'projects') return projects.value.length > 0
    if (section === 'education') return education.value.length > 0
    if (section === 'certificates') return certificates.value.length > 0
    return languages.value.length > 0
  })
  sections.slice(hasSummary ? 1 : 0).forEach((element, index) => {
    const section = visibleOrder[index]
    if (section) element.style.order = String(20 + contentSectionOrder.value.indexOf(section))
  })
}

watch(content, async () => { await nextTick(); syncPreviewSectionOrder(); updatePreviewPageCount() })
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

function updateResume(mutator: (draft: Record<string, any>) => void) {
  if (!sourceValid.value) { showSource.value = true; error.value = '源数据 JSON 格式有误，请先修复后再继续可视化编辑。'; return }
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
function removeWork(index: number) { const label = work.value[index]?.company || work.value[index]?.position || `Work ${index + 1}`; removeWithUndo(label, (d) => { d.work = (Array.isArray(d.work) ? d.work : []).filter((_: unknown, i: number) => i !== index) }) }
function setWorkHighlights(index: number, value: string) { setWork(index, 'highlights', value.split('\n').map((i) => i.trim()).filter(Boolean)) }
function addSkill() { expandSection('skills'); updateResume((d) => { d.skills = [...(Array.isArray(d.skills) ? d.skills : []), { name: '' }] }) }
function setSkill(index: number, value: string) { updateResume((d) => { const items = Array.isArray(d.skills) ? [...d.skills] : []; items[index] = typeof items[index] === 'string' ? value : { ...(items[index] ?? {}), name: value }; d.skills = items }) }
function removeSkill(index: number) { const item = skills.value[index]; const label = (typeof item === 'string' ? item : item?.name || item?.keyword) || `Skill ${index + 1}`; removeWithUndo(label, (d) => { d.skills = (Array.isArray(d.skills) ? d.skills : []).filter((_: unknown, i: number) => i !== index) }) }
function addEducation() { expandSection('education'); updateResume((d) => { d.education = [...(Array.isArray(d.education) ? d.education : []), { school: '', degree: '', major: '', startDate: '', endDate: '' }] }) }
function setEducation(index: number, field: string, value: string) { updateResume((d) => { const items = Array.isArray(d.education) ? [...d.education] : []; items[index] = { ...(items[index] ?? {}), [field]: value }; d.education = items }) }
function removeEducation(index: number) { const label = education.value[index]?.school || `Education ${index + 1}`; removeWithUndo(label, (d) => { d.education = (Array.isArray(d.education) ? d.education : []).filter((_: unknown, i: number) => i !== index) }) }
function addProject() { expandSection('projects'); updateResume((d) => { d.projects = [...(Array.isArray(d.projects) ? d.projects : []), { name: '', role: '', description: '', highlights: [] }] }) }
function setProject(index: number, field: string, value: unknown) { updateResume((d) => { const items = Array.isArray(d.projects) ? [...d.projects] : []; items[index] = { ...(items[index] ?? {}), [field]: value }; d.projects = items }) }
function removeProject(index: number) { const label = projects.value[index]?.name || `Project ${index + 1}`; removeWithUndo(label, (d) => { d.projects = (Array.isArray(d.projects) ? d.projects : []).filter((_: unknown, i: number) => i !== index) }) }
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
function addSimpleItem(section: 'certificates' | 'languages') { expandSection(section); updateResume((d) => { const item = section === 'certificates' ? { name: '', issuer: '', date: '' } : { name: '', level: '' }; d[section] = [...(Array.isArray(d[section]) ? d[section] : []), item] }) }
function setSimpleItem(section: 'certificates' | 'languages', index: number, field: string, value: string) { updateResume((d) => { const items = Array.isArray(d[section]) ? [...d[section]] : []; items[index] = { ...(items[index] ?? {}), [field]: value }; d[section] = items }) }
function removeSimpleItem(section: 'certificates' | 'languages', index: number) { const col = section === 'certificates' ? certificates.value : languages.value; const label = col[index]?.name || `${section === 'certificates' ? 'Certificate' : 'Language'} ${index + 1}`; removeWithUndo(label, (d) => { d[section] = (Array.isArray(d[section]) ? d[section] : []).filter((_: unknown, i: number) => i !== index) }) }
function defaultResumeJson() { return { basics: { name: '' }, work: [], education: [], skills: [], projects: [], certificates: [], languages: [], template: { code: 'classic' }, layout: defaultLayout } }

onMounted(async () => {
  try { sidebarCollapsed.value = localStorage.getItem('resume-editor-sidebar-collapsed') === 'true' } catch { /* storage is optional */ }
  try { editorPanelCollapsed.value = localStorage.getItem('resume-editor-property-panel-collapsed') === 'true' } catch { /* storage is optional */ }
  window.addEventListener('beforeunload', handleBeforeUnload); window.addEventListener('keydown', handleKeydown)
  try {
    const resumeResponse = await getResume(Number(props.id))
    currentVersionId.value = resumeResponse.data.data.currentVersionId
    if (currentVersionId.value == null) {
      const versionsResponse = await listVersions(Number(props.id))
      currentVersionId.value = versionsResponse.data.data[0]?.id ?? null
    }
    const currentVersion = currentVersionId.value
      ? (await getResumeVersion(currentVersionId.value)).data.data
      : null
    content.value = JSON.stringify(currentVersion?.resumeJson ?? defaultResumeJson(), null, 2)
    initialContent.value = content.value
  } catch { error.value = '简历版本无法加载，请返回列表后重试。' }
  finally {
    loading.value = false
    await nextTick()
    updatePreviewPageCount()
    if (previewPaperRef.value) {
      previewResizeObserver = new ResizeObserver(updatePreviewPageCount)
      previewResizeObserver.observe(previewPaperRef.value)
    }
  }
})

onBeforeUnmount(() => { window.removeEventListener('beforeunload', handleBeforeUnload); window.removeEventListener('keydown', handleKeydown); document.removeEventListener('mousemove', onDrag); document.removeEventListener('mouseup', onDragEnd); previewResizeObserver?.disconnect(); if (undoTimer) clearTimeout(undoTimer) })

async function save() {
  let resumeJson: Record<string, unknown>
  try { resumeJson = JSON.parse(content.value) as Record<string, unknown> } catch { error.value = '简历 JSON 格式无效。'; return }
  saving.value = true; error.value = ''
  try {
    await createManualVersion(Number(props.id), resumeJson, summary.value.trim() || undefined)
    initialContent.value = content.value
    await router.push({ name: 'resume-detail', params: { id: props.id } })
  } catch (requestError) {
    const apiMessage = (requestError as AxiosError<{ message?: string }>).response?.data?.message?.trim()
    error.value = apiMessage || '版本保存失败，请稍后重试。'
  }
  finally { saving.value = false }
}
</script>

<template>
  <section class="resume-studio" :class="{ 'preview-mode': previewOnly }">
    <header class="studio-head">
      <div><p class="eyebrow">Resume studio</p><h1>{{ t('resumeEditor.title') }}</h1><p>左侧编辑，右侧即时预览。保存后会创建新的历史版本。</p></div>
      <div class="studio-actions">
        <button class="btn-neon btn-ghost btn-sample" type="button" @click="loadSample"><BookOpen :size="16" /> {{ t('resumeEditor.loadSample') }}</button>
        <button class="btn-neon btn-ghost" type="button" @click="previewOnly = !previewOnly">{{ previewOnly ? '返回编辑' : '专注预览' }}</button>
        <button v-if="!previewOnly" class="btn-neon btn-ghost" type="button" @click="showSource = !showSource">{{ showSource ? '收起源数据' : '查看源数据' }}</button>
        <button class="btn-neon btn-ghost" type="button" @click="goBack">{{ t('common.back') }}</button>
        <button v-if="!previewOnly" form="resume-form" class="btn-neon btn-primary" :disabled="saving || !sourceValid || !dirty">{{ saving ? t('common.saving') : dirty ? '保存新版本' : '没有待保存修改' }}</button>
      </div>
    </header>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <div v-if="showSampleConfirm" class="sample-confirm-overlay" @click.self="showSampleConfirm = false">
      <div class="sample-confirm-card">
        <h3>{{ t('resumeEditor.loadSampleTitle') }}</h3>
        <p>{{ t('resumeEditor.loadSampleDesc') }}</p>
        <div class="sample-confirm-actions">
          <button class="btn-neon btn-ghost" type="button" @click="showSampleConfirm = false">{{ t('common.cancel') }}</button>
          <button class="btn-neon btn-primary" type="button" @click="loadSample">确认加载示例</button>
        </div>
      </div>
    </div>
    <p v-if="loading">{{ t('common.loading') }}</p>
    <div v-else class="studio-grid" :class="{ 'sidebar-collapsed': sidebarCollapsed, 'editor-panel-collapsed': editorPanelCollapsed }" ref="gridRef">
      <div v-if="!previewOnly" class="editor-shell">
        <aside class="editor-sidebar" :class="{ 'is-collapsed': sidebarCollapsed }" aria-label="简历编辑控制台">
          <button class="sidebar-toggle" type="button" :aria-expanded="!sidebarCollapsed" :title="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'" @click="toggleSidebar">
            <PanelLeftOpen v-if="sidebarCollapsed" :size="16" /><PanelLeftClose v-else :size="16" />
            <span>{{ sidebarCollapsed ? '展开' : '收起' }}</span>
          </button>
          <div class="editor-command-center">
          <label class="version-note">本次版本说明<input v-model.trim="summary" maxlength="1000" placeholder="例如：针对 Java 后端岗位调整" /></label>
          <fieldset class="template-picker"><legend>{{ t('resumeEditor.templateLabel') }}</legend><div class="template-options"><button v-for="opt in templateOptions" :key="opt.code" type="button" :class="{ active: templateCode === opt.code }" :aria-pressed="templateCode === opt.code" @click="setTemplate(opt.code)"><span class="template-swatch" :class="`swatch-${opt.code}`" /><strong>{{ opt.name() }}</strong><small>{{ opt.description() }}</small></button></div></fieldset>
          <fieldset class="layout-controls">
            <legend>版式设置</legend>
            <div class="layout-presets" role="group" aria-label="内容密度预设">
              <button type="button" @click="applyLayoutPreset('compact')">紧凑</button>
              <button type="button" @click="applyLayoutPreset('balanced')">均衡</button>
              <button type="button" @click="applyLayoutPreset('spacious')">舒展</button>
            </div>
            <label class="font-family-select">字体风格
              <select aria-label="字体风格" :value="layout.fontFamily" @change="setLayout('fontFamily', ($event.target as HTMLSelectElement).value)">
                <option v-for="option in fontOptions" :key="option.code" :value="option.code">{{ option.name }}</option>
              </select>
            </label>
            <label>正文字号 <output>{{ layout.bodyFontSize }} px</output><input aria-label="正文字号滑杆" type="range" min="11" max="16" step="1" :value="layout.bodyFontSize" @input="setLayout('bodyFontSize', Number(($event.target as HTMLInputElement).value))" /></label>
            <label>标题字号 <output>{{ layout.headingFontSize }} px</output><input aria-label="标题字号滑杆" type="range" min="11" max="18" step="1" :value="layout.headingFontSize" @input="setLayout('headingFontSize', Number(($event.target as HTMLInputElement).value))" /></label>
            <label>行距 <output>{{ layout.lineHeight.toFixed(2) }}</output><input aria-label="行距滑杆" type="range" min="1.30" max="2.00" step="0.05" :value="layout.lineHeight" @input="setLayout('lineHeight', Number(($event.target as HTMLInputElement).value))" /></label>
            <label>模块间距 <output>{{ layout.sectionSpacing }} px</output><input aria-label="模块间距滑杆" type="range" min="10" max="32" step="2" :value="layout.sectionSpacing" @input="setLayout('sectionSpacing', Number(($event.target as HTMLInputElement).value))" /></label>
            <label>条目间距 <output>{{ layout.entrySpacing }} px</output><input aria-label="条目间距滑杆" type="range" min="6" max="22" step="2" :value="layout.entrySpacing" @input="setLayout('entrySpacing', Number(($event.target as HTMLInputElement).value))" /></label>
            <label>页面留白 <output>{{ layout.pagePadding }} px</output><input aria-label="页面留白滑杆" type="range" min="32" max="80" step="2" :value="layout.pagePadding" @input="setLayout('pagePadding', Number(($event.target as HTMLInputElement).value))" /></label>
            <button class="layout-reset" type="button" @click="resetLayout">恢复默认</button>
          </fieldset>
          <div class="editor-progress" role="status"><div class="progress-heading"><span>内容完成度</span><strong>{{ completionScore }}%</strong></div><div class="progress-track"><span :style="{ width: `${completionScore}%` }" /></div><small>{{ nextSuggestion }}</small></div>
          <nav class="editor-outline" aria-label="简历内容目录">
            <a href="#resume-basics" title="基本信息" :class="{ complete: basics.name && (basics.title || basics.position), active: !isSectionCollapsed('basics') }" @click.prevent="jumpToSection('basics')"><span data-short="基">{{ t('resumeEditor.basicsLabel') }}</span><small>{{ basics.name ? t('resumeEditor.basicsFilled') : t('resumeEditor.basicsEmpty') }}</small></a>
            <a href="#resume-work" title="工作经历" :class="{ complete: work.length, active: !isSectionCollapsed('work') }" @click.prevent="jumpToSection('work')"><span data-short="工">{{ t('resumeEditor.workLabel') }}</span><small>{{ work.length }} 段</small></a>
            <a href="#resume-skills" title="专业技能" :class="{ complete: skills.length, active: !isSectionCollapsed('skills') }" @click.prevent="jumpToSection('skills')"><span data-short="技">{{ t('resumeEditor.skillsLabel') }}</span><small>{{ skills.length }} 项</small></a>
            <a href="#resume-projects" title="项目经历" :class="{ complete: projects.length, active: !isSectionCollapsed('projects') }" @click.prevent="jumpToSection('projects')"><span data-short="项">{{ t('resumeEditor.projectsLabel') }}</span><small>{{ projects.length }} 个</small></a>
            <a href="#resume-education" title="教育经历" :class="{ complete: education.length, active: !isSectionCollapsed('education') }" @click.prevent="jumpToSection('education')"><span data-short="教">{{ t('resumeEditor.educationLabel') }}</span><small>{{ education.length }} 段</small></a>
            <a href="#resume-certificates" title="专业证书" :class="{ complete: certificates.length, active: !isSectionCollapsed('certificates') }" @click.prevent="jumpToSection('certificates')"><span data-short="证">专业证书</span><small>{{ certificates.length }} 项</small></a>
            <a href="#resume-languages" title="语言能力" :class="{ complete: languages.length, active: !isSectionCollapsed('languages') }" @click.prevent="jumpToSection('languages')"><span data-short="语">语言能力</span><small>{{ languages.length }} 项</small></a>
          </nav>
          </div>
        </aside>
        <form id="resume-form" class="studio-editor" :class="{ 'is-collapsed': editorPanelCollapsed }" @submit.prevent="save">
        <header class="property-panel-heading"><div><span>内容属性</span><strong>编辑简历内容</strong><small>从左侧选择章节，在此修改字段。</small></div><button class="property-toggle" type="button" :aria-expanded="!editorPanelCollapsed" :title="editorPanelCollapsed ? '展开编辑器' : '收起编辑器'" @click="toggleEditorPanel"><PanelRightOpen v-if="editorPanelCollapsed" :size="16" /><PanelRightClose v-else :size="16" /></button></header>
        <aside v-if="aiAssistant" class="ai-assistant-panel" aria-live="polite">
          <header><span class="ai-orb"><Sparkles :size="15" /></span><div><small>{{ aiAssistant.scope === 'field' ? '字段润色' : '模块优化' }}</small><strong>{{ aiAssistant.label }}</strong></div><button type="button" aria-label="关闭 AI 助手" @click="closeAiAssistant"><X :size="16" /></button></header>
          <p v-if="aiAssistant.content">AI 将基于当前内容和简历上下文优化表达；候选文本必须由你确认后才会写回。</p>
          <p v-else>先填写真实内容，再让 AI 帮你改善表达。AI 不会补造经历、技能或量化结果。</p>
          <div class="ai-guardrails"><span>保持事实</span><span>可选目标 JD</span><span>人工确认写回</span></div>
          <div v-if="aiAssistant.loading" class="ai-candidate-placeholder"><WandSparkles :size="16" /><div><strong>正在生成候选表达</strong><small>仅调整措辞，不补充原文之外的事实。</small></div></div>
          <p v-else-if="aiAssistant.error" class="ai-inline-error" role="alert">{{ aiAssistant.error }}</p>
          <div v-else-if="aiAssistant.result" class="ai-candidate-list">
            <article v-for="(c, ci) in aiAssistant.result.candidates" :key="ci">
              <span>候选 {{ ci + 1 }}</span><p>{{ c.content }}</p><small>{{ c.suggestion }}</small>
              <button v-if="aiAssistant.apply" type="button" @click="applyAiCandidate(c.content)">采纳并写回</button>
            </article>
            <div v-if="!aiAssistant.result.candidates.length" class="ai-candidate-placeholder">
              <WandSparkles :size="16" /><div><strong>没有可安全采纳的改写</strong><small>{{ aiAssistant.result.emptyReason || '当前内容已经较清晰；补充真实的职责、技术或成果后可获得更明显的优化。' }}</small></div>
            </div>
          </div>
          <div v-else class="ai-candidate-placeholder"><WandSparkles :size="16" /><div><strong>{{ aiAssistant.content ? '等待 AI 服务' : '等待填写内容' }}</strong><small>{{ aiAssistant.content ? '确认已完成 AI 数据授权后重试。' : '填写真实内容后即可生成 3 个候选版本。' }}</small></div></div>
          <footer v-if="aiAssistant.needsConsent"><a class="ai-consent-action" :href="aiConsentHref">重新授权</a></footer>
        </aside>
        <!-- Basics -->
        <fieldset id="resume-basics" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('basics') }"><legend><span>{{ t('resumeEditor.basicsLabel') }}</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', '个人概要', 'summary', sectionAiContent('basics'))"><Sparkles :size="13" /> AI 优化</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('basics')" @click="toggleSection('basics')">{{ isSectionCollapsed('basics') ? '展开' : '收起' }}</button></span></legend><div class="field-grid">
          <label>姓名<input :value="basics.name ?? ''" placeholder="张明远" @input="setBasic('name', ($event.target as HTMLInputElement).value)" /></label>
          <label>目标岗位<input :value="basics.title ?? basics.position ?? ''" placeholder="高级后端工程师" @input="setBasic('title', ($event.target as HTMLInputElement).value)" /></label>
          <label>邮箱<input :value="basics.email ?? ''" type="email" placeholder="name@example.com" @input="setBasic('email', ($event.target as HTMLInputElement).value)" /></label>
          <label>电话<input :value="basics.phone ?? ''" placeholder="138 0000 0000" @input="setBasic('phone', ($event.target as HTMLInputElement).value)" /></label>
          <label class="span-two">所在地<input :value="basics.location ?? ''" placeholder="上海" @input="setBasic('location', ($event.target as HTMLInputElement).value)" /></label>
          <label class="span-two"><span class="field-label-row"><span>个人概要</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', '个人概要', 'summary', basics.summary, value => setBasic('summary', value))"><WandSparkles :size="13" /> 润色</button></span><textarea :value="basics.summary ?? ''" rows="4" placeholder="概括专业方向、经验与优势。" @input="setBasic('summary', ($event.target as HTMLTextAreaElement).value)" /></label>
        </div></fieldset>
        <!-- Work -->
        <fieldset id="resume-work" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('work'), 'is-section-drag-before': isContentSectionTarget('work', false), 'is-section-drag-after': isContentSectionTarget('work', true) }" :style="contentSectionStyle('work')" @dragover.prevent="updateContentSectionTarget('work', $event)" @drop.prevent="dropContentSection('work')"><legend><button type="button" class="section-drag-handle" draggable="true" title="拖拽调整模块顺序" aria-label="拖拽调整模块顺序" @dragstart="startContentSectionDrag('work', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>{{ t('resumeEditor.workLabel') }}</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', '工作经历', 'workDescription', sectionAiContent('work'))"><Sparkles :size="13" /> AI 优化</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('work')" @click="toggleSection('work')">{{ isSectionCollapsed('work') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addWork">＋ {{ t('resumeEditor.addWork') }}</button></span></legend>
          <p v-if="!work.length" class="editor-empty">添加第一段经历后，它会立即出现在右侧预览。</p>
          <article v-for="(item, index) in work" :key="index" class="work-editor" :class="{ 'is-drag-over-before': isDragTarget('work', index, false), 'is-drag-over-after': isDragTarget('work', index, true) }" @dragover.prevent="updateDragTarget('work', index, $event)" @drop.prevent="dropItem('work', index, $event)"><div class="work-editor-head"><strong>经历 {{ index + 1 }}</strong><div class="item-order-actions"><button type="button" class="drag-handle" draggable="true" title="拖拽排序" aria-label="拖拽排序" @dragstart="startItemDrag('work', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="item-move" :disabled="index === 0" title="上移" @click="moveItem('work', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === work.length - 1" title="下移" @click="moveItem('work', index, 1)">↓</button><button type="button" @click="removeWork(index)">{{ t('common.delete') }}</button></div></div><div class="field-grid">
            <label>公司<input :value="item.company ?? item.name ?? ''" @input="setWork(index, 'company', ($event.target as HTMLInputElement).value)" /></label>
            <label>职位<input :value="item.position ?? item.role ?? ''" @input="setWork(index, 'position', ($event.target as HTMLInputElement).value)" /></label>
            <label>开始时间<input :value="item.startDate ?? ''" placeholder="2022-03" @input="setWork(index, 'startDate', ($event.target as HTMLInputElement).value)" /></label>
            <label>结束时间<input :value="item.endDate ?? ''" placeholder="至今" @input="setWork(index, 'endDate', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>职责概述</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', `经历 ${index + 1} · 职责概述`, 'workDescription', item.description, value => setWork(index, 'description', value))"><WandSparkles :size="13" /> 润色</button></span><textarea :value="item.description ?? ''" rows="3" @input="setWork(index, 'description', ($event.target as HTMLTextAreaElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>成果要点</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', `经历 ${index + 1} · 成果要点`, 'workHighlights', item.highlights, value => setWorkHighlights(index, value))"><WandSparkles :size="13" /> 强化成果</button></span><textarea :value="Array.isArray(item.highlights) ? item.highlights.join('\n') : ''" rows="4" placeholder="每行一条，优先写动作、规模和结果。" @input="setWorkHighlights(index, ($event.target as HTMLTextAreaElement).value)" /></label>
          </div></article>
        </fieldset>
        <!-- Skills -->
        <fieldset id="resume-skills" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('skills'), 'is-section-drag-before': isContentSectionTarget('skills', false), 'is-section-drag-after': isContentSectionTarget('skills', true) }" :style="contentSectionStyle('skills')" @dragover.prevent="updateContentSectionTarget('skills', $event)" @drop.prevent="dropContentSection('skills')"><legend><button type="button" class="section-drag-handle" draggable="true" title="拖拽调整模块顺序" aria-label="拖拽调整模块顺序" @dragstart="startContentSectionDrag('skills', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>{{ t('resumeEditor.skillsLabel') }}</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', '专业技能', 'skillDescription', sectionAiContent('skills'))"><Sparkles :size="13" /> AI 优化</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('skills')" @click="toggleSection('skills')">{{ isSectionCollapsed('skills') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addSkill">＋ {{ t('resumeEditor.addSkill') }}</button></span></legend>
          <p v-if="!skills.length" class="editor-empty">添加与你目标岗位相关、并且可以被经历证明的技能。</p>
          <div v-for="(skill, index) in skills" :key="index" class="inline-editor" :class="{ 'is-drag-over-before': isDragTarget('skills', index, false), 'is-drag-over-after': isDragTarget('skills', index, true) }" @dragover.prevent="updateDragTarget('skills', index, $event)" @drop.prevent="dropItem('skills', index, $event)"><input :value="typeof skill === 'string' ? skill : skill.name ?? skill.keyword ?? ''" placeholder="例如：Spring Boot" @input="setSkill(index, ($event.target as HTMLInputElement).value)" /><div class="inline-editor-actions"><button type="button" class="drag-handle" draggable="true" title="拖拽排序" aria-label="拖拽排序" @dragstart="startItemDrag('skills', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="ai-inline-action" title="AI 润色" @click="openAiAssistant('field', `技能 ${index + 1}`, 'skillDescription', typeof skill === 'string' ? skill : skill.name ?? skill.keyword, value => setSkill(index, value))"><WandSparkles :size="13" /></button><button type="button" class="item-move" :disabled="index === 0" title="上移" @click="moveItem('skills', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === skills.length - 1" title="下移" @click="moveItem('skills', index, 1)">↓</button><button type="button" @click="removeSkill(index)">{{ t('common.delete') }}</button></div></div>
        </fieldset>
        <!-- Projects -->
        <fieldset id="resume-projects" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('projects'), 'is-section-drag-before': isContentSectionTarget('projects', false), 'is-section-drag-after': isContentSectionTarget('projects', true) }" :style="contentSectionStyle('projects')" @dragover.prevent="updateContentSectionTarget('projects', $event)" @drop.prevent="dropContentSection('projects')"><legend><button type="button" class="section-drag-handle" draggable="true" title="拖拽调整模块顺序" aria-label="拖拽调整模块顺序" @dragstart="startContentSectionDrag('projects', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>{{ t('resumeEditor.projectsLabel') }}</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', '项目经历', 'projectDescription', sectionAiContent('projects'))"><Sparkles :size="13" /> AI 优化</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('projects')" @click="toggleSection('projects')">{{ isSectionCollapsed('projects') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addProject">＋ {{ t('resumeEditor.addProject') }}</button></span></legend>
          <p v-if="!projects.length" class="editor-empty">选择最能证明能力的项目，写清你负责什么以及产生了什么结果。</p>
          <article v-for="(item, index) in projects" :key="index" class="work-editor" :class="{ 'is-drag-over-before': isDragTarget('projects', index, false), 'is-drag-over-after': isDragTarget('projects', index, true) }" @dragover.prevent="updateDragTarget('projects', index, $event)" @drop.prevent="dropItem('projects', index, $event)"><div class="work-editor-head"><strong>项目 {{ index + 1 }}</strong><div class="item-order-actions"><button type="button" class="drag-handle" draggable="true" title="拖拽排序" aria-label="拖拽排序" @dragstart="startItemDrag('projects', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="item-move" :disabled="index === 0" title="上移" @click="moveItem('projects', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === projects.length - 1" title="下移" @click="moveItem('projects', index, 1)">↓</button><button type="button" @click="removeProject(index)">{{ t('common.delete') }}</button></div></div><div class="field-grid">
            <label>项目名称<input :value="item.name ?? ''" @input="setProject(index, 'name', ($event.target as HTMLInputElement).value)" /></label>
            <label>担任角色<input :value="item.role ?? item.position ?? ''" @input="setProject(index, 'role', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>项目说明</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', `项目 ${index + 1} · 项目说明`, 'projectDescription', item.description, value => setProject(index, 'description', value))"><WandSparkles :size="13" /> 润色</button></span><textarea :value="item.description ?? ''" rows="3" @input="setProject(index, 'description', ($event.target as HTMLTextAreaElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>项目成果</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', `项目 ${index + 1} · 项目成果`, 'projectHighlights', item.highlights, value => setProject(index, 'highlights', value.split('\n').map(i => i.trim()).filter(Boolean)))"><WandSparkles :size="13" /> 强化成果</button></span><textarea :value="Array.isArray(item.highlights) ? item.highlights.join('\n') : ''" rows="4" placeholder="每行一条成果。" @input="setProject(index, 'highlights', ($event.target as HTMLTextAreaElement).value.split('\n').map(v => v.trim()).filter(Boolean))" /></label>
          </div></article>
        </fieldset>
        <!-- Education -->
        <fieldset id="resume-education" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('education'), 'is-section-drag-before': isContentSectionTarget('education', false), 'is-section-drag-after': isContentSectionTarget('education', true) }" :style="contentSectionStyle('education')" @dragover.prevent="updateContentSectionTarget('education', $event)" @drop.prevent="dropContentSection('education')"><legend><button type="button" class="section-drag-handle" draggable="true" title="拖拽调整模块顺序" aria-label="拖拽调整模块顺序" @dragstart="startContentSectionDrag('education', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>{{ t('resumeEditor.educationLabel') }}</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('education')" @click="toggleSection('education')">{{ isSectionCollapsed('education') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addEducation">＋ {{ t('resumeEditor.addEducation') }}</button></span></legend>
          <p v-if="!education.length" class="editor-empty">添加学校、专业和学历信息。</p>
          <article v-for="(item, index) in education" :key="index" class="work-editor" :class="{ 'is-drag-over-before': isDragTarget('education', index, false), 'is-drag-over-after': isDragTarget('education', index, true) }" @dragover.prevent="updateDragTarget('education', index, $event)" @drop.prevent="dropItem('education', index, $event)"><div class="work-editor-head"><strong>教育经历 {{ index + 1 }}</strong><div class="item-order-actions"><button type="button" class="drag-handle" draggable="true" title="拖拽排序" aria-label="拖拽排序" @dragstart="startItemDrag('education', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="item-move" :disabled="index === 0" title="上移" @click="moveItem('education', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === education.length - 1" title="下移" @click="moveItem('education', index, 1)">↓</button><button type="button" @click="removeEducation(index)">{{ t('common.delete') }}</button></div></div><div class="field-grid">
            <label>学校<input :value="item.school ?? item.name ?? ''" @input="setEducation(index, 'school', ($event.target as HTMLInputElement).value)" /></label>
            <label>学历<input :value="item.degree ?? ''" placeholder="本科" @input="setEducation(index, 'degree', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two">专业<input :value="item.major ?? item.area ?? ''" @input="setEducation(index, 'major', ($event.target as HTMLInputElement).value)" /></label>
            <label>开始时间<input :value="item.startDate ?? ''" placeholder="2018-09" @input="setEducation(index, 'startDate', ($event.target as HTMLInputElement).value)" /></label>
            <label>结束时间<input :value="item.endDate ?? ''" placeholder="2022-06" @input="setEducation(index, 'endDate', ($event.target as HTMLInputElement).value)" /></label>
          </div></article>
        </fieldset>
        <!-- Certificates -->
        <fieldset id="resume-certificates" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('certificates'), 'is-section-drag-before': isContentSectionTarget('certificates', false), 'is-section-drag-after': isContentSectionTarget('certificates', true) }" :style="contentSectionStyle('certificates')" @dragover.prevent="updateContentSectionTarget('certificates', $event)" @drop.prevent="dropContentSection('certificates')"><legend><button type="button" class="section-drag-handle" draggable="true" title="拖拽调整模块顺序" aria-label="拖拽调整模块顺序" @dragstart="startContentSectionDrag('certificates', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>专业证书</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('certificates')" @click="toggleSection('certificates')">{{ isSectionCollapsed('certificates') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addSimpleItem('certificates')">＋ 添加证书</button></span></legend>
          <p v-if="!certificates.length" class="editor-empty">填写与目标岗位相关、仍然有效的专业认证。</p>
          <article v-for="(item, index) in certificates" :key="index" class="work-editor" :class="{ 'is-drag-over-before': isDragTarget('certificates', index, false), 'is-drag-over-after': isDragTarget('certificates', index, true) }" @dragover.prevent="updateDragTarget('certificates', index, $event)" @drop.prevent="dropItem('certificates', index, $event)"><div class="work-editor-head"><strong>证书 {{ index + 1 }}</strong><div class="item-order-actions"><button type="button" class="drag-handle" draggable="true" title="拖拽排序" aria-label="拖拽排序" @dragstart="startItemDrag('certificates', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="item-move" :disabled="index === 0" title="上移" @click="moveItem('certificates', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === certificates.length - 1" title="下移" @click="moveItem('certificates', index, 1)">↓</button><button type="button" @click="removeSimpleItem('certificates', index)">{{ t('common.delete') }}</button></div></div><div class="field-grid">
            <label>证书名称<input :value="item.name ?? ''" placeholder="例如：AWS Solutions Architect" @input="setSimpleItem('certificates', index, 'name', ($event.target as HTMLInputElement).value)" /></label>
            <label>颁发机构<input :value="item.issuer ?? ''" placeholder="Amazon Web Services" @input="setSimpleItem('certificates', index, 'issuer', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two">获得时间<input :value="item.date ?? ''" placeholder="2024-06" @input="setSimpleItem('certificates', index, 'date', ($event.target as HTMLInputElement).value)" /></label>
          </div></article>
        </fieldset>
        <!-- Languages -->
        <fieldset id="resume-languages" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('languages'), 'is-section-drag-before': isContentSectionTarget('languages', false), 'is-section-drag-after': isContentSectionTarget('languages', true) }" :style="contentSectionStyle('languages')" @dragover.prevent="updateContentSectionTarget('languages', $event)" @drop.prevent="dropContentSection('languages')"><legend><button type="button" class="section-drag-handle" draggable="true" title="拖拽调整模块顺序" aria-label="拖拽调整模块顺序" @dragstart="startContentSectionDrag('languages', $event)" @dragend="endContentSectionDrag"><GripVertical :size="16" /></button><span>语言能力</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('languages')" @click="toggleSection('languages')">{{ isSectionCollapsed('languages') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addSimpleItem('languages')">＋ 添加语言</button></span></legend>
          <p v-if="!languages.length" class="editor-empty">仅保留能为岗位加分的语言与熟练程度。</p>
          <article v-for="(item, index) in languages" :key="index" class="work-editor" :class="{ 'is-drag-over-before': isDragTarget('languages', index, false), 'is-drag-over-after': isDragTarget('languages', index, true) }" @dragover.prevent="updateDragTarget('languages', index, $event)" @drop.prevent="dropItem('languages', index, $event)"><div class="work-editor-head"><strong>语言 {{ index + 1 }}</strong><div class="item-order-actions"><button type="button" class="drag-handle" draggable="true" title="拖拽排序" aria-label="拖拽排序" @dragstart="startItemDrag('languages', index, $event)" @dragend="endItemDrag"><GripVertical :size="16" /></button><button type="button" class="item-move" :disabled="index === 0" title="上移" @click="moveItem('languages', index, -1)">↑</button><button type="button" class="item-move" :disabled="index === languages.length - 1" title="下移" @click="moveItem('languages', index, 1)">↓</button><button type="button" @click="removeSimpleItem('languages', index)">{{ t('common.delete') }}</button></div></div><div class="field-grid">
            <label>语言<input :value="item.name ?? item.language ?? ''" placeholder="例如：英语" @input="setSimpleItem('languages', index, 'name', ($event.target as HTMLInputElement).value)" /></label>
            <label>熟练程度<input :value="item.level ?? item.fluency ?? ''" placeholder="例如：专业工作沟通" @input="setSimpleItem('languages', index, 'level', ($event.target as HTMLInputElement).value)" /></label>
          </div></article>
        </fieldset>
        <div class="editor-note"><strong>高级编辑</strong><span>兴趣等少用扩展字段可通过标准 JSON 精确调整。</span></div>
        <label v-if="showSource" class="source-editor open"><span>简历源数据</span><textarea v-model="content" :rows="28" required spellcheck="false" /><small :class="sourceValid ? 'source-ok' : 'source-invalid'">{{ sourceValid ? 'JSON 格式有效' : 'JSON 格式有误，修复后才能保存' }}</small></label>
        <div v-if="undoRemoval" class="editor-undo" role="status"><span>已移除"{{ undoRemoval.label }}"</span><button type="button" @click="restoreRemoval">撤销</button></div>
        <div class="editor-save-dock"><span><strong>{{ dirty ? '有未保存修改' : '当前内容已同步' }}</strong><small>快捷键 Ctrl / Cmd + S</small></span><button class="btn-neon btn-primary" :disabled="saving || !sourceValid || !dirty">{{ saving ? t('common.saving') : t('resumeEditor.saveDraft') }}</button></div>
        </form>
      </div>
      <div v-if="!previewOnly" class="studio-splitter" :class="{ dragging: isDragging }" @mousedown="onSplitterDown" />
      <aside class="preview-rail"><div class="preview-label"><span>{{ previewOnly ? '专注预览 · 保存后可在版本页导出 PDF' : `${templateName}样式 · 实时预览` }}</span><span :class="{ 'preview-warning': previewPageCount > 1 }">{{ `预计 ${previewPageCount} 页 · A4` }}</span></div>
        <div class="preview-scroll">
        <article ref="previewPaperRef" class="resume-paper" :class="`template-${templateCode}`" :style="layoutStyle">
          <header class="paper-header"><h2 :class="{ 'paper-placeholder': !basics.name }">{{ basics.name || '你的姓名' }}</h2><p :class="{ 'paper-placeholder': !(basics.title || basics.position) }">{{ basics.title || basics.position || '目标岗位' }}</p><div :class="{ 'paper-placeholder': ![basics.phone, basics.email, basics.location].some(Boolean) }">{{ [basics.phone, basics.email, basics.location].filter(Boolean).join('  ·  ') || '电话 · 邮箱 · 城市' }}</div></header>
          <div v-if="!hasBodyContent" class="paper-empty-guide"><span>从左侧开始</span><h3>先写内容，再追求版式</h3><ol><li><strong>说明你是谁</strong><small>姓名、目标岗位和联系方式</small></li><li><strong>证明你做成过什么</strong><small>经历、项目和可量化成果</small></li><li><strong>匹配目标岗位</strong><small>专业技能与教育背景</small></li></ol></div>
          <section v-if="basics.summary"><h3>个人概要</h3><p>{{ basics.summary }}</p></section>
          <section v-if="work.length"><h3>{{ t('resumeEditor.workLabel') }}</h3><div v-for="(item, index) in work" :key="index" class="paper-entry"><strong>{{ item.company || item.name || '公司名称' }}</strong><span>{{ item.position || item.role || '' }}</span><small>{{ item.startDate || '' }}{{ item.endDate ? ` — ${item.endDate}` : '' }}</small><p v-if="item.description">{{ item.description }}</p><ul v-if="item.highlights"><li v-for="(p, pi) in item.highlights" :key="pi">{{ p }}</li></ul></div></section>
          <section v-if="skills.length"><h3>{{ t('resumeEditor.skillsLabel') }}</h3><div class="skill-chips"><span v-for="(skill, index) in skills" :key="index">{{ typeof skill === 'string' ? skill : skill.name || skill.keyword }}</span></div></section>
          <section v-if="projects.length"><h3>{{ t('resumeEditor.projectsLabel') }}</h3><div v-for="(item, index) in projects" :key="index" class="paper-entry"><strong>{{ item.name || '项目名称' }}</strong><span>{{ item.role || item.position || '' }}</span><p v-if="item.description">{{ item.description }}</p><ul v-if="item.highlights"><li v-for="(p, pi) in item.highlights" :key="pi">{{ p }}</li></ul></div></section>
          <section v-if="education.length"><h3>{{ t('resumeEditor.educationLabel') }}</h3><div v-for="(item, index) in education" :key="index" class="paper-entry"><strong>{{ item.school || item.name }}</strong><span>{{ [item.degree, item.major || item.area].filter(Boolean).join(' · ') }}</span><small>{{ item.startDate || '' }}{{ item.endDate ? ` — ${item.endDate}` : '' }}</small></div></section>
          <section v-if="certificates.length"><h3>专业证书</h3><div v-for="(item, index) in certificates" :key="index" class="paper-entry compact"><strong>{{ item.name || '证书名称' }}</strong><span>{{ item.issuer || '' }}</span><small>{{ item.date || '' }}</small></div></section>
          <section v-if="languages.length"><h3>语言能力</h3><div class="skill-chips"><span v-for="(item, index) in languages" :key="index">{{ [item.name || item.language, item.level || item.fluency].filter(Boolean).join(' · ') }}</span></div></section>
        </article>
        </div>
      </aside>
    </div>
  </section>
</template>
