/**
 * 解析器视角（B4）的码 → 文案映射。
 *
 * 为什么要单独一张表而不是在模板里拼 `t('atsParser.section.' + code)`：
 * 1. 拼字符串的 key 无法被 `check-i18n.mjs` 的静态扫描覆盖，漏文案不会有人发现；
 * 2. 这张表可以被单测直接对拍**后端产出的码**（`ResumeParserViewRenderer` 的告警码、
 *    `ParserViewSections` 的章节键），从而把「后端加了新码、前端没文案」变成红灯。
 *
 * 与 `utils/errorCodes.ts` 同一套路：码表是契约，不是实现细节。
 */

/** 章节键 → 文案键。键集合必须等于后端 `ParserViewSections.SECTION_KEYS`。 */
export const PARSER_SECTION_LABEL_KEYS = {
  basics: 'atsParser.section.basics',
  objective: 'atsParser.section.objective',
  links: 'atsParser.section.links',
  work: 'atsParser.section.work',
  volunteering: 'atsParser.section.volunteering',
  skills: 'atsParser.section.skills',
  projects: 'atsParser.section.projects',
  education: 'atsParser.section.education',
  courses: 'atsParser.section.courses',
  certificates: 'atsParser.section.certificates',
  publications: 'atsParser.section.publications',
  awards: 'atsParser.section.awards',
  languages: 'atsParser.section.languages',
  customSections: 'atsParser.section.customSections',
} as const

/** 告警码 → 文案键。键集合必须等于后端渲染器抛出的告警码。 */
export const PARSER_WARNING_LABEL_KEYS = {
  MISSING_NAME: 'atsParser.warning.missingName',
  MISSING_EMAIL: 'atsParser.warning.missingEmail',
  INVALID_EMAIL: 'atsParser.warning.invalidEmail',
  MISSING_PHONE: 'atsParser.warning.missingPhone',
  INVALID_PHONE: 'atsParser.warning.invalidPhone',
  MISSING_LOCATION: 'atsParser.warning.missingLocation',
  ITEM_MISSING_DATES: 'atsParser.warning.itemMissingDates',
  UNKNOWN_SECTION: 'atsParser.warning.unknownSection',
  NO_EXPERIENCE: 'atsParser.warning.noExperience',
  NO_SKILLS: 'atsParser.warning.noSkills',
  NO_EDUCATION: 'atsParser.warning.noEducation',
} as const

export type ParserSectionCode = keyof typeof PARSER_SECTION_LABEL_KEYS
export type ParserWarningCode = keyof typeof PARSER_WARNING_LABEL_KEYS

/** 未知章节码（后端版本更新时可能出现）返回 null，由调用方决定如何降级。 */
export function parserSectionLabelKey(code: string): string | null {
  return PARSER_SECTION_LABEL_KEYS[code as ParserSectionCode] ?? null
}

export function parserWarningLabelKey(code: string): string | null {
  return PARSER_WARNING_LABEL_KEYS[code as ParserWarningCode] ?? null
}
