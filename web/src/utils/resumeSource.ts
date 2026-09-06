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
