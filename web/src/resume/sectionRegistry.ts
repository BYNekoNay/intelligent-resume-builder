/**
 * Single source of truth for resume section keys, default ordering,
 * and section metadata used by editor navigation and ResumePaper rendering.
 *
 * Both ResumeEditorView and ResumePaper import from here so that
 * adding or reordering a section only requires a change in this file.
 */

/** All recognized section keys in the resume document. */
export const SECTION_KEYS = [
  'basics',
  'objective',
  'links',
  'work',
  'volunteering',
  'skills',
  'projects',
  'education',
  'courses',
  'certificates',
  'publications',
  'awards',
  'languages',
  'customSections',
] as const

export type SectionKey = (typeof SECTION_KEYS)[number]

const ATS_SECTION_ALIASES: Record<string, SectionKey> = {
  basics: 'basics',
  summary: 'basics',
  profile: 'basics',
  'personal summary': 'basics',
  'professional summary': 'basics',
  '个人信息': 'basics',
  '个人概要': 'basics',
  objective: 'objective',
  'career objective': 'objective',
  'target role': 'objective',
  '求职目标': 'objective',
  links: 'links',
  profiles: 'links',
  '个人链接': 'links',
  work: 'work',
  experience: 'work',
  'work experience': 'work',
  'professional experience': 'work',
  employment: 'work',
  '工作经历': 'work',
  '工作经验': 'work',
  volunteering: 'volunteering',
  volunteer: 'volunteering',
  'volunteer experience': 'volunteering',
  '志愿经历': 'volunteering',
  skills: 'skills',
  skill: 'skills',
  'technical skills': 'skills',
  '技能': 'skills',
  '专业技能': 'skills',
  projects: 'projects',
  project: 'projects',
  'project experience': 'projects',
  '项目经历': 'projects',
  education: 'education',
  academic: 'education',
  '教育背景': 'education',
  courses: 'courses',
  course: 'courses',
  training: 'courses',
  '课程培训': 'courses',
  certificates: 'certificates',
  certificate: 'certificates',
  certifications: 'certificates',
  '专业证书': 'certificates',
  publications: 'publications',
  publication: 'publications',
  research: 'publications',
  '研究成果': 'publications',
  awards: 'awards',
  award: 'awards',
  honors: 'awards',
  '奖项荣誉': 'awards',
  languages: 'languages',
  language: 'languages',
  '语言能力': 'languages',
  customsections: 'customSections',
  'custom sections': 'customSections',
  '自定义模块': 'customSections',
}

/** Map explicit ATS section identifiers to editor keys; generic prose stays unmapped. */
export function mapAtsSection(value: unknown): SectionKey | null {
  if (typeof value !== 'string') return null
  const normalized = value.trim().toLocaleLowerCase().replace(/[_-]+/g, ' ').replace(/\s+/g, ' ')
  return ATS_SECTION_ALIASES[normalized] ?? null
}

/** Sections that appear in the body (everything except the header basics block). */
export const CONTENT_SECTION_KEYS = SECTION_KEYS.filter(
  (key): key is Exclude<SectionKey, 'basics'> => key !== 'basics',
)

export type ContentSectionKey = (typeof CONTENT_SECTION_KEYS)[number]

/**
 * Default rendering order for body sections.
 * `basics` is always rendered as the paper header and is not part of this list.
 * This order matches pdf-service/src/templates/classic.js `defaultOrder`.
 */
export const DEFAULT_SECTION_ORDER: ContentSectionKey[] = [...CONTENT_SECTION_KEYS]

/**
 * Resolve the effective section order from a saved `layout.sectionOrder` array.
 * Unknown keys are dropped; missing known keys are appended in default order.
 * This logic is shared between ResumePaper (rendering) and ResumeEditorView (navigation).
 */
export function resolveSectionOrder(saved: unknown): ContentSectionKey[] {
  const valid = Array.isArray(saved)
    ? saved.filter((key): key is ContentSectionKey =>
        typeof key === 'string' && (DEFAULT_SECTION_ORDER as readonly string[]).includes(key),
      )
    : []
  const seen = new Set(valid)
  return [...valid, ...DEFAULT_SECTION_ORDER.filter((key) => !seen.has(key))]
}
