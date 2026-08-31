import { SECTION_KEYS, type SectionKey } from '@/resume/sectionRegistry'

/**
 * 纯前端简历版本 diff（无第三方依赖）。
 *
 * <p>输入两侧 resumeJson，按 14 章节组织输出 SectionDiff[]。
 * 稳定 key 对齐规则（设计共享约定第 7 条）：
 *   数组条目按 id（若存在）→ 复合键（标题/名称 + 时间/起止时间）→ 兜底数组下标；
 * 字段比较用 JSON 规范序列化字符串判等；
 * 章节级：UNCHANGED / ADDED / REMOVED / CHANGED；
 * 字段级：ADDED(绿) / REMOVED(红) / MODIFIED(黄)。
 */

export type SectionChangeType = 'UNCHANGED' | 'ADDED' | 'REMOVED' | 'CHANGED'
export type FieldChangeType = 'ADDED' | 'REMOVED' | 'MODIFIED'

export interface FieldDiff {
  key: string
  type: FieldChangeType
  baseValue: unknown
  compareValue: unknown
}

export interface EntryDiff {
  key: string
  type: SectionChangeType
  baseEntry: Record<string, unknown> | null
  compareEntry: Record<string, unknown> | null
  fields: FieldDiff[]
}

export interface SectionDiff {
  sectionKey: SectionKey
  type: SectionChangeType
  base: unknown
  compare: unknown
  entries: EntryDiff[]
  fields: FieldDiff[]
}

type JsonObject = Record<string, unknown>

/** JSON 规范序列化：键排序后判等。 */
function canonical(value: unknown): string {
  return JSON.stringify(sortValue(value))
}

function sortValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortValue)
  if (value !== null && typeof value === 'object') {
    const record: JsonObject = {}
    for (const key of Object.keys(value as JsonObject).sort()) {
      record[key] = sortValue((value as JsonObject)[key])
    }
    return record
  }
  return value
}

function isBlank(value: unknown): boolean {
  if (value === null || value === undefined) return true
  if (typeof value === 'string') return value.trim() === ''
  if (Array.isArray(value)) return value.length === 0
  if (typeof value === 'object') return Object.keys(value as JsonObject).length === 0
  return false
}

function stringValue(value: unknown): string {
  if (value === null || value === undefined) return ''
  return String(value).trim()
}

/** 稳定 key：id → 复合键（标题/名称 + 时间）→ 兜底数组下标。 */
export function stableEntryKey(entry: JsonObject, index: number): string {
  if (entry.id !== undefined && entry.id !== null && String(entry.id) !== '') {
    return `id:${entry.id}`
  }
  const title = firstPresent(entry, ['title', 'name', 'company', 'institution', 'course', 'certificate', 'language'])
  const subtitle = firstPresent(entry, ['company', 'institution', 'issuer', 'level', 'provider', 'publisher'])
  const start = firstPresent(entry, ['startDate', 'date', 'period'])
  const end = firstPresent(entry, ['endDate'])
  if (title !== '' || start !== '') {
    return `k:${title}|${subtitle}|${start}|${end}`
  }
  return `i:${index}`
}

function firstPresent(entry: JsonObject, keys: string[]): string {
  for (const key of keys) {
    const value = entry[key]
    if (value !== undefined && value !== null && String(value).trim() !== '') {
      return String(value).trim()
    }
  }
  return ''
}

