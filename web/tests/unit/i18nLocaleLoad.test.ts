import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

import { createLazyCatalog } from '@/i18n'
import type { MessageTree } from '@/i18n'

/**
 * 文案按语言拆分（2026-10-29）后，en-US 从「内联常量」变成了「一次网络请求」。
 * 这类改动最容易被忽视的两条不变量：
 *
 * 1. 并发触发时只能加载一次（启动期与语言开关会同时命中）；
 * 2. **失败后必须能重试**——早期实现把 rejected promise 缓存在模块变量里，
 *    一次网络抖动就让英文在本会话内永久不可用。
 *
 * 第 3 组用例守的是白屏防线：`bootstrap()` 里的 try/catch 被删掉时，
 * en-US chunk 404 会让整个应用永远不挂载（比语言不对严重得多）。
 */

const tree = (brand: string): MessageTree => ({ brand })

describe('createLazyCatalog', () => {
  it('加载成功后复用结果，不再重复请求', async () => {
    let calls = 0
    const catalog = createLazyCatalog(async () => {
      calls += 1
      return tree('ZhiLi')
    })

    await catalog.ensure()
    await catalog.ensure()
    await catalog.ensure()

    expect(calls).toBe(1)
    expect(catalog.isLoaded()).toBe(true)
    expect(catalog.get()).toEqual(tree('ZhiLi'))
  })

  it('并发 ensure 共用同一次加载', async () => {
    let calls = 0
    const catalog = createLazyCatalog(() => {
      calls += 1
      return Promise.resolve(tree('ZhiLi'))
    })

    await Promise.all([catalog.ensure(), catalog.ensure(), catalog.ensure()])

    expect(calls).toBe(1)
  })

  it('加载失败后可以重试（不会把失败永久缓存）', async () => {
    let calls = 0
    const catalog = createLazyCatalog(async () => {
      calls += 1
      if (calls === 1) throw new Error('chunk 404')
      return tree('ZhiLi')
    })

    await expect(catalog.ensure()).rejects.toThrow('chunk 404')
    // 失败后状态必须是「未加载」，否则重试会直接拿到同一个失败 promise
    expect(catalog.isLoaded()).toBe(false)
    expect(catalog.get()).toBeNull()

    await expect(catalog.ensure()).resolves.toBeUndefined()
    expect(calls).toBe(2)
    expect(catalog.isLoaded()).toBe(true)
  })
})

describe('main.ts 启动守卫', () => {
  /** 去掉注释后再判据：注释里写满「必须 try/catch」也不能算通过。 */
  function stripComments(source: string): string {
    return source.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
  }

  function bootstrapBody(): string {
    const source = stripComments(readFileSync(new URL('../../src/main.ts', import.meta.url), 'utf8'))
    const start = source.indexOf('async function bootstrap')
    expect(start, 'main.ts 里找不到 bootstrap()，测试需要同步更新').toBeGreaterThan(-1)
    const end = source.indexOf('mount(', start)
    expect(end, 'bootstrap() 里找不到 mount(#app)').toBeGreaterThan(start)
    return source.slice(start, end)
  }

  it('await ensureLocaleMessages 必须被 try/catch 包住（否则 chunk 404 会白屏）', () => {
    const body = bootstrapBody()
    const guarded = body.indexOf('await ensureLocaleMessages')
    expect(guarded, 'bootstrap() 不再加载语言文案，本守卫需要同步更新').toBeGreaterThan(-1)

    const before = body.slice(0, guarded)
    expect(before, 'await ensureLocaleMessages 之前必须有 try {').toContain('try {')

    const after = body.slice(guarded)
    expect(after, 'catch 必须在挂载之前，否则异常仍然会中断挂载').toContain('catch')
  })
})
