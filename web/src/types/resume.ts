/**
 * Typed contract for the resume JSON document shared across
 * ResumeEditorView, ResumePaper, and the PDF rendering service.
 *
 * The shape mirrors the persisted JSON stored in resume_version.content_json
 * and consumed by pdf-service/src/templates/classic.js.
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
