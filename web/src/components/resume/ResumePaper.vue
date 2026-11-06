<!--
简历纸张渲染组件（名片的"纸"）：用户在编辑器里改内容，这里把 JSON 简历（ResumeDocument）
画成一张 A4 纸样的视觉稿，编辑区和导出预览共用这一份呈现，保证"所见即所得"。

props 契约（父组件传什么）：
- document     简历数据本体（JSON resume 标准结构，basics/work/education 等章节）
- templateCode 模板代号，只用来拼模板类名 `template-xxx`，交给全局 CSS 套不同皮肤
- layoutStyle  父组件算好的版式变量（字号、边距等 CSS 变量），本组件不算版式
- design       设计面板模式（缩小比例展示）；showEmptyGuide 空简历时显示引导步骤
易错点 / 特殊行为：
- document 的各章节先经 items() 归一成数组再渲染；字段缺失时整段不渲染（v-if），
  不用占位符撑高度——所以缺哪章纸面就少哪章，不会出现"空框"。
- 章节顺序来自 layout.sectionOrder（用户可拖动配置的章节顺序），sectionStyle 用 CSS
  order 排序；order 从 20 起步是刻意给个人概要固定 order:10 置顶留出位置（CSS order
  越小越靠前）。
- 日期优先 startDate/endDate，只有两者都为空才回退 period（与 pdf-service 的 classic
  模板一致，见 formatDateRange）。
- paperElement 通过 defineExpose 暴露（父组件拿真实 DOM 用的钩子），供父组件做分页测量。
-->
<script setup lang="ts">
import { computed, ref, type CSSProperties } from 'vue'
import { useLocale } from '@/i18n'
import type { ResumeDocument } from '@/types/resume'
import { DEFAULT_SECTION_ORDER, resolveSectionOrder } from '@/resume/sectionRegistry'

type ResumeItem = Record<string, any>

const props = withDefaults(defineProps<{
  document: ResumeDocument
  templateCode: string
  layoutStyle: CSSProperties
  design?: boolean
  showEmptyGuide?: boolean
}>(), {
  design: false,
  showEmptyGuide: false,
})

const { t } = useLocale()
const paperElement = ref<HTMLElement | null>(null)
defineExpose({ paperElement })

const doc = computed<Record<string, any>>(() => props.document as Record<string, any>)
const basics = computed<ResumeItem>(() => props.document.basics ?? {})
const contactLocation = computed(() => typeof basics.value.location === 'string'
  ? basics.value.location
  : basics.value.location?.city ?? '')
const contactParts = computed(() => [basics.value.phone, basics.value.email, contactLocation.value].filter(Boolean))
const objective = computed<ResumeItem>(() => props.document.objective ?? {})
// items(key) 生成器：把 doc[key] 归一成数组——后端给的章节要么不存在、要么就是数组，
// 归一后模板里每个 section 都能无脑 v-if="xxx.length"，不用写两种判空。
const items = (key: string) => computed<ResumeItem[]>(() => Array.isArray(doc.value[key]) ? doc.value[key] : [])
const links = items('links')
const work = items('work')
const volunteering = items('volunteering')
const skills = items('skills')
const projects = items('projects')
const education = items('education')
const courses = items('courses')
const certificates = items('certificates')
const publications = items('publications')
const awards = items('awards')
const languages = items('languages')
const customSections = items('customSections')

// 章节排序：20 起步是刻意的——个人概要固定 order:10，比所有章节都小，因此永远置顶。
// 未出现在 sectionOrder 里的键 indexOf 返回 -1，order 变成 20-1=19，会插到概要和首位章节之间，
// 所以 sectionOrder 必须覆盖所有会被渲染的章节键（由 resolveSectionOrder 兜底补全）。
const sectionOrder = computed(() => resolveSectionOrder(props.document.layout?.sectionOrder))
const sectionStyle = (key: string): CSSProperties => ({ order: 20 + sectionOrder.value.indexOf(key as (typeof sectionOrder.value)[number]) })
const hasBodyContent = computed(() => Boolean(basics.value.summary || objective.value.summary)
  || DEFAULT_SECTION_ORDER.some(key => Array.isArray(doc.value[key]) && doc.value[key].length > 0))
