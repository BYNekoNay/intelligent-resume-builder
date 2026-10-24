import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitest/config'

/**
 * 前端单测（vitest）。
 *
 * 与 `vite.config.ts` 分开是为了不碰构建配置：`vite` 的 `UserConfig` 没有 `test` 字段，
 * 直接塞进去会让 `vue-tsc` 报错；这里重新声明 alias，保持与构建期一致的 `@` 解析。
 *
 * 环境用 `node`（默认）：首批只测纯逻辑。需要 DOM 的（`localUserData` / `useToast` /
 * 编辑器草稿）后续按文件在顶部加 `// @vitest-environment jsdom` 局部开启，不全局开 jsdom，
 * 那会让所有测试变慢且掩盖"其实不需要 DOM"的事实。
 */
export default defineConfig({
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  test: {
    environment: 'node',
    include: ['tests/unit/**/*.test.ts'],
    // 只补 Web Storage（api/* 经 client 连带加载 auth store，它在模块顶层访问 sessionStorage）
    setupFiles: ['tests/unit/setup.ts'],
    reporters: ['default'],
  },
})
