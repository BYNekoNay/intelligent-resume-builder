import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

import { useLocale } from '@/i18n'
import {
  PARSER_SECTION_LABEL_KEYS,
  PARSER_WARNING_LABEL_KEYS,
} from '@/utils/parserLabels'

/**
 * 解析器视角（B4）的码表门禁。守两件事：
 *
 * 1. **前端码表 = 后端产出的码**：章节键读 `ParserViewSections.java`，告警码读
 *    `ResumeParserViewRenderer.java`。后端加一个告警码而前端没文案时，界面会显示一个
 *    裸码（`ITEM_MISSING_DATES`）——用户看不懂，且没有任何测试会报错。这里把它变成红灯。
 * 2. **每条文案在中英两套目录里都真实存在**：走真实的 `t()`（缺失时 `t` 会回退成 key 本身），
 *    而不是解析源码文本，避免"注释里写了也算存在"这类假绿。
 */

const SECTIONS_JAVA = fileURLToPath(
  new URL('../../../server/src/main/java/com/intelligentresume/ats/service/ParserViewSections.java', import.meta.url),
)
const RENDERER_JAVA = fileURLToPath(
  new URL('../../../server/src/main/java/com/intelligentresume/ats/service/ResumeParserViewRenderer.java', import.meta.url),
)

/** `new Spec("work", "WORK EXPERIENCE", ...)` 里的章节键。basics 单独声明，需补上。 */
function backendSectionCodes(): string[] {
  const source = readFileSync(SECTIONS_JAVA, 'utf8')
  const codes = [...source.matchAll(/new Spec\("([a-zA-Z]+)"/g)].map(match => match[1])
  const basics = /Spec BASICS = new Spec\("basics"/.test(source)
  return basics ? ['basics', ...codes] : codes
}

/** `warning("CODE", ...)` 里的告警码。 */
function backendWarningCodes(): string[] {
  const source = readFileSync(RENDERER_JAVA, 'utf8')
  return [...source.matchAll(/warning\("([A-Z_]+)"/g)].map(match => match[1])
}

describe('parserLabels', () => {
  it('章节码表与后端 ParserViewSections 完全一致', () => {
    const backend = backendSectionCodes()
    // 解析失效时必须报红，不能用空集合冒充"相等"
    expect(backend.length, '未解析到后端章节键 —— 锚点失配').toBeGreaterThan(10)
    expect(Object.keys(PARSER_SECTION_LABEL_KEYS).sort()).toEqual([...new Set(backend)].sort())
  })

  it('告警码表与后端渲染器抛出的码完全一致', () => {
    const backend = backendWarningCodes()
    expect(backend.length, '未解析到后端告警码 —— 锚点失配').toBeGreaterThan(8)
    expect(Object.keys(PARSER_WARNING_LABEL_KEYS).sort()).toEqual([...new Set(backend)].sort())
  })

  it('每条文案在中英两套目录里都存在', async () => {
    const { t } = useLocale()
    const keys = [
      ...Object.values(PARSER_SECTION_LABEL_KEYS),
      ...Object.values(PARSER_WARNING_LABEL_KEYS),
    ]
    // 中文是内联目录：走真实的 t()，缺失时 t 会回退成 key 本身
    for (const key of keys) {
      expect(t(key), `zh-CN 缺文案：${key}`).not.toBe(key)
    }
    // 英文目录直接用对象断言（不经过 setLocale）：node 环境没有 window.localStorage，
    // 而这里要验证的是"文案存在"，不是"语言切换流程"，两件事不该互相绑死。
    const enUS = (await import('@/i18n/locales/en-US')).default as Record<string, unknown>
    for (const key of keys) {
      const value = key.split('.').reduce<unknown>(
        (node, part) => (node && typeof node === 'object' ? (node as Record<string, unknown>)[part] : undefined),
        enUS,
      )
      expect(typeof value === 'string' && value.length > 0, `en-US 缺文案：${key}`).toBe(true)
    }
  })
})
