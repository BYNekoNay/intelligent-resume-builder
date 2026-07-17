<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { onBeforeRouteLeave, useRouter } from 'vue-router'
import { Sparkles, WandSparkles, X } from 'lucide-vue-next'
import { createManualVersion, listVersions } from '@/api/resume'
import { inlineOptimize, type InlineOptimizeResponse } from '@/api/ai'

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
const currentVersionId = ref<number | null>(null)
const sectionKeys = ['basics', 'work', 'skills', 'projects', 'education', 'certificates', 'languages'] as const
type SectionKey = typeof sectionKeys[number]
const collapsedSections = ref<Set<SectionKey>>(new Set(sectionKeys.filter((section) => section !== 'basics')))
type AiAssistantState = {
  scope: 'field' | 'section'
  label: string
  section: string
  content: string
  loading: boolean
  result: InlineOptimizeResponse | null
  error: string
  apply?: (value: string) => void
}
const aiAssistant = ref<AiAssistantState | null>(null)
const undoRemoval = ref<{ content: string; label: string } | null>(null)
let undoTimer: ReturnType<typeof setTimeout> | undefined
const templateOptions = [
  { code: 'classic', name: '经典', description: '稳重分隔线，适合通用岗位' },
  { code: 'modern', name: '现代', description: '蓝色侧栏感，突出技能与成果' },
  { code: 'minimal', name: '极简', description: '高留白排版，适合设计与管理岗位' },
] as const
const resume = computed<Record<string, any>>(() => {
  try { return JSON.parse(content.value || '{}') } catch { return {} }
})
const basics = computed<Record<string, any>>(() => resume.value.basics ?? {})
const skills = computed<any[]>(() => Array.isArray(resume.value.skills) ? resume.value.skills : [])
const work = computed<any[]>(() => Array.isArray(resume.value.work) ? resume.value.work : [])
const education = computed<any[]>(() => Array.isArray(resume.value.education) ? resume.value.education : [])
const projects = computed<any[]>(() => Array.isArray(resume.value.projects) ? resume.value.projects : [])
const certificates = computed<any[]>(() => Array.isArray(resume.value.certificates) ? resume.value.certificates : [])
const languages = computed<any[]>(() => Array.isArray(resume.value.languages) ? resume.value.languages : [])
const templateCode = computed(() => {
  const code = resume.value.template?.code
  return templateOptions.some((option) => option.code === code) ? code : 'classic'
})
const templateName = computed(() => templateOptions.find((option) => option.code === templateCode.value)?.name ?? '经典')
const sourceValid = computed(() => {
  try { JSON.parse(content.value); return true } catch { return false }
})
const dirty = computed(() => !loading.value && (content.value !== initialContent.value || summary.value.trim().length > 0))
const previewItemCount = computed(() => work.value.length + projects.value.length + education.value.length + skills.value.length + certificates.value.length + languages.value.length)
const previewTextLength = computed(() => {
  const values = [basics.value.summary, ...work.value, ...projects.value, ...education.value, ...skills.value, ...certificates.value, ...languages.value]
  return values.reduce((total, value) => total + JSON.stringify(value ?? '').length, 0)
})
const previewDensity = computed(() => previewItemCount.value + previewTextLength.value / 180)
const previewMayOverflow = computed(() => previewDensity.value > 12)
const hasBodyContent = computed(() => Boolean(basics.value.summary) || previewItemCount.value > 0)
const completionChecks = computed(() => [
  Boolean(basics.value.name),
  Boolean(basics.value.title || basics.value.position),
  Boolean(basics.value.email || basics.value.phone),
  Boolean(basics.value.summary),
  work.value.length > 0,
  projects.value.length > 0,
  skills.value.length > 0,
  education.value.length > 0,
])
const completionScore = computed(() => Math.round(completionChecks.value.filter(Boolean).length / completionChecks.value.length * 100))
const nextSuggestion = computed(() => {
  if (!basics.value.name) return '先填写姓名，让简历拥有明确的主人。'
  if (!basics.value.title && !basics.value.position) return '补充目标岗位，让内容有清晰方向。'
  if (!basics.value.summary) return '用三句话概括经验、专业方向与核心优势。'
  if (!work.value.length && !projects.value.length) return '添加一段最能证明能力的经历或项目。'
  if (!skills.value.length) return '补充与目标岗位直接相关的专业技能。'
  return '主体内容已经完整，继续精炼成果表达。'
})

function isSectionCollapsed(section: SectionKey) {
  return collapsedSections.value.has(section)
}

function toggleSection(section: SectionKey) {
  if (isSectionCollapsed(section)) {
    collapsedSections.value = new Set(sectionKeys.filter((key) => key !== section))
    return
  }
  collapsedSections.value = new Set(sectionKeys)
}

function expandSection(section: SectionKey) {
  collapsedSections.value = new Set(sectionKeys.filter((key) => key !== section))
}