function isJsonObject(value: unknown): value is JsonObject {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function isJsonArray(value: unknown): value is unknown[] {
  return Array.isArray(value)
}

/** 对象章节字段级 diff。 */
function diffFields(baseObj: JsonObject | null, compareObj: JsonObject | null): FieldDiff[] {
  const keys = new Set<string>([
    ...(baseObj ? Object.keys(baseObj) : []),
    ...(compareObj ? Object.keys(compareObj) : []),
  ])
  const fields: FieldDiff[] = []
  for (const key of Array.from(keys).sort()) {
    const baseValue = baseObj ? baseObj[key] : undefined
    const compareValue = compareObj ? compareObj[key] : undefined
    if (baseValue === undefined || isBlank(baseValue)) {
      if (compareValue !== undefined && !isBlank(compareValue)) {
        fields.push({ key, type: 'ADDED', baseValue: undefined, compareValue })
      }
      continue
    }
    if (compareValue === undefined || isBlank(compareValue)) {
      fields.push({ key, type: 'REMOVED', baseValue, compareValue: undefined })
      continue
    }
    if (canonical(baseValue) !== canonical(compareValue)) {
      fields.push({ key, type: 'MODIFIED', baseValue, compareValue })
    }
  }
  return fields
}

/** 数组章节条目级 diff（稳定 key 对齐）。 */
function diffEntries(baseArr: unknown[] | null, compareArr: unknown[] | null): EntryDiff[] {
  const baseList = (baseArr ?? []).filter(isJsonObject)
  const compareList = (compareArr ?? []).filter(isJsonObject)

  const baseByKey = new Map<string, JsonObject>()
  const usedKeys = new Set<string>()
  baseList.forEach((entry, index) => {
    let key = stableEntryKey(entry, index)
    let suffix = 2
    while (baseByKey.has(key)) key = `${stableEntryKey(entry, index)}#${suffix++}`
    baseByKey.set(key, entry)
    usedKeys.add(key)
  })
  const compareByKey = new Map<string, JsonObject>()
  compareList.forEach((entry, index) => {
    let key = stableEntryKey(entry, index)
    let suffix = 2
    while (compareByKey.has(key)) key = `${stableEntryKey(entry, index)}#${suffix++}`
    compareByKey.set(key, entry)
  })

  const allKeys = new Set<string>([...baseByKey.keys(), ...compareByKey.keys()])
  const entries: EntryDiff[] = []
  for (const key of Array.from(allKeys).sort()) {
    const baseEntry = baseByKey.get(key) ?? null
    const compareEntry = compareByKey.get(key) ?? null
    let type: SectionChangeType
    if (baseEntry && !compareEntry) type = 'REMOVED'
    else if (!baseEntry && compareEntry) type = 'ADDED'
    else if (baseEntry && compareEntry && canonical(baseEntry) === canonical(compareEntry)) type = 'UNCHANGED'
    else type = 'CHANGED'
    entries.push({
      key,
      type,
      baseEntry,
      compareEntry,
      fields: baseEntry && compareEntry ? diffFields(baseEntry, compareEntry) : [],
    })
  }
  return entries
}

function sectionType(base: unknown, compare: unknown): SectionChangeType {
  const baseBlank = isBlank(base)
  const compareBlank = isBlank(compare)
  if (baseBlank && compareBlank) return 'UNCHANGED'
  if (baseBlank && !compareBlank) return 'ADDED'
  if (!baseBlank && compareBlank) return 'REMOVED'
  if (canonical(base) === canonical(compare)) return 'UNCHANGED'
  return 'CHANGED'
}

/**
 * 计算两个简历 JSON 的章节级 diff。
 */
export function diffResumeVersions(baseJson: Record<string, unknown>, compareJson: Record<string, unknown>): SectionDiff[] {
  return SECTION_KEYS.map((sectionKey) => {
    const base = baseJson[sectionKey]
    const compare = compareJson[sectionKey]

    if (isJsonArray(base) || isJsonArray(compare)) {
      const entries = diffEntries(
        isJsonArray(base) ? base : null,
        isJsonArray(compare) ? compare : null,
      )
      return {
        sectionKey,
        type: sectionType(base, compare),
        base,
        compare,
        entries,
        fields: [],
      }
    }

    const fields = diffFields(
      isJsonObject(base) ? base : null,
      isJsonObject(compare) ? compare : null,
    )
    return {
      sectionKey,
      type: sectionType(base, compare),
      base,
      compare,
      entries: [],
      fields,
    }
  })
}

/** 差异摘要：有变化章节数、增/删/改条目数。 */
export function summarizeDiff(diffs: SectionDiff[]): { changedSections: number; addedItems: number; removedItems: number; modifiedItems: number } {
  let changedSections = 0
  let addedItems = 0
  let removedItems = 0
  let modifiedItems = 0
  for (const section of diffs) {
    if (section.type !== 'UNCHANGED') changedSections += 1
    if (section.entries.length > 0) {
      for (const entry of section.entries) {
        if (entry.type === 'ADDED') addedItems += 1
        else if (entry.type === 'REMOVED') removedItems += 1
        else if (entry.type === 'CHANGED') modifiedItems += 1
      }
    } else {
      for (const field of section.fields) {
        if (field.type === 'ADDED') addedItems += 1
        else if (field.type === 'REMOVED') removedItems += 1
        else modifiedItems += 1
      }
    }
  }
  return { changedSections, addedItems, removedItems, modifiedItems }
}
