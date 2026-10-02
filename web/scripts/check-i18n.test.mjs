import test from 'node:test'
import assert from 'node:assert/strict'
import { collectErrorCodeRegistryKeys, collectRegistryTranslationKeys, collectStaticTranslationKeys, findDynamicKeyFailures, findLocaleKeyMismatches, findRuntimeLiterals, findVisibleLiterals, inspectCatalog } from './check-i18n.mjs'

test('rejects visible Chinese inside a structural tag line', () => {
  const source = '<template><button class="primary">保存</button></template>'
  assert.ok(findVisibleLiterals(source).length > 0)
})

test('rejects visible Chinese attributes and runtime messages', () => {
  assert.ok(findVisibleLiterals('<template><input placeholder="姓名" /></template>').length > 0)
  assert.ok(findRuntimeLiterals("error.value = '保存失败'").length > 0)
  assert.ok(findRuntimeLiterals("profileMessage.value = '保存成功'").length > 0)
})

test('ignores translation-like calls in source comments', () => {
  assert.deepEqual(collectStaticTranslationKeys("// t('missing.key')\nt('common.save')"), ['common.save'])
  assert.deepEqual(collectStaticTranslationKeys("const cdn = '//cdn'; t('missing.key')"), ['missing.key'])
  assert.deepEqual(findRuntimeLiterals("// profileMessage.value = '保存成功'"), [])
})

test('rejects duplicate locale roots', () => {
  const source = `const messages = {
    'zh-CN': { common: { save: '保存' } },
    'zh-CN': { common: { save: '保存' } },
    'en-US': { common: { save: 'Save' } },
  }`
  assert.deepEqual(inspectCatalog(source).duplicates, ['zh-CN'])
})

test('allows translated template text and source comments', () => {
  const source = `<script setup>// 中文说明\nconst label = t('common.save')</script><template><button :title="t('common.save')">{{ t('common.save') }}</button></template>`
  assert.deepEqual(findVisibleLiterals(source), [])
  assert.deepEqual(findRuntimeLiterals(source), [])
})

test('rejects hard-coded English in visible template and runtime message sinks', () => {
  assert.ok(findVisibleLiterals('<template><button>Save</button></template>').length > 0)
  assert.ok(findVisibleLiterals('<template><input placeholder="Full name" /></template>').length > 0)
  assert.ok(findVisibleLiterals(`<template><input :placeholder="'Full name'" /></template>`).length > 0)
  assert.ok(findVisibleLiterals(`<template><button :aria-label="failed ? 'Retry save' : t('common.save')" /></template>`).length > 0)
  assert.ok(findVisibleLiterals(`<template><p>{{ failed ? 'Save failed' : t('common.ready') }}</p></template>`).length > 0)
  assert.ok(findRuntimeLiterals("error.value = 'Save failed'").length > 0)
  assert.ok(findRuntimeLiterals("window.confirm('Leave without saving?')").length > 0)
})

test('allows translated bound visible attributes', () => {
  const source = `<template><input :placeholder="t('profile.fullName')" /><button :aria-label="t('common.save')" /></template>`
  assert.deepEqual(findVisibleLiterals(source), [])
})

test('allows domain constants, examples, dates, and internal expression keys', () => {
  const source = `<template><span>ATS</span><input placeholder="Amazon Web Services" /><p>{{ section === 'work' ? t('common.save') : '' }}</p><input placeholder="2024-06" /></template>`
  assert.deepEqual(findVisibleLiterals(source), [])
  assert.deepEqual(findRuntimeLiterals("status.value = 'RUNNING'"), [])
})

test('rejects locale catalogs with different key sets', () => {
  const source = `const messages = {
    'zh-CN': { common: { save: '保存' } },
    'en-US': { common: { save: 'Save', cancel: 'Cancel' } },
  }`
  const { locales } = inspectCatalog(source)
  assert.deepEqual(findLocaleKeyMismatches(locales, ['zh-CN', 'en-US']), [
    'zh-CN is missing catalog key common.cancel',
  ])
})

test('collects dynamic navigation registry translation descriptors', () => {
  assert.deepEqual(collectRegistryTranslationKeys(`
    const item = { labelKey: 'navGroups.resume.generate', descriptionKey: 'navGroups.resume.generateDesc' }
    const step = { stepKey: 'home.workflowResumeStep', titleKey: 'home.workflowResumeTitle', descKey: 'home.workflowResumeDesc' }
  `), [
    'navGroups.resume.generate',
    'navGroups.resume.generateDesc',
    'home.workflowResumeStep',
    'home.workflowResumeTitle',
    'home.workflowResumeDesc',
  ])
})

test('collects error-code registry keys and ignores non-registry lines', () => {
  const source = `export const ERROR_CODE_KEYS: Readonly<Record<number, string>> = {
  40001: 'errors.validation',
  40902: 'errors.versionArchived',
}
// 99999: 'errors.inComment',  ← 注释行行尾与真实键行相同，无行首锚的正则会把它解析进来`
  assert.deepEqual(collectErrorCodeRegistryKeys(source), ['errors.validation', 'errors.versionArchived'])
  assert.deepEqual(collectErrorCodeRegistryKeys('export const EMPTY = {}'), [])
})

test('dynamic key failures point at the missing locale and key, and refuse to idle on a broken registry', () => {
  const locales = new Map([
    ['zh-CN', new Set(['errors.conflict'])],
    ['en-US', new Set([])],
  ])
  assert.deepEqual(
    findDynamicKeyFailures(['errors.conflict'], locales, ['zh-CN', 'en-US']),
    ['missing en-US translation for errors.conflict（经动态 t() 调用消费，静态键校验覆盖不到）'],
  )
  assert.deepEqual(findDynamicKeyFailures(['errors.conflict'], locales, ['zh-CN']), [])
  // 解析失效（0 键）必须报错而不是静默通过 —— 「永远通过」的检查最危险
  assert.ok(findDynamicKeyFailures([], locales, ['zh-CN', 'en-US'], { minKeys: 10 })[0].includes('解析可能已失效'))
})