async function jumpToSection(section: SectionKey) {
  expandSection(section)
  await nextTick()
  document.getElementById(`resume-${section}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function openAiAssistant(scope: 'field' | 'section', label: string, section: string, value: unknown, apply?: (value: string) => void) {
  const contentValue = Array.isArray(value) ? value.filter(Boolean).join('\n') : String(value ?? '').trim()
  aiAssistant.value = { scope, label, section, content: contentValue, loading: false, result: null, error: '', apply }
  if (!contentValue || !currentVersionId.value) return
  aiAssistant.value.loading = true
  try {
    const response = await inlineOptimize({
      resumeVersionId: currentVersionId.value,
      section,
      content: contentValue,
    })
    if (aiAssistant.value?.section === section && aiAssistant.value.content === contentValue) {
      aiAssistant.value.result = response.data.data
    }
  } catch (requestError: any) {
    if (aiAssistant.value?.section === section) {
      aiAssistant.value.error = requestError?.response?.data?.code === 40302
        ? '请先完成 AI 数据处理授权，再使用润色功能。'
        : 'AI 润色暂时不可用，请稍后重试。'
    }
  } finally {
    if (aiAssistant.value?.section === section) aiAssistant.value.loading = false
  }
}

function applyAiCandidate(value: string) {
  const assistant = aiAssistant.value
  if (!assistant?.apply) return
  assistant.apply(value)
  summary.value = summary.value || `采纳 AI 润色：${assistant.label}`
  closeAiAssistant()
}

function closeAiAssistant() {
  aiAssistant.value = null
}

function sectionAiContent(section: 'basics' | 'work' | 'skills' | 'projects') {
  if (section === 'basics') return basics.value.summary ?? ''
  if (section === 'skills') return skills.value.map((item) => typeof item === 'string' ? item : item.name ?? item.keyword ?? '').filter(Boolean)
  const collection = section === 'work' ? work.value : projects.value
  return collection.flatMap((item) => [item.description, ...(Array.isArray(item.highlights) ? item.highlights : [])]).filter(Boolean)
}

function handleBeforeUnload(event: BeforeUnloadEvent) {
  if (!dirty.value) return
  event.preventDefault()
  event.returnValue = ''
}

function handleKeydown(event: KeyboardEvent) {
  if (!(event.ctrlKey || event.metaKey) || event.key.toLowerCase() !== 's') return
  event.preventDefault()
  if (dirty.value && sourceValid.value && !saving.value) void save()
}

function goBack() {
  router.back()
}

onBeforeRouteLeave(() => {
  if (!dirty.value) return true
  return window.confirm('当前修改尚未保存，确定离开编辑器吗？')
})

function updateResume(mutator: (draft: Record<string, any>) => void) {
  if (!sourceValid.value) {
    showSource.value = true
    error.value = '源数据 JSON 格式有误，请先修复后再继续可视化编辑。'
    return
  }
  const draft = JSON.parse(JSON.stringify(resume.value || {})) as Record<string, any>
  mutator(draft)
  content.value = JSON.stringify(draft, null, 2)
  error.value = ''
}

function removeWithUndo(label: string, mutator: (draft: Record<string, any>) => void) {
  if (!sourceValid.value) {
    updateResume(() => undefined)
    return
  }
  const previousContent = content.value
  updateResume(mutator)
  undoRemoval.value = { content: previousContent, label }
  if (undoTimer) clearTimeout(undoTimer)
  undoTimer = setTimeout(() => { undoRemoval.value = null }, 6000)
}

function restoreRemoval() {
  if (!undoRemoval.value) return
  content.value = undoRemoval.value.content
  undoRemoval.value = null
  if (undoTimer) clearTimeout(undoTimer)
}

function setBasic(field: string, value: string) {
  updateResume((draft) => { draft.basics = { ...(draft.basics ?? {}), [field]: value } })
}

function setTemplate(code: string) {
  updateResume((draft) => { draft.template = { code } })
}

function addWork() {
  expandSection('work')
  updateResume((draft) => { draft.work = [...(Array.isArray(draft.work) ? draft.work : []), { company: '', position: '', startDate: '', endDate: '' }] })
}

function setWork(index: number, field: string, value: unknown) {
  updateResume((draft) => {
    const items = Array.isArray(draft.work) ? [...draft.work] : []
    items[index] = { ...(items[index] ?? {}), [field]: value }
    draft.work = items
  })
}

function removeWork(index: number) {
  const label = work.value[index]?.company || work.value[index]?.position || `经历 ${index + 1}`
  removeWithUndo(label, (draft) => { draft.work = (Array.isArray(draft.work) ? draft.work : []).filter((_: unknown, itemIndex: number) => itemIndex !== index) })
}

function setWorkHighlights(index: number, value: string) {
  setWork(index, 'highlights', value.split('\n').map((item) => item.trim()).filter(Boolean))
}

function addSkill() {
  expandSection('skills')
  updateResume((draft) => { draft.skills = [...(Array.isArray(draft.skills) ? draft.skills : []), { name: '' }] })
}

function setSkill(index: number, value: string) {
  updateResume((draft) => {
    const items = Array.isArray(draft.skills) ? [...draft.skills] : []
    items[index] = typeof items[index] === 'string' ? value : { ...(items[index] ?? {}), name: value }
    draft.skills = items
  })
}

function removeSkill(index: number) {
  const item = skills.value[index]
  const label = (typeof item === 'string' ? item : item?.name || item?.keyword) || `技能 ${index + 1}`
  removeWithUndo(label, (draft) => { draft.skills = (Array.isArray(draft.skills) ? draft.skills : []).filter((_: unknown, itemIndex: number) => itemIndex !== index) })
}

function addEducation() {
  expandSection('education')
  updateResume((draft) => { draft.education = [...(Array.isArray(draft.education) ? draft.education : []), { school: '', degree: '', major: '', startDate: '', endDate: '' }] })
}

function setEducation(index: number, field: string, value: string) {
  updateResume((draft) => {
    const items = Array.isArray(draft.education) ? [...draft.education] : []
    items[index] = { ...(items[index] ?? {}), [field]: value }
    draft.education = items
  })
}

function removeEducation(index: number) {
  const label = education.value[index]?.school || `教育经历 ${index + 1}`
  removeWithUndo(label, (draft) => { draft.education = (Array.isArray(draft.education) ? draft.education : []).filter((_: unknown, itemIndex: number) => itemIndex !== index) })
}

function addProject() {
  expandSection('projects')
  updateResume((draft) => { draft.projects = [...(Array.isArray(draft.projects) ? draft.projects : []), { name: '', role: '', description: '', highlights: [] }] })
}

function setProject(index: number, field: string, value: unknown) {
  updateResume((draft) => {
    const items = Array.isArray(draft.projects) ? [...draft.projects] : []
    items[index] = { ...(items[index] ?? {}), [field]: value }
    draft.projects = items
  })
}

function removeProject(index: number) {
  const label = projects.value[index]?.name || `项目 ${index + 1}`
  removeWithUndo(label, (draft) => { draft.projects = (Array.isArray(draft.projects) ? draft.projects : []).filter((_: unknown, itemIndex: number) => itemIndex !== index) })
}

function moveItem(section: 'work' | 'projects' | 'skills' | 'education' | 'certificates' | 'languages', index: number, direction: -1 | 1) {
  updateResume((draft) => {
    const items = Array.isArray(draft[section]) ? [...draft[section]] : []
    const targetIndex = index + direction
    if (targetIndex < 0 || targetIndex >= items.length) return
    ;[items[index], items[targetIndex]] = [items[targetIndex], items[index]]
    draft[section] = items
  })
}

function addSimpleItem(section: 'certificates' | 'languages') {
  expandSection(section)
  updateResume((draft) => {
    const item = section === 'certificates' ? { name: '', issuer: '', date: '' } : { name: '', level: '' }
    draft[section] = [...(Array.isArray(draft[section]) ? draft[section] : []), item]
  })
}

function setSimpleItem(section: 'certificates' | 'languages', index: number, field: string, value: string) {
  updateResume((draft) => {
    const items = Array.isArray(draft[section]) ? [...draft[section]] : []
    items[index] = { ...(items[index] ?? {}), [field]: value }
    draft[section] = items
  })
}

function removeSimpleItem(section: 'certificates' | 'languages', index: number) {
  const collection = section === 'certificates' ? certificates.value : languages.value
  const label = collection[index]?.name || `${section === 'certificates' ? '证书' : '语言'} ${index + 1}`
  removeWithUndo(label, (draft) => { draft[section] = (Array.isArray(draft[section]) ? draft[section] : []).filter((_: unknown, itemIndex: number) => itemIndex !== index) })
}

function defaultResumeJson() {
  return {
    basics: { name: '' },
    work: [],
    education: [],
    skills: [],
    projects: [],
    certificates: [],
    languages: [],
    template: { code: 'classic' },
  }
}

onMounted(async () => {
  window.addEventListener('beforeunload', handleBeforeUnload)
  window.addEventListener('keydown', handleKeydown)
  try {
    const response = await listVersions(Number(props.id))
    const latest = response.data.data[0]
    currentVersionId.value = latest?.id ?? null
    content.value = JSON.stringify(latest?.resumeJson ?? defaultResumeJson(), null, 2)
    initialContent.value = content.value
  } catch {
    error.value = '简历版本无法加载，请返回列表后重试。'
  } finally {
    loading.value = false
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', handleBeforeUnload)
  window.removeEventListener('keydown', handleKeydown)
  if (undoTimer) clearTimeout(undoTimer)
})

async function save() {
  let resumeJson: Record<string, unknown>
  try {
    resumeJson = JSON.parse(content.value) as Record<string, unknown>
  } catch {
    error.value = '简历 JSON 格式无效。'
    return
  }

  saving.value = true
  error.value = ''
  try {
    await createManualVersion(Number(props.id), resumeJson, summary.value.trim() || undefined)
    initialContent.value = content.value
    await router.push({ name: 'resume-detail', params: { id: props.id } })
  } catch {
    error.value = '版本保存失败，请确认 basics 字段和 JSON 结构后重试。'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <section class="resume-studio" :class="{ 'preview-mode': previewOnly }">
    <header class="studio-head">
      <div><p class="eyebrow">Resume studio</p><h1>把经历写成一页好简历</h1><p>左侧编辑，右侧即时预览。保存后会创建新的历史版本。</p></div>
      <div class="studio-actions"><button class="btn-neon btn-ghost" type="button" @click="previewOnly = !previewOnly">{{ previewOnly ? '返回编辑' : '专注预览' }}</button><button v-if="!previewOnly" class="btn-neon btn-ghost" type="button" @click="showSource = !showSource">{{ showSource ? '收起源数据' : '查看源数据' }}</button><button class="btn-neon btn-ghost" type="button" @click="goBack">返回</button><button v-if="!previewOnly" form="resume-form" class="btn-neon btn-primary" :disabled="saving || !sourceValid || !dirty">{{ saving ? '正在保存…' : dirty ? '保存新版本' : '没有待保存修改' }}</button></div>
    </header>
    <p v-if="error" class="form-error" role="alert">{{ error }}</p>
    <p v-if="loading">加载中...</p>
    <div v-else class="studio-grid">
      <form id="resume-form" class="studio-editor" @submit.prevent="save">
        <div class="editor-command-center">
          <label class="version-note">本次版本说明<input v-model.trim="summary" maxlength="1000" placeholder="例如：针对 Java 后端岗位调整" /></label>
          <fieldset class="template-picker"><legend>简历样式</legend><div class="template-options"><button v-for="option in templateOptions" :key="option.code" type="button" :class="{ active: templateCode === option.code }" :aria-pressed="templateCode === option.code" @click="setTemplate(option.code)"><span class="template-swatch" :class="`swatch-${option.code}`" /><strong>{{ option.name }}</strong><small>{{ option.description }}</small></button></div></fieldset>
          <div class="editor-progress" role="status"><div class="progress-heading"><span>内容完成度</span><strong>{{ completionScore }}%</strong></div><div class="progress-track"><span :style="{ width: `${completionScore}%` }" /></div><small>{{ nextSuggestion }}</small></div>
          <nav class="editor-outline" aria-label="简历内容目录">
            <a href="#resume-basics" :class="{ complete: basics.name && (basics.title || basics.position), active: !isSectionCollapsed('basics') }" @click.prevent="jumpToSection('basics')"><span>个人信息</span><small>{{ basics.name ? '已填写' : '待填写' }}</small></a>
            <a href="#resume-work" :class="{ complete: work.length, active: !isSectionCollapsed('work') }" @click.prevent="jumpToSection('work')"><span>工作经历</span><small>{{ work.length }} 段</small></a>
            <a href="#resume-skills" :class="{ complete: skills.length, active: !isSectionCollapsed('skills') }" @click.prevent="jumpToSection('skills')"><span>专业技能</span><small>{{ skills.length }} 项</small></a>
            <a href="#resume-projects" :class="{ complete: projects.length, active: !isSectionCollapsed('projects') }" @click.prevent="jumpToSection('projects')"><span>项目经历</span><small>{{ projects.length }} 个</small></a>
            <a href="#resume-education" :class="{ complete: education.length, active: !isSectionCollapsed('education') }" @click.prevent="jumpToSection('education')"><span>教育经历</span><small>{{ education.length }} 段</small></a>
            <a href="#resume-certificates" :class="{ complete: certificates.length, active: !isSectionCollapsed('certificates') }" @click.prevent="jumpToSection('certificates')"><span>专业证书</span><small>{{ certificates.length }} 项</small></a>
            <a href="#resume-languages" :class="{ complete: languages.length, active: !isSectionCollapsed('languages') }" @click.prevent="jumpToSection('languages')"><span>语言能力</span><small>{{ languages.length }} 项</small></a>
          </nav>
        </div>
        <aside v-if="aiAssistant" class="ai-assistant-panel" aria-live="polite">
          <header><span class="ai-orb"><Sparkles :size="15" /></span><div><small>{{ aiAssistant.scope === 'field' ? '字段润色' : '模块优化' }}</small><strong>{{ aiAssistant.label }}</strong></div><button type="button" aria-label="关闭 AI 助手" @click="closeAiAssistant"><X :size="16" /></button></header>
          <p v-if="aiAssistant.content">AI 将基于当前内容和简历上下文优化表达；候选文本必须由你确认后才会写回。</p>
          <p v-else>先填写真实内容，再让 AI 帮你改善表达。AI 不会补造经历、技能或量化结果。</p>
          <div class="ai-guardrails"><span>保持事实</span><span>可选目标 JD</span><span>人工确认写回</span></div>
          <div v-if="aiAssistant.loading" class="ai-candidate-placeholder"><WandSparkles :size="16" /><div><strong>正在生成候选表达</strong><small>仅调整措辞，不补充原文之外的事实。</small></div></div>
          <p v-else-if="aiAssistant.error" class="ai-inline-error" role="alert">{{ aiAssistant.error }}</p>
          <div v-else-if="aiAssistant.result" class="ai-candidate-list">
            <article v-for="(candidate, candidateIndex) in aiAssistant.result.candidates" :key="candidateIndex">
              <span>候选 {{ candidateIndex + 1 }}</span><p>{{ candidate.content }}</p><small>{{ candidate.suggestion }}</small>
              <button v-if="aiAssistant.apply" type="button" @click="applyAiCandidate(candidate.content)">采纳并写回</button>
            </article>
          </div>
          <div v-else class="ai-candidate-placeholder"><WandSparkles :size="16" /><div><strong>{{ aiAssistant.content ? '等待 AI 服务' : '等待填写内容' }}</strong><small>{{ aiAssistant.content ? '确认已完成 AI 数据授权后重试。' : '填写真实内容后即可生成 3 个候选版本。' }}</small></div></div>
          <footer><RouterLink class="text-link" to="/ai-consent">管理 AI 数据授权</RouterLink><small v-if="aiAssistant.result">记录 #{{ aiAssistant.result.recordId }} · 已追溯</small></footer>
        </aside>
        <fieldset id="resume-basics" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('basics') }"><legend><span>个人信息</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', '个人概要', 'summary', sectionAiContent('basics'))"><Sparkles :size="13" /> AI 优化</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('basics')" @click="toggleSection('basics')">{{ isSectionCollapsed('basics') ? '展开' : '收起' }}</button></span></legend><div class="field-grid">
          <label>姓名<input :value="basics.name ?? ''" placeholder="张明远" @input="setBasic('name', ($event.target as HTMLInputElement).value)" /></label>
          <label>目标岗位<input :value="basics.title ?? basics.position ?? ''" placeholder="高级后端工程师" @input="setBasic('title', ($event.target as HTMLInputElement).value)" /></label>
          <label>邮箱<input :value="basics.email ?? ''" type="email" placeholder="name@example.com" @input="setBasic('email', ($event.target as HTMLInputElement).value)" /></label>
          <label>电话<input :value="basics.phone ?? ''" placeholder="138 0000 0000" @input="setBasic('phone', ($event.target as HTMLInputElement).value)" /></label>
          <label class="span-two">所在地<input :value="basics.location ?? ''" placeholder="上海" @input="setBasic('location', ($event.target as HTMLInputElement).value)" /></label>
          <label class="span-two"><span class="field-label-row"><span>个人概要</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', '个人概要', 'summary', basics.summary, value => setBasic('summary', value))"><WandSparkles :size="13" /> 润色</button></span><textarea :value="basics.summary ?? ''" rows="4" placeholder="概括专业方向、经验与优势。" @input="setBasic('summary', ($event.target as HTMLTextAreaElement).value)" /></label>
        </div></fieldset>
        <fieldset id="resume-work" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('work') }"><legend><span>工作经历</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', '工作经历', 'workDescription', sectionAiContent('work'))"><Sparkles :size="13" /> AI 优化</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('work')" @click="toggleSection('work')">{{ isSectionCollapsed('work') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addWork">＋ 添加经历</button></span></legend>
          <p v-if="!work.length" class="editor-empty">添加第一段经历后，它会立即出现在右侧预览。</p>
          <article v-for="(item, index) in work" :key="index" class="work-editor"><div class="work-editor-head"><strong>经历 {{ index + 1 }}</strong><div class="item-order-actions"><button type="button" :disabled="index === 0" :aria-label="`上移经历 ${index + 1}`" title="上移" @click="moveItem('work', index, -1)">↑</button><button type="button" :disabled="index === work.length - 1" :aria-label="`下移经历 ${index + 1}`" title="下移" @click="moveItem('work', index, 1)">↓</button><button type="button" :aria-label="`删除经历 ${index + 1}`" @click="removeWork(index)">删除</button></div></div><div class="field-grid">
            <label>公司<input :value="item.company ?? item.name ?? ''" @input="setWork(index, 'company', ($event.target as HTMLInputElement).value)" /></label>
            <label>职位<input :value="item.position ?? item.role ?? ''" @input="setWork(index, 'position', ($event.target as HTMLInputElement).value)" /></label>
            <label>开始时间<input :value="item.startDate ?? ''" placeholder="2022-03" @input="setWork(index, 'startDate', ($event.target as HTMLInputElement).value)" /></label>
            <label>结束时间<input :value="item.endDate ?? ''" placeholder="至今" @input="setWork(index, 'endDate', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>职责概述</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', `经历 ${index + 1} · 职责概述`, 'workDescription', item.description, value => setWork(index, 'description', value))"><WandSparkles :size="13" /> 润色</button></span><textarea :value="item.description ?? ''" rows="3" @input="setWork(index, 'description', ($event.target as HTMLTextAreaElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>成果要点</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', `经历 ${index + 1} · 成果要点`, 'workHighlights', item.highlights, value => setWorkHighlights(index, value))"><WandSparkles :size="13" /> 强化成果</button></span><textarea :value="Array.isArray(item.highlights) ? item.highlights.join('\n') : ''" rows="4" placeholder="每行一条，优先写动作、规模和结果。" @input="setWorkHighlights(index, ($event.target as HTMLTextAreaElement).value)" /></label>
          </div></article>
        </fieldset>
        <fieldset id="resume-skills" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('skills') }"><legend><span>专业技能</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', '专业技能', 'skillDescription', sectionAiContent('skills'))"><Sparkles :size="13" /> AI 优化</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('skills')" @click="toggleSection('skills')">{{ isSectionCollapsed('skills') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addSkill">＋ 添加技能</button></span></legend>
          <p v-if="!skills.length" class="editor-empty">添加与你目标岗位相关、并且可以被经历证明的技能。</p>
          <div v-for="(skill, index) in skills" :key="index" class="inline-editor"><input :value="typeof skill === 'string' ? skill : skill.name ?? skill.keyword ?? ''" placeholder="例如：Spring Boot" @input="setSkill(index, ($event.target as HTMLInputElement).value)" /><div class="inline-editor-actions"><button type="button" class="ai-inline-action" :aria-label="`润色技能 ${index + 1}`" title="AI 润色" @click="openAiAssistant('field', `技能 ${index + 1}`, 'skillDescription', typeof skill === 'string' ? skill : skill.name ?? skill.keyword, value => setSkill(index, value))"><WandSparkles :size="13" /></button><button type="button" :disabled="index === 0" :aria-label="`上移技能 ${index + 1}`" title="上移" @click="moveItem('skills', index, -1)">↑</button><button type="button" :disabled="index === skills.length - 1" :aria-label="`下移技能 ${index + 1}`" title="下移" @click="moveItem('skills', index, 1)">↓</button><button type="button" :aria-label="`删除技能 ${index + 1}`" @click="removeSkill(index)">删除</button></div></div>
        </fieldset>
        <fieldset id="resume-projects" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('projects') }"><legend><span>项目经历</span><span class="legend-actions"><button type="button" class="ai-section-action" @click="openAiAssistant('section', '项目经历', 'projectDescription', sectionAiContent('projects'))"><Sparkles :size="13" /> AI 优化</button><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('projects')" @click="toggleSection('projects')">{{ isSectionCollapsed('projects') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addProject">＋ 添加项目</button></span></legend>
          <p v-if="!projects.length" class="editor-empty">选择最能证明能力的项目，写清你负责什么以及产生了什么结果。</p>
          <article v-for="(item, index) in projects" :key="index" class="work-editor"><div class="work-editor-head"><strong>项目 {{ index + 1 }}</strong><div class="item-order-actions"><button type="button" :disabled="index === 0" :aria-label="`上移项目 ${index + 1}`" title="上移" @click="moveItem('projects', index, -1)">↑</button><button type="button" :disabled="index === projects.length - 1" :aria-label="`下移项目 ${index + 1}`" title="下移" @click="moveItem('projects', index, 1)">↓</button><button type="button" :aria-label="`删除项目 ${index + 1}`" @click="removeProject(index)">删除</button></div></div><div class="field-grid">
            <label>项目名称<input :value="item.name ?? ''" @input="setProject(index, 'name', ($event.target as HTMLInputElement).value)" /></label>
            <label>担任角色<input :value="item.role ?? item.position ?? ''" @input="setProject(index, 'role', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>项目说明</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', `项目 ${index + 1} · 项目说明`, 'projectDescription', item.description, value => setProject(index, 'description', value))"><WandSparkles :size="13" /> 润色</button></span><textarea :value="item.description ?? ''" rows="3" @input="setProject(index, 'description', ($event.target as HTMLTextAreaElement).value)" /></label>
            <label class="span-two"><span class="field-label-row"><span>项目成果</span><button type="button" class="ai-field-action" @click="openAiAssistant('field', `项目 ${index + 1} · 项目成果`, 'projectHighlights', item.highlights, value => setProject(index, 'highlights', value.split('\n').map(item => item.trim()).filter(Boolean)))"><WandSparkles :size="13" /> 强化成果</button></span><textarea :value="Array.isArray(item.highlights) ? item.highlights.join('\n') : ''" rows="4" placeholder="每行一条成果。" @input="setProject(index, 'highlights', ($event.target as HTMLTextAreaElement).value.split('\n').map(value => value.trim()).filter(Boolean))" /></label>
          </div></article>
        </fieldset>
        <fieldset id="resume-education" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('education') }"><legend><span>教育经历</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('education')" @click="toggleSection('education')">{{ isSectionCollapsed('education') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addEducation">＋ 添加教育经历</button></span></legend>
          <p v-if="!education.length" class="editor-empty">添加学校、专业和学历信息。</p>
          <article v-for="(item, index) in education" :key="index" class="work-editor"><div class="work-editor-head"><strong>教育经历 {{ index + 1 }}</strong><div class="item-order-actions"><button type="button" :disabled="index === 0" :aria-label="`上移教育经历 ${index + 1}`" title="上移" @click="moveItem('education', index, -1)">↑</button><button type="button" :disabled="index === education.length - 1" :aria-label="`下移教育经历 ${index + 1}`" title="下移" @click="moveItem('education', index, 1)">↓</button><button type="button" :aria-label="`删除教育经历 ${index + 1}`" @click="removeEducation(index)">删除</button></div></div><div class="field-grid">
            <label>学校<input :value="item.school ?? item.name ?? ''" @input="setEducation(index, 'school', ($event.target as HTMLInputElement).value)" /></label>
            <label>学历<input :value="item.degree ?? ''" placeholder="本科" @input="setEducation(index, 'degree', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two">专业<input :value="item.major ?? item.area ?? ''" @input="setEducation(index, 'major', ($event.target as HTMLInputElement).value)" /></label>
            <label>开始时间<input :value="item.startDate ?? ''" placeholder="2018-09" @input="setEducation(index, 'startDate', ($event.target as HTMLInputElement).value)" /></label>
            <label>结束时间<input :value="item.endDate ?? ''" placeholder="2022-06" @input="setEducation(index, 'endDate', ($event.target as HTMLInputElement).value)" /></label>
          </div></article>
        </fieldset>
        <fieldset id="resume-certificates" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('certificates') }"><legend><span>专业证书</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('certificates')" @click="toggleSection('certificates')">{{ isSectionCollapsed('certificates') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addSimpleItem('certificates')">＋ 添加证书</button></span></legend>
          <p v-if="!certificates.length" class="editor-empty">填写与目标岗位相关、仍然有效的专业认证。</p>
          <article v-for="(item, index) in certificates" :key="index" class="work-editor"><div class="work-editor-head"><strong>证书 {{ index + 1 }}</strong><div class="item-order-actions"><button type="button" :disabled="index === 0" :aria-label="`上移证书 ${index + 1}`" title="上移" @click="moveItem('certificates', index, -1)">↑</button><button type="button" :disabled="index === certificates.length - 1" :aria-label="`下移证书 ${index + 1}`" title="下移" @click="moveItem('certificates', index, 1)">↓</button><button type="button" :aria-label="`删除证书 ${index + 1}`" @click="removeSimpleItem('certificates', index)">删除</button></div></div><div class="field-grid">
            <label>证书名称<input :value="item.name ?? ''" placeholder="例如：AWS Solutions Architect" @input="setSimpleItem('certificates', index, 'name', ($event.target as HTMLInputElement).value)" /></label>
            <label>颁发机构<input :value="item.issuer ?? ''" placeholder="Amazon Web Services" @input="setSimpleItem('certificates', index, 'issuer', ($event.target as HTMLInputElement).value)" /></label>
            <label class="span-two">获得时间<input :value="item.date ?? ''" placeholder="2024-06" @input="setSimpleItem('certificates', index, 'date', ($event.target as HTMLInputElement).value)" /></label>
          </div></article>
        </fieldset>
        <fieldset id="resume-languages" class="editor-section" :class="{ 'is-collapsed': isSectionCollapsed('languages') }"><legend><span>语言能力</span><span class="legend-actions"><button type="button" class="section-toggle" :aria-expanded="!isSectionCollapsed('languages')" @click="toggleSection('languages')">{{ isSectionCollapsed('languages') ? '展开' : '收起' }}</button><button type="button" class="section-add" @click="addSimpleItem('languages')">＋ 添加语言</button></span></legend>
          <p v-if="!languages.length" class="editor-empty">仅保留能为岗位加分的语言与熟练程度。</p>
          <article v-for="(item, index) in languages" :key="index" class="work-editor"><div class="work-editor-head"><strong>语言 {{ index + 1 }}</strong><div class="item-order-actions"><button type="button" :disabled="index === 0" :aria-label="`上移语言 ${index + 1}`" title="上移" @click="moveItem('languages', index, -1)">↑</button><button type="button" :disabled="index === languages.length - 1" :aria-label="`下移语言 ${index + 1}`" title="下移" @click="moveItem('languages', index, 1)">↓</button><button type="button" :aria-label="`删除语言 ${index + 1}`" @click="removeSimpleItem('languages', index)">删除</button></div></div><div class="field-grid">
            <label>语言<input :value="item.name ?? item.language ?? ''" placeholder="例如：英语" @input="setSimpleItem('languages', index, 'name', ($event.target as HTMLInputElement).value)" /></label>
            <label>熟练程度<input :value="item.level ?? item.fluency ?? ''" placeholder="例如：专业工作沟通" @input="setSimpleItem('languages', index, 'level', ($event.target as HTMLInputElement).value)" /></label>
          </div></article>
        </fieldset>
        <div class="editor-note"><strong>高级编辑</strong><span>兴趣等少用扩展字段可通过标准 JSON 精确调整。</span></div>
        <label v-if="showSource" class="source-editor open"><span>简历源数据</span><textarea v-model="content" :rows="28" required spellcheck="false" /><small :class="sourceValid ? 'source-ok' : 'source-invalid'">{{ sourceValid ? 'JSON 格式有效' : 'JSON 格式有误，修复后才能保存' }}</small></label>
        <div v-if="undoRemoval" class="editor-undo" role="status"><span>已移除“{{ undoRemoval.label }}”</span><button type="button" @click="restoreRemoval">撤销</button></div>
        <div class="editor-save-dock"><span><strong>{{ dirty ? '有未保存修改' : '当前内容已同步' }}</strong><small>快捷键 Ctrl / Cmd + S</small></span><button class="btn-neon btn-primary" :disabled="saving || !sourceValid || !dirty">{{ saving ? '正在保存…' : '保存新版本' }}</button></div>
      </form>
      <aside class="preview-rail"><div class="preview-label"><span>{{ previewOnly ? '专注预览 · 保存后可在版本页导出 PDF' : `${templateName}样式 · 实时预览` }}</span><span :class="{ 'preview-warning': previewMayOverflow }">{{ previewMayOverflow ? '内容较多，可能超过一页' : `A4 / ${basics.name || '未命名'}` }}</span></div>
        <article class="resume-paper" :class="`template-${templateCode}`">
          <header class="paper-header"><h2 :class="{ 'paper-placeholder': !basics.name }">{{ basics.name || '你的姓名' }}</h2><p :class="{ 'paper-placeholder': !(basics.title || basics.position) }">{{ basics.title || basics.position || '目标岗位' }}</p><div :class="{ 'paper-placeholder': ![basics.phone, basics.email, basics.location].some(Boolean) }">{{ [basics.phone, basics.email, basics.location].filter(Boolean).join('  ·  ') || '电话 · 邮箱 · 城市' }}</div></header>
          <div v-if="!hasBodyContent" class="paper-empty-guide"><span>从左侧开始</span><h3>先写内容，再追求版式</h3><ol><li><strong>说明你是谁</strong><small>姓名、目标岗位和联系方式</small></li><li><strong>证明你做成过什么</strong><small>经历、项目和可量化成果</small></li><li><strong>匹配目标岗位</strong><small>专业技能与教育背景</small></li></ol></div>
          <section v-if="basics.summary"><h3>个人概要</h3><p>{{ basics.summary }}</p></section>
          <section v-if="work.length"><h3>工作经历</h3><div v-for="(item, index) in work" :key="index" class="paper-entry"><strong>{{ item.company || item.name || '公司名称' }}</strong><span>{{ item.position || item.role || '' }}</span><small>{{ item.startDate || '' }}{{ item.endDate ? ` — ${item.endDate}` : '' }}</small><p v-if="item.description">{{ item.description }}</p><ul v-if="item.highlights"><li v-for="(point, pointIndex) in item.highlights" :key="pointIndex">{{ point }}</li></ul></div></section>
          <section v-if="skills.length"><h3>专业技能</h3><div class="skill-chips"><span v-for="(skill, index) in skills" :key="index">{{ typeof skill === 'string' ? skill : skill.name || skill.keyword }}</span></div></section>
          <section v-if="projects.length"><h3>项目经历</h3><div v-for="(item, index) in projects" :key="index" class="paper-entry"><strong>{{ item.name || '项目名称' }}</strong><span>{{ item.role || item.position || '' }}</span><p v-if="item.description">{{ item.description }}</p><ul v-if="item.highlights"><li v-for="(point, pointIndex) in item.highlights" :key="pointIndex">{{ point }}</li></ul></div></section>
          <section v-if="education.length"><h3>教育经历</h3><div v-for="(item, index) in education" :key="index" class="paper-entry"><strong>{{ item.school || item.name }}</strong><span>{{ [item.degree, item.major || item.area].filter(Boolean).join(' · ') }}</span><small>{{ item.startDate || '' }}{{ item.endDate ? ` — ${item.endDate}` : '' }}</small></div></section>
          <section v-if="certificates.length"><h3>专业证书</h3><div v-for="(item, index) in certificates" :key="index" class="paper-entry compact"><strong>{{ item.name || '证书名称' }}</strong><span>{{ item.issuer || '' }}</span><small>{{ item.date || '' }}</small></div></section>
          <section v-if="languages.length"><h3>语言能力</h3><div class="skill-chips"><span v-for="(item, index) in languages" :key="index">{{ [item.name || item.language, item.level || item.fluency].filter(Boolean).join(' · ') }}</span></div></section>
        </article>
      </aside>
    </div>
  </section>
</template>
