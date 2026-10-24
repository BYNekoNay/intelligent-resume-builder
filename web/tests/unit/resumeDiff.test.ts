import { describe, expect, it } from 'vitest'
import { diffResumeVersions, stableEntryKey, summarizeDiff } from '@/utils/resumeDiff'

/**
 * 版本对比的稳定键与差异统计。
 *
 * `stableEntryKey` 是 diff 正确性的根：条目排序/对齐全靠它。
 * 三级优先级（id → 复合键 → 下标）任何一级错位，都会把"改了一条"显示成"删一条 + 加一条"，
 * 而这类错误在界面上看起来"只是多了两行"，很容易被当成正常差异放过。
 */

describe('stableEntryKey', () => {
  it('有 id 时以 id 为准（即使标题也不同）', () => {
    expect(stableEntryKey({ id: 7, title: 'A' }, 0)).toBe('id:7')
    expect(stableEntryKey({ id: 7, title: 'B' }, 3)).toBe('id:7')
  })

  it('id 为空串/undefined/null 时退到复合键', () => {
    expect(stableEntryKey({ id: '', title: 'Acme', company: 'Acme', startDate: '2024-01' }, 0))
      .toBe('k:Acme|Acme|2024-01|')
    expect(stableEntryKey({ id: null, name: '项目甲', startDate: '2023' }, 1)).toBe('k:项目甲||2023|')
    expect(stableEntryKey({ id: undefined, title: '职称', endDate: '2025' }, 2)).toBe('k:职称|||2025')
  })

  it('什么都没有时用下标兜底', () => {
    expect(stableEntryKey({}, 5)).toBe('i:5')
  })

  it('同一条内容在不同位置得到同一个键（这才是它能对齐的前提）', () => {
    const a = stableEntryKey({ title: 'X', startDate: '2020' }, 0)
    const b = stableEntryKey({ title: 'X', startDate: '2020' }, 9)
    expect(a).toBe(b)
  })
})

describe('diffResumeVersions', () => {
  it('内容相同 → 全部章节 UNCHANGED', () => {
    const json = { basics: { name: '张三' }, work: [{ id: 1, title: '后端' }] }
    const diffs = diffResumeVersions(json, structuredClone(json))
    expect(diffs.length).toBeGreaterThan(0)
    expect(diffs.every(diff => diff.type === 'UNCHANGED')).toBe(true)
    expect(summarizeDiff(diffs)).toEqual({
      changedSections: 0, addedItems: 0, removedItems: 0, modifiedItems: 0,
    })
  })

  it('数组章节新增条目 → 该条目 ADDED，计入 addedItems', () => {
    const base = { work: [{ id: 1, title: '后端' }] }
    const compare = { work: [{ id: 1, title: '后端' }, { id: 2, title: '全栈' }] }
    const work = diffResumeVersions(base, compare).find(diff => diff.sectionKey === 'work')
    expect(work?.type).toBe('CHANGED')
    expect(work?.entries.some(entry => entry.type === 'ADDED' && entry.key === 'id:2')).toBe(true)
    expect(summarizeDiff(diffResumeVersions(base, compare)).addedItems).toBe(1)
  })

  it('数组章节删除条目 → 计入 removedItems', () => {
    const base = { work: [{ id: 1, title: '后端' }, { id: 2, title: '全栈' }] }
    const compare = { work: [{ id: 1, title: '后端' }] }
    expect(summarizeDiff(diffResumeVersions(base, compare)).removedItems).toBe(1)
  })

  it('对象章节改字段 → 计入 modifiedItems 且只标变化的键', () => {
    const base = { basics: { name: '张三', title: '后端' } }
    const compare = { basics: { name: '李四', title: '后端' } }
    const basics = diffResumeVersions(base, compare).find(diff => diff.sectionKey === 'basics')
    expect(basics?.fields.filter(field => field.type === 'MODIFIED').map(field => field.key)).toEqual(['name'])
    expect(summarizeDiff(diffResumeVersions(base, compare)).modifiedItems).toBe(1)
  })
})

describe('summarizeDiff', () => {
  it('空输入 → 全零（不抛错）', () => {
    expect(summarizeDiff([])).toEqual({
      changedSections: 0, addedItems: 0, removedItems: 0, modifiedItems: 0,
    })
  })
})