// 亮点条目可能是字符串/数字，也可能是 {text}/{value} 对象（旧数据或编辑器产出不一），
// 这里统一转成纯文本，避免对象被直接渲染成 [object Object]。
const highlightText = (point: unknown) => {
  if (typeof point === 'string' || typeof point === 'number') return String(point)
  if (point && typeof point === 'object') {
    const item = point as ResumeItem
    return String(item.text ?? item.value ?? '')
  }
  return ''
}
/**
 * 单条经历的日期范围，结构字段优先：startDate/endDate 任一存在就用它们拼「起 — 止」；
 * 旧数据里自由文本的 period 只在两者都缺时兜底。两套来源绝不同时出现。
 * 该规则与 pdf-service/src/templates/classic.js 的 dates() 保持一致，确保网页与 PDF 日期一致。
 */
const formatDateRange = (item: ResumeItem) => {
  const start = item.startDate
  const end = item.endDate
  if (start || end) return [start, end].filter(Boolean).join(' — ')
  return item.period ? String(item.period) : ''
}
</script>

<template>
  <article
    ref="paperElement"
    class="resume-paper"
    :class="[{ 'design-resume-paper': design }, `template-${templateCode}`]"
    :style="layoutStyle"
  >
    <header class="paper-header">
      <h2 :class="{ 'paper-placeholder': !basics.name }">{{ basics.name || t('resumeEditor.previewNamePlaceholder') }}</h2>
      <p :class="{ 'paper-placeholder': !(basics.title || basics.position) }">{{ basics.title || basics.position || t('resumeEditor.previewRolePlaceholder') }}</p>
      <div :class="{ 'paper-placeholder': !contactParts.length }">{{ contactParts.join('  ·  ') || t('resumeEditor.previewContactPlaceholder') }}</div>
    </header>
    <div v-if="showEmptyGuide && !hasBodyContent" class="paper-empty-guide">
      <span>{{ t('resumeEditor.previewEmptyEyebrow') }}</span><h3>{{ t('resumeEditor.previewEmptyTitle') }}</h3>
      <ol>
        <li><strong>{{ t('resumeEditor.emptyStepIdentity') }}</strong><small>{{ t('resumeEditor.emptyStepIdentityDescription') }}</small></li>
        <li><strong>{{ t('resumeEditor.emptyStepProof') }}</strong><small>{{ t('resumeEditor.emptyStepProofDescription') }}</small></li>
        <li><strong>{{ t('resumeEditor.emptyStepMatch') }}</strong><small>{{ t('resumeEditor.emptyStepMatchDescription') }}</small></li>
      </ol>
    </div>
    <section v-if="basics.summary" :style="{ order: 10 }"><h3>{{ t('resumeEditor.personalSummary') }}</h3><p>{{ basics.summary }}</p></section>
    <section v-if="objective.summary" :style="sectionStyle('objective')"><h3>{{ t('resumeEditor.objectiveLabel') }}</h3><p>{{ [objective.targetRole, objective.targetIndustry, objective.location].filter(Boolean).join(' · ') }}</p><p>{{ objective.summary }}</p></section>
    <section v-if="links.length" :style="sectionStyle('links')"><h3>{{ t('resumeEditor.linksLabel') }}</h3><div v-for="(item, index) in links" :key="index" class="paper-entry compact"><strong>{{ item.label }}</strong><span>{{ item.url }}</span></div></section>
    <section v-if="work.length" :style="sectionStyle('work')"><h3>{{ t('resumeEditor.workLabel') }}</h3><div v-for="(item, index) in work" :key="index" class="paper-entry"><strong>{{ item.company || item.name || t('resumeEditor.companyPlaceholder') }}</strong><span>{{ item.position || item.role || '' }}</span><small>{{ formatDateRange(item) }}</small><p v-if="item.description">{{ item.description }}</p><ul v-if="item.highlights"><li v-for="(point, pointIndex) in item.highlights" :key="pointIndex">{{ highlightText(point) }}</li></ul></div></section>
    <section v-if="volunteering.length" :style="sectionStyle('volunteering')"><h3>{{ t('resumeEditor.volunteeringLabel') }}</h3><div v-for="(item, index) in volunteering" :key="index" class="paper-entry"><strong>{{ item.organization }}</strong><span>{{ item.role }}</span><small>{{ formatDateRange(item) }}</small><p v-if="item.description">{{ item.description }}</p><ul v-if="item.highlights"><li v-for="(point, pointIndex) in item.highlights" :key="pointIndex">{{ highlightText(point) }}</li></ul></div></section>
    <section v-if="skills.length" :style="sectionStyle('skills')"><h3>{{ t('resumeEditor.skillsLabel') }}</h3><div class="skill-chips"><span v-for="(skill, index) in skills" :key="index">{{ typeof skill === 'string' ? skill : skill.name || skill.keyword }}</span></div></section>
    <section v-if="projects.length" :style="sectionStyle('projects')"><h3>{{ t('resumeEditor.projectsLabel') }}</h3><div v-for="(item, index) in projects" :key="index" class="paper-entry"><strong>{{ item.name || t('resumeEditor.projectPlaceholder') }}</strong><span>{{ item.role || item.position || '' }}</span><small>{{ formatDateRange(item) }}</small><p v-if="item.description">{{ item.description }}</p><ul v-if="item.highlights"><li v-for="(point, pointIndex) in item.highlights" :key="pointIndex">{{ highlightText(point) }}</li></ul></div></section>
    <section v-if="education.length" :style="sectionStyle('education')"><h3>{{ t('resumeEditor.educationLabel') }}</h3><div v-for="(item, index) in education" :key="index" class="paper-entry"><strong>{{ item.school || item.name }}</strong><span>{{ [item.degree, item.major || item.area].filter(Boolean).join(' · ') }}</span><small>{{ formatDateRange(item) }}</small></div></section>
    <section v-if="courses.length" :style="sectionStyle('courses')"><h3>{{ t('resumeEditor.coursesLabel') }}</h3><div v-for="(item, index) in courses" :key="index" class="paper-entry compact"><strong>{{ item.name }}</strong><span>{{ item.provider || '' }}</span><small>{{ item.date || '' }}</small><p v-if="item.description">{{ item.description }}</p></div></section>
    <section v-if="certificates.length" :style="sectionStyle('certificates')"><h3>{{ t('resumeEditor.certificatesLabel') }}</h3><div v-for="(item, index) in certificates" :key="index" class="paper-entry compact"><strong>{{ item.name || t('resumeEditor.certificatePlaceholder') }}</strong><span>{{ item.issuer || '' }}</span><small>{{ item.date || '' }}</small></div></section>
    <section v-if="publications.length" :style="sectionStyle('publications')"><h3>{{ t('resumeEditor.publicationsLabel') }}</h3><div v-for="(item, index) in publications" :key="index" class="paper-entry compact"><strong>{{ item.title }}</strong><span>{{ item.publisher || item.url || '' }}</span><small>{{ item.date || '' }}</small><p v-if="item.description">{{ item.description }}</p></div></section>
    <section v-if="awards.length" :style="sectionStyle('awards')"><h3>{{ t('resumeEditor.awardsLabel') }}</h3><div v-for="(item, index) in awards" :key="index" class="paper-entry compact"><strong>{{ item.name || item.title || t('resumeEditor.awardName') }}</strong><span>{{ item.issuer || item.organization || '' }}</span><small>{{ item.date || '' }}</small><p v-if="item.description">{{ item.description }}</p></div></section>
    <section v-if="languages.length" :style="sectionStyle('languages')"><h3>{{ t('resumeEditor.languagesLabel') }}</h3><div class="skill-chips"><span v-for="(item, index) in languages" :key="index">{{ [item.name || item.language, item.level || item.fluency].filter(Boolean).join(' · ') }}</span></div></section>
    <section v-for="(section, sectionIndex) in customSections" :key="`custom-${sectionIndex}`" :style="sectionStyle('customSections')"><h3>{{ section.title || t('resumeEditor.customSectionsLabel') }}</h3><div v-for="(item, entryIndex) in (section.entries ?? [])" :key="entryIndex" class="paper-entry"><strong>{{ item.name }}</strong><span>{{ [item.organization, item.role].filter(Boolean).join(' · ') }}</span><small>{{ formatDateRange(item) }}</small><p v-if="item.description">{{ item.description }}</p><ul v-if="item.highlights"><li v-for="(point, pointIndex) in item.highlights" :key="pointIndex">{{ highlightText(point) }}</li></ul></div></section>
  </article>
</template>
