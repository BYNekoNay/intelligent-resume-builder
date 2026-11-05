/**
 * 简历版本来源码 → 文案键。
 *
 * 后端会新增来源类型，这里刻意用「查表 + 兜底」而不是拼字符串：
 * 未知来源统一回落到 resumeDetail.sourceOther，绝不把 undefined 传给 t()（否则界面出现裸 key）。
 */
export type KnownResumeSourceType =
  | 'MANUAL'
  | 'AI_OPTIMIZED'
  | 'JD_CUSTOMIZED'
  | 'MATERIAL_CUSTOMIZED'
  | 'RESTORED'

const SOURCE_LABEL_KEYS: Record<KnownResumeSourceType, string> = {
  MANUAL: 'resumeDetail.sourceManual',
  AI_OPTIMIZED: 'resumeDetail.sourceAiOptimized',
  JD_CUSTOMIZED: 'resumeDetail.sourceJdCustomized',
  MATERIAL_CUSTOMIZED: 'resumeDetail.sourceMaterialCustomized',
  RESTORED: 'resumeDetail.sourceRestored',
}

/** Resolve API source codes without allowing an unknown future value to reach t(undefined). */
export function resumeSourceLabelKey(source: string | null | undefined) {
  return SOURCE_LABEL_KEYS[source as KnownResumeSourceType] ?? 'resumeDetail.sourceOther'
}
