import js from '@eslint/js'
import tseslint from 'typescript-eslint'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

/**
 * 前端静态检查（2026-10-29 从 0 到 1）。
 *
 * 设计取舍：
 * - **起步只开 warn，不进入 `npm run build`**：先建立可见性，不制造发布阻塞。
 * - **用 `--max-warnings` 做棘轮**：`package.json` 的 lint 脚本把阈值钉在当前告警数上，
 *   于是「新增告警」会让 lint 变红，而「清理告警」不会（需手动下调阈值）。与后端行数棘轮
 *   （`source-size-baseline.txt` 只允许下调）同一套思路。
 * - **刻意不启用全部 recommended 规则**：在这个体量的 Vue 项目里会产生大量与熵增无关的噪声
 *   （缩进、引号、`no-prototype-builtins` 等），噪声一多就没人看，门禁等于失效。
 *   只保留三类真正会腐烂的规则：显式 `any`、未使用变量、空 catch。
 * - Vue 只取 `flat/essential`（正确性规则），不取 `flat/recommended`（排版类，会与各视图
 *   自带的 scoped 样式改造冲突，工作量与收益不成比例）。
 * - **`.ts` 必须用 TS parser**：默认 espree 会在第一个类型标注处报 Parsing error
 *   （首跑实测 121 个 error 全是这一类，不是代码问题）。
 */

/** 三类核心规则：显式 any / 未使用变量 / 空 catch。三个文件类型共用。 */
const coreRules = {
  // ① 显式 any：边界数据「随便进」的温床（当前 35 处，最集中 useDraftReview.ts）
  '@typescript-eslint/no-explicit-any': 'warn',
  // ② 未使用变量/导入：重构残留的直接信号（下划线开头放行）
  '@typescript-eslint/no-unused-vars': [
    'warn',
    { argsIgnorePattern: '^_', varsIgnorePattern: '^_', caughtErrors: 'none' },
  ],
  // ③ 空 catch 会把错误吞掉，必须写注释说明为什么可以忽略（allowEmptyCatch: false）
  'no-empty': ['warn', { allowEmptyCatch: false }],
}

export default tseslint.config(
  {
    // 构建产物、依赖、以及「测试/脚本自有其运行环境」的目录不参与
    ignores: [
      'dist/**',
      'node_modules/**',
      'playwright-report/**',
      'test-results/**',
      // 单文件 1200+ 行的纯数据表，未使用变量类规则误报多且无收益
      'src/i18n/index.ts',
    ],
  },

  // ---- TypeScript 源 ----
  {
    files: ['src/**/*.ts', 'tests/**/*.ts', 'e2e/**/*.ts', '*.ts'],
    languageOptions: {
      parser: tseslint.parser,
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser, ...globals.node },
    },
    plugins: { '@typescript-eslint': tseslint.plugin },
    rules: coreRules,
  },

  // ---- Vue 单文件组件 ----
  // 必须展开 pluginVue 的 flat 预设：它负责把顶层 parser 设为 vue-eslint-parser，
  // 只把规则对象摘出来而不设 parser，.vue 会退回 espree → 「Unexpected token <」
  // （首跑实测 54 个 error 全是这一类）。
  ...pluginVue.configs['flat/essential'],
  {
    files: ['src/**/*.vue'],
    languageOptions: {
      parserOptions: {
        // <script lang="ts"> 交给 TS parser，模板仍由 vue-eslint-parser 解析
        parser: tseslint.parser,
        ecmaVersion: 'latest',
        sourceType: 'module',
        extraFileExtensions: ['.vue'],
      },
      globals: { ...globals.browser },
    },
    plugins: { '@typescript-eslint': tseslint.plugin },
    rules: {
      ...coreRules,
      // 现有 6 个单词组件名（AuthShell / DraftContentFields 等）不在本批改造范围
      'vue/multi-word-component-names': 'off',
      // .vue <template> 里的标识符由 vue 编译器解析，ESLint 不感知会误报
      'no-undef': 'off',
    },
  },

  // ---- Node 侧的脚本与配置 ----
  {
    files: ['scripts/**/*.mjs', '*.js', '*.mjs'],
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.node },
    },
    rules: {
      ...js.configs.recommended.rules,
      'no-empty': ['warn', { allowEmptyCatch: false }],
    },
  },
)
