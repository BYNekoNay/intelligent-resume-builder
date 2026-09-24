#!/usr/bin/env node
/**
 * 草稿字段展示映射的静态门禁。
 *
 * 背景（2026-09-24 真实浏览器测试发现）：草稿确认页把**内部英文键名与裸枚举**直接上屏 ——
 * 实测出现 `items`、`category`、`entries` 与熟练度裸枚举 `EXPERT`。
 * 根因是 `DraftContentFields.vue` 的字段标签表漏配，未命中时回退为"驼峰拆词 + 原样展示"。
 *
 * 为什么用静态检查而不是 e2e：这是**映射完整性问题**，与渲染无关。
 * 走浏览器验证单个用例需数分钟且脆弱；静态检查毫秒级、可进 build 门禁、覆盖同样完整。
 *
 * 检查项：
 *  1. 草稿 schema 的全部字段都有中文标签（漏配即失败 —— 这正是当初的缺陷）
 *  2. 字段标签引用的 i18n 键在中英两种语言下都存在且非空
 *  3. 中文标签确实被翻译过（不等于字段名本身、且含中日韩字符）
 *  4. 枚举值字典同样满足 2、3（保证 EXPERT 不会裸上屏）
 */

import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const COMPONENT = join(here, '..', 'src', 'components', 'DraftContentFields.vue')
const I18N = join(here, '..', 'src', 'i18n', 'index.ts')

/**
 * 草稿 schema 中会出现在确认页的字段全集。
 * 来源：`JobGenerationPromptBuilder` 的 "Example output structure" + JSON Resume 常用字段。
 * schema 演进时需同步扩展本列表 —— 漏加会让新字段绕过门禁，故改动该列表应成对进行。
 */
export const SCHEMA_FIELDS = [
  // basics / objective
  'name', 'title', 'label', 'email', 'phone', 'location', 'website', 'summary',
  // work / projects / education
  'company', 'position', 'role', 'school', 'degree', 'major',
  'startDate', 'endDate', 'period', 'description', 'highlights',
  // skills
  'category', 'items', 'level', 'proficiency', 'keywords',
  // certificates / courses / publications / volunteering
  'issuer', 'credentialId', 'url', 'date', 'organization', 'provider', 'publisher', 'duration',
]

/** 从对象字面量里抽取 `键: '值'` 映射（用于源码静态解析，不做完整 TS 解析）。
 *  注意：字段表是**一行多个键**（`name: '...', title: '...'`），故不能要求键在行首。 */
export function parseStringMap(source, marker) {
  const start = source.indexOf(marker)
  if (start === -1) throw new Error(`未找到 ${marker}`)
  const open = source.indexOf('{', start)
  const close = source.indexOf('\n}', open)
  if (open === -1 || close === -1) throw new Error(`${marker} 的对象字面量不完整`)
  const body = source.slice(open + 1, close)
  const map = {}
  for (const match of body.matchAll(/([A-Za-z_$][\w$]*)\s*:\s*'([^']+)'/g)) {
    map[match[1]] = match[2]
  }
  return map
}

/** 抽取指定 locale 下 draftFields 的键值对（不解析 TS，按出现顺序取第 index 个）。 */
export function parseDraftFields(source, index) {
  let cursor = source.indexOf('draftFields: {', 0)
  for (let found = 0; found < index && cursor !== -1; found += 1) {
    cursor = source.indexOf('draftFields: {', cursor + 1)
  }
  if (cursor === -1) throw new Error(`未找到第 ${index + 1} 个 draftFields 块`)
  const close = source.indexOf('\n    },', cursor)
  const body = source.slice(cursor, close === -1 ? undefined : close)
  const map = {}
  for (const match of body.matchAll(/([A-Za-z_$][\w$]*)\s*:\s*'([^']*)'/g)) {
    map[match[1]] = match[2]
  }
  return map
}

const CJK = /[\u4e00-\u9fff]/

export function audit({ component, i18n }) {
  const fieldLabels = parseStringMap(component, 'const FIELD_LABEL_KEYS')
  const valueLabels = parseStringMap(component, 'const VALUE_LABEL_KEYS')
  const zh = parseDraftFields(i18n, 0)
  const en = parseDraftFields(i18n, 1)
  const problems = []

  // 1. schema 字段必须全部有中文标签
  for (const field of SCHEMA_FIELDS) {
    if (!fieldLabels[field]) problems.push(`schema 字段 "${field}" 缺少标签映射（会以原始英文键上屏）`)
  }

  // 2. 引用的 i18n 键必须在中英两种语言下都存在且非空
  const referenced = { ...fieldLabels, ...valueLabels }
  for (const [key, i18nKey] of Object.entries(referenced)) {
    const shortKey = i18nKey.replace(/^draftFields\./, '')
    for (const [locale, table] of [['zh', zh], ['en', en]]) {
      const text = table[shortKey]
      if (!text) problems.push(`${key} 引用的键 draftFields.${shortKey} 在 ${locale} 下缺失或为空`)
    }
  }

  // 3. 中文标签必须真的翻译过（中文表里的值必须含中文字符）
  //    注意：不能断言"标签值 != 字段名" —— 标签值是 i18n 键（如 draftFields.name），
  //    与字段名天然不同，那条断言永远成立、没有意义。
  for (const field of Object.keys(fieldLabels)) {
    const i18nKey = fieldLabels[field].replace(/^draftFields\./, '')
    const text = zh[i18nKey]
    if (text && !CJK.test(text)) {
      problems.push(`字段 ${field} 的中文标签 "${text}" 不含中文，疑似中文表里填了英文`)
    }
  }

  // 4. 枚举值必须中文化（EXPERT 曾裸上屏）
  for (const [enumValue, i18nKey] of Object.entries(valueLabels)) {
    const text = zh[i18nKey.replace(/^draftFields\./, '')]
    if (text && !CJK.test(text)) problems.push(`枚举 ${enumValue} 的中文标签 "${text}" 不含中文`)
  }

  return { problems, fieldLabelCount: Object.keys(fieldLabels).length, valueLabelCount: Object.keys(valueLabels).length }
}

function main() {
  const result = audit({
    component: readFileSync(COMPONENT, 'utf8'),
    i18n: readFileSync(I18N, 'utf8'),
  })
  if (result.problems.length) {
    console.error('✗ 草稿字段映射门禁未通过：')
    for (const problem of result.problems) console.error(`   - ${problem}`)
    process.exit(1)
  }
  console.log(`draft field guard passed: ${result.fieldLabelCount} 个字段标签、`
    + `${result.valueLabelCount} 个枚举值，覆盖 ${SCHEMA_FIELDS.length} 个 schema 字段`)
}

if (process.argv[1] && process.argv[1].endsWith('check-draft-fields.mjs')) main()
