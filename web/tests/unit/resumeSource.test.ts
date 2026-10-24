import { describe, expect, it } from 'vitest'
import { resumeSourceLabelKey } from '@/utils/resumeSource'

/**
 * 简历来源标签。
 *
 * 单行 guard 的价值在于：未知来源（后端将来新增的枚举值）**不能**让 `t(undefined)` 落到界面。
 * 这类问题在 TS 里看不见（API 返回的是 `string`），只有在运行时才炸成 "[object Object]" 或空标签。
 */
describe('resumeSourceLabelKey', () => {
  it('已知来源映射到各自文案键', () => {
    expect(resumeSourceLabelKey('MANUAL')).toBe('resumeDetail.sourceManual')
    expect(resumeSourceLabelKey('AI_OPTIMIZED')).toBe('resumeDetail.sourceAiOptimized')
    expect(resumeSourceLabelKey('JD_CUSTOMIZED')).toBe('resumeDetail.sourceJdCustomized')
    expect(resumeSourceLabelKey('MATERIAL_CUSTOMIZED')).toBe('resumeDetail.sourceMaterialCustomized')
    expect(resumeSourceLabelKey('RESTORED')).toBe('resumeDetail.sourceRestored')
  })

  it('未知/缺失来源回落到 sourceOther，而不是返回 undefined', () => {
    expect(resumeSourceLabelKey('FUTURE_CODE_FROM_BACKEND')).toBe('resumeDetail.sourceOther')
    expect(resumeSourceLabelKey('')).toBe('resumeDetail.sourceOther')
    expect(resumeSourceLabelKey(null)).toBe('resumeDetail.sourceOther')
    expect(resumeSourceLabelKey(undefined)).toBe('resumeDetail.sourceOther')
  })
})
