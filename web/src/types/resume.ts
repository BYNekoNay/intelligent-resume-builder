/**
 * 简历 JSON 文档的类型契约：ResumeEditorView、ResumePaper（纸张渲染）与
 * pdf-service 的 classic.js 模板三方共用。
 *
 * 形状镜像 resume_version.content_json 的持久化内容——改这里的任何结构，
 * 必须同步 pdf-service 模板与后端写入侧，否则导出 PDF 会丢章节。
 */

export interface ResumeBasics {
  name?: string
  title?: string
  position?: string
  label?: string
  email?: string
  phone?: string
  location?: string | { city?: string }
  summary?: string
}

export interface ResumeObjective {
  targetRole?: string
  targetIndustry?: string
  location?: string
  summary?: string
}

export interface ResumeLink {
  label?: string
  name?: string
  url?: string
}

export interface ResumeHighlight {
  text?: string
  value?: string
}

export interface ResumeWorkItem {
  company?: string
  name?: string
  position?: string
  role?: string
  startDate?: string
  endDate?: string
  /**
   * Legacy free-form time range (e.g. "2021 - present").
   * Treated as a compatibility value: consumers display it only when
   * neither startDate nor endDate is present. Never parsed or rewritten.
   */
  period?: string
  description?: string
  highlights?: (string | ResumeHighlight)[]
}

export interface ResumeVolunteeringItem {
  organization?: string
  name?: string
  role?: string
  position?: string
  startDate?: string
  endDate?: string
  /** Legacy free-form time range; see ResumeWorkItem.period. */
  period?: string
  description?: string
  highlights?: (string | ResumeHighlight)[]
}

export interface ResumeSkillItem {
  name?: string
  keyword?: string
}

export interface ResumeProjectItem {
  name?: string
  role?: string
  position?: string
  startDate?: string
  endDate?: string
  /** Legacy free-form time range; see ResumeWorkItem.period. */
  period?: string
  description?: string
  highlights?: (string | ResumeHighlight)[]
}

export interface ResumeEducationItem {
  school?: string
  name?: string
  degree?: string
  major?: string
  area?: string
  startDate?: string
  endDate?: string
  /** Legacy free-form time range; see ResumeWorkItem.period. */
  period?: string
}

export interface ResumeCourseItem {
  name?: string
  provider?: string
  date?: string
  description?: string
}

export interface ResumeCertificateItem {
  name?: string
  issuer?: string
  date?: string
}

export interface ResumePublicationItem {
  title?: string
  name?: string
  publisher?: string
  url?: string
  date?: string
  description?: string
}

export interface ResumeAwardItem {
  name?: string
  title?: string
  issuer?: string
  organization?: string
  date?: string
  description?: string
}

export interface ResumeLanguageItem {
  name?: string
  language?: string
  level?: string
  fluency?: string
}

export interface ResumeCustomEntry {
  name?: string
  organization?: string
  role?: string
  startDate?: string
  endDate?: string
  /** Legacy free-form time range; see ResumeWorkItem.period. */
  period?: string
  description?: string
  highlights?: (string | ResumeHighlight)[]
}

export interface ResumeCustomSection {
  title?: string
  entries?: ResumeCustomEntry[]
}

export interface ResumeLayout {
  sectionOrder?: string[]
  fontFamily?: string
  bodyFontSize?: number
  headingFontSize?: number
  lineHeight?: number
  sectionSpacing?: number
  entrySpacing?: number
  pagePadding?: number
}

export interface ResumeTemplate {
  code?: string
}

/**
 * The full resume document as persisted in resume_version.content_json.
 * Array sections default to [] when absent; object sections default to {}.
 */
export interface ResumeDocument {
  basics?: ResumeBasics
  objective?: ResumeObjective
  links?: ResumeLink[]
  work?: ResumeWorkItem[]
  volunteering?: ResumeVolunteeringItem[]
  skills?: (ResumeSkillItem | string)[]
  projects?: ResumeProjectItem[]
  education?: ResumeEducationItem[]
  courses?: ResumeCourseItem[]
  certificates?: ResumeCertificateItem[]
  publications?: ResumePublicationItem[]
  awards?: ResumeAwardItem[]
  languages?: ResumeLanguageItem[]
  customSections?: ResumeCustomSection[]
  layout?: ResumeLayout
  template?: ResumeTemplate
}
