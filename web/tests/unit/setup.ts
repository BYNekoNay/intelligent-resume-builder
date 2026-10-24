/**
 * 单测公共前置（`vitest.config.ts` 的 `setupFiles`）。
 *
 * 为什么需要它：`api/*.ts` 会 `import './client'`，而 `client.ts` 又 `import '@/stores/auth'`，
 * 后者在**模块顶层**执行 `sessionStorage.removeItem(...)`。在 node 环境里没有 `sessionStorage`，
 * 于是「纯函数」的测试在 import 阶段就抛 `ReferenceError` —— 与被测逻辑完全无关。
 *
 * 只补 Web Storage 这一层，**不**引入 jsdom：
 * 需要 DOM 的测试应当在文件顶部用 `// @vitest-environment jsdom` 局部开启，
 * 而不是让所有测试都跑在一个假浏览器里（那会掩盖"这段代码其实不依赖 DOM"）。
 */

class MemoryStorage implements Storage {
  private store = new Map<string, string>()

  get length(): number {
    return this.store.size
  }

  clear(): void {
    this.store.clear()
  }

  getItem(key: string): string | null {
    return this.store.has(key) ? this.store.get(key)! : null
  }

  key(index: number): string | null {
    return [...this.store.keys()][index] ?? null
  }

  removeItem(key: string): void {
    this.store.delete(key)
  }

  setItem(key: string, value: string): void {
    this.store.set(key, String(value))
  }
}

for (const name of ['sessionStorage', 'localStorage'] as const) {
  if (typeof globalThis[name] === 'undefined') {
    Object.defineProperty(globalThis, name, {
      value: new MemoryStorage(),
      configurable: true,
      writable: true,
    })
  }
}
