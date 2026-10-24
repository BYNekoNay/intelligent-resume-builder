import { describe, expect, it } from 'vitest'
import {
  AI_CONSENT_DATA_CATEGORIES,
  AI_CONSENT_TASK_SCOPES,
  JOB_GENERATION_DATA_CATEGORIES,
  JOB_GENERATION_POLICY_VERSION,
  hasFullAiConsent,
  hasJobGenerationConsent,
  type ConsentResponse,
} from '@/api/ai'

/**
 * AI 同意判定（合规门禁）。
 *
 * 这四个与条件是「能不能把用户资料发给模型」的唯一前端闸口：
 * 状态、政策版本、任务范围、数据类别。少判任何一条，都会在政策更新后
 * 让**旧版本的同意**继续放行新用途。断言按"逐条拆掉一个条件必须为 false"来写，
 * 这样新增条件时不会因为"整体仍然 true"而漏测。
 */

function consent(overrides: Partial<ConsentResponse> = {}): ConsentResponse {
  return {
    id: 1,
    status: 'GRANTED',
    createdAt: '2026-01-01T00:00:00',
    policyVersion: JOB_GENERATION_POLICY_VERSION,
    providerCode: 'bailian',
    taskScopes: [...AI_CONSENT_TASK_SCOPES],
    dataCategories: [...AI_CONSENT_DATA_CATEGORIES],
    ...overrides,
  }
}

describe('hasJobGenerationConsent', () => {
  it('全量同意且版本匹配 → true', () => {
    expect(hasJobGenerationConsent(consent())).toBe(true)
  })

  it('缺任一条即为 false', () => {
    expect(hasJobGenerationConsent(null)).toBe(false)
    expect(hasJobGenerationConsent(undefined)).toBe(false)
    expect(hasJobGenerationConsent(consent({ status: 'WITHDRAWN' }))).toBe(false)
    expect(hasJobGenerationConsent(consent({ policyVersion: 'v1.1.0' }))).toBe(false)
    expect(hasJobGenerationConsent(consent({ taskScopes: ['JOB_GENERATION'] }))).toBe(false)
    expect(hasJobGenerationConsent(consent({ taskScopes: ['JOB_MATERIAL_SELECTION'] }))).toBe(false)
    for (const category of JOB_GENERATION_DATA_CATEGORIES) {
      const partial = AI_CONSENT_DATA_CATEGORIES.filter(item => item !== category)
      expect(hasJobGenerationConsent(consent({ dataCategories: [...partial] }))).toBe(false)
    }
  })
})

describe('hasFullAiConsent', () => {
  it('全量同意 → true；少一个任务范围即 false', () => {
    expect(hasFullAiConsent(consent())).toBe(true)
    const missingOne = AI_CONSENT_TASK_SCOPES.filter(scope => scope !== 'INTERVIEW_COACH')
    expect(hasFullAiConsent(consent({ taskScopes: [...missingOne] }))).toBe(false)
  })

  it('少一个数据类别即为 false（含新增的 JOB_GENERATION 类别）', () => {
    for (const category of AI_CONSENT_DATA_CATEGORIES) {
      const partial = AI_CONSENT_DATA_CATEGORIES.filter(item => item !== category)
      expect(hasFullAiConsent(consent({ dataCategories: [...partial] }))).toBe(false)
    }
  })

  it('政策版本不一致即 false（旧同意不得放行新政策）', () => {
    expect(hasFullAiConsent(consent({ policyVersion: 'v0.9.0' }))).toBe(false)
  })
})
