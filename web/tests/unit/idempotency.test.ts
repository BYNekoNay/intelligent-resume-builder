import { afterEach, describe, expect, it, vi } from 'vitest'
import { newIdempotencyKey } from '@/utils/idempotency'

/**
 * 幂等键的三档降级。
 *
 * 这不是"锦上添花"的测试：`crypto.randomUUID()` 只在安全上下文可用，
 * 用 `http://<公网 IP>:端口` 打开部署时它是 `undefined`，直接调用会在请求发出**之前**抛错 ——
 * 表现为点了按钮只弹一句通用错误、接口从未被调用。三档都必须真的走通。
 */

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('newIdempotencyKey', () => {
  it('优先用 randomUUID', () => {
    vi.stubGlobal('crypto', { randomUUID: () => '11111111-2222-4333-8444-555555555555' })
    expect(newIdempotencyKey()).toBe('11111111-2222-4333-8444-555555555555')
  })

  it('没有 randomUUID 时用 getRandomValues 生成合法 v4 形态', () => {
    vi.stubGlobal('crypto', {
      getRandomValues: (bytes: Uint8Array) => {
        for (let i = 0; i < bytes.length; i += 1) bytes[i] = i
        return bytes
      },
    })
    const key = newIdempotencyKey()
    // 版本位/变体位必须被打上，否则日志里无法与真实 UUID 区分检索
    expect(key).toMatch(UUID_V4)
  })

  it('两者都缺失时退化为时间戳 + 随机串，仍然非空且带前缀', () => {
    vi.stubGlobal('crypto', undefined)
    const key = newIdempotencyKey('ats')
    expect(key.startsWith('ats:t')).toBe(true)
    expect(key.length).toBeGreaterThan(10)
  })

  it('无前缀时不加冒号', () => {
    vi.stubGlobal('crypto', { randomUUID: () => 'abc' })
    expect(newIdempotencyKey()).toBe('abc')
    expect(newIdempotencyKey('pdf')).toBe('pdf:abc')
  })

  it('连两次调用不重复（时间戳兜底也要有随机成分）', () => {
    vi.stubGlobal('crypto', undefined)
    const keys = new Set(Array.from({ length: 50 }, () => newIdempotencyKey()))
    expect(keys.size).toBeGreaterThan(1)
  })
})
