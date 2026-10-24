import { describe, expect, it } from 'vitest'
import { emptyPersonalProfile, normalizePersonalProfile } from '@/api/personalProfile'

/**
 * 个人档案归一化。
 *
 * 表单直接绑 `profile.targetRoleTitles.push(...)`，所以只要后端把某个数组字段回成 null
 * （老数据、导出包导入、字段刚加时的存量行），界面就会在 `push of null` 上崩。
 * 这里把"非数组 → 空数组、缺失 → 空串"钉死。
 */
describe('normalizePersonalProfile', () => {
  it('null/undefined → 全空档案（不抛错）', () => {
    const empty = emptyPersonalProfile()
    expect(normalizePersonalProfile(null)).toEqual(empty)
    expect(normalizePersonalProfile(undefined)).toEqual(empty)
  })

  it('缺字段按空值补齐，不产生 undefined', () => {
    const profile = normalizePersonalProfile({ fullName: '张三' })
    expect(profile.fullName).toBe('张三')
    expect(profile.email).toBe('')
    expect(profile.targetRoleTitles).toEqual([])
    expect(Object.values(profile).every(value => value !== undefined)).toBe(true)
  })

  it('数组字段被回成 null / 字符串时退回空数组（否则表单 push 会崩）', () => {
    const profile = normalizePersonalProfile({
      // 这三处刻意用 any：模拟后端返回了非数组（老数据/导出包）
      targetRoleTitles: null as unknown as string[],
      targetIndustries: 'Java' as unknown as string[],
      targetWorkPreferences: undefined as unknown as string[],
    })
    expect(profile.targetRoleTitles).toEqual([])
    expect(profile.targetIndustries).toEqual([])
    expect(profile.targetWorkPreferences).toEqual([])
  })

  it('已有值原样保留（含空数组与非空数组）', () => {
    const profile = normalizePersonalProfile({
      targetRoleTitles: ['后端'],
      targetIndustries: [],
      targetSeniority: '中级',
      careerPositioningSummary: '五年后端',
    })
    expect(profile.targetRoleTitles).toEqual(['后端'])
    expect(profile.targetIndustries).toEqual([])
    expect(profile.targetSeniority).toBe('中级')
    expect(profile.careerPositioningSummary).toBe('五年后端')
  })
})
