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
