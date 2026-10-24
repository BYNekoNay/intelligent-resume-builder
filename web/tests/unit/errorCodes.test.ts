import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'
import { ERROR_CODE_KEYS, extractErrorCode } from '@/utils/errorCodes'

/**
 * 错误码表：本文件守护两件事。
 *
 * 1. `extractErrorCode` 的取值边界（网络错 / 非信封 / 非法码 → null）；
 * 2. **错误码表与后端 `ErrorCode` 枚举同步** —— 这条是真正的承重点。
 *    原来有一份 `web/scripts/errorCodes.test.mjs` 做同样的断言，但它**没有被任何 script 或 CI 调用**，
 *    于是后端加码、前端表加码，断言停在 11 条而实际已有 13 条 —— 测试红了整整一段时间没人知道。
 *    现在把它迁到这里并纳入 CI（`npm run test:unit`），同时直接读后端源码对账，
 *    以后"后端加码忘改前端 / 前端加码忘改后端"都会立刻变红。
 */

const CODE_ENUM = fileURLToPath(
  new URL('../../../server/src/main/java/com/intelligentresume/common/error/ErrorCode.java', import.meta.url),
)

function backendCodes(): number[] {
  const source = readFileSync(CODE_ENUM, 'utf8')
  // ErrorCode 的枚举常量形如 `VALIDATION(40001, "…")`，只抓「数字开头 + 逗号」的那一列
  const matches = source.matchAll(/\(\s*(\d{5})\s*,/g)
  return [...matches].map(match => Number(match[1]))
}

describe('errorCodes', () => {
  it('前端错误码表与后端 ErrorCode 枚举完全一致', () => {
    const backend = backendCodes()
    expect(backend.length).toBeGreaterThan(10)
    expect(Object.keys(ERROR_CODE_KEYS).map(Number).sort()).toEqual([...new Set(backend)].sort())
  })

  it('每个码都映射到 errors.* 文案键', () => {
    for (const key of Object.values(ERROR_CODE_KEYS)) {
      expect(key).toMatch(/^errors\./)
    }
  })

  it('从 axios 错误中提取业务码', () => {
    expect(extractErrorCode({ response: { data: { code: 40901 } } })).toBe(40901)
  })

  it('提取不到时返回 null（网络错误 / 非信封 / 非法码）', () => {
    expect(extractErrorCode(null)).toBeNull()
    expect(extractErrorCode(undefined)).toBeNull()
    expect(extractErrorCode('500')).toBeNull()
    expect(extractErrorCode({})).toBeNull()
    expect(extractErrorCode({ response: {} })).toBeNull()
    expect(extractErrorCode({ response: { data: {} } })).toBeNull()
    // 字符串码不算：后端信封里 code 是数字，字符串多半是被代理改写过
    expect(extractErrorCode({ response: { data: { code: '40901' } } })).toBeNull()
    expect(extractErrorCode({ response: { data: { code: Number.NaN } } })).toBeNull()
    expect(extractErrorCode({ response: { data: { code: Number.POSITIVE_INFINITY } } })).toBeNull()
  })
})
