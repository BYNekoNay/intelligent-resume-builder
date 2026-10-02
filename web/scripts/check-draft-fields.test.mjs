/**
 * `check-draft-fields.mjs` 的自测：真实文件必须通过，且各类漏配必须被拦住。
 * 用 node --test 运行（与 check:i18n 同一范式，无需额外依赖）。
 */
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'
import { audit, parseStringMap } from './check-draft-fields.mjs'

const here = dirname(fileURLToPath(import.meta.url))
const realComponent = readFileSync(join(here, '..', 'src', 'components', 'DraftContentFields.vue'), 'utf8')
const realI18n = readFileSync(join(here, '..', 'src', 'i18n', 'index.ts'), 'utf8')

test('真实文件通过门禁', () => {
  const result = audit({ component: realComponent, i18n: realI18n })
  assert.deepEqual(result.problems, [], '不应有问题：' + result.problems.join('; '))
})

test('schema 字段漏配标签会被拦住（这正是当初的缺陷）', () => {
  // 模拟曾经的缺陷：删掉 category 的映射
  const broken = realComponent.replace("category: 'draftFields.category', ", '')
  const result = audit({ component: broken, i18n: realI18n })
  assert.ok(result.problems.some(p => p.includes('"category"')), '应报告 category 缺少映射')
})

test('i18n 键在英文缺失会被拦住', () => {
  const broken = realI18n.replace(/category: 'Category',/, '')
  const result = audit({ component: realComponent, i18n: broken })
  assert.ok(result.problems.some(p => p.includes('在 en 下缺失')), '应报告英文缺失')
})

test('中文表里填了英文会被拦住', () => {
  // 把 zh 的 category 标签改成英文
  const broken = realI18n.replace("category: '类别',", "category: 'Category',")
  const result = audit({ component: realComponent, i18n: broken })
  assert.ok(result.problems.some(p => p.includes('不含中文')), '应报告中文标签未翻译')
})

test('枚举值未中文化会被拦住（EXPERT 曾裸上屏）', () => {
  // 把 zh 的 EXPERT 标签改成英文，模拟"加了枚举映射但中文表没翻译"
  const broken = realI18n.replace("valueExpert: '精通',", "valueExpert: 'Expert',")
  const result = audit({ component: realComponent, i18n: broken })
  assert.ok(result.problems.some(p => p.includes('枚举 EXPERT')), '应报告枚举未中文化')
})

test('注释里的键值不得被解析进字段表（2026-10-02 抽查实证的注释污染误报）', () => {
  const source = `const FIELD_LABEL_KEYS: Record<string, string> = {
  name: 'draftFields.name',
  // ghostAuditField: 'draftFields.ghostAudit',
  /* ghostBlock: 'draftFields.ghostBlock', */
  title: 'draftFields.title',
}`
  const map = parseStringMap(source, 'const FIELD_LABEL_KEYS')
  assert.deepEqual(Object.keys(map).sort(), ['name', 'title'])
  // 值内的 //（如 URL）不得被误判为行注释
  const withUrl = `const FIELD_LABEL_KEYS: Record<string, string> = {
  website: 'draftFields.website',
}`
  assert.equal(parseStringMap(withUrl, 'const FIELD_LABEL_KEYS').website, 'draftFields.website')
})
