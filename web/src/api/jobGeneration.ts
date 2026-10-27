import { apiClient, type ApiResponse } from './client'
// 仅类型引用：编译期擦除，不会与 ai.ts 形成运行时循环依赖。
import type { AiTask, AiTaskPreview } from './ai'

/**
 * 生成确认页（JOB_GENERATION）的请求体与两个端点（R32）。
 *
 * <p>单独成文件的原因：`api/ai.ts` 已到 300 行上限。
 * 通过 `@/api/ai` 原样转出，调用方写法不变。
 */
export interface ConfirmMaterialsPayload {
  taskUpdatedAt: string
  selectedMaterialIds: number[]
  forcedIncludedMaterialIds?: number[]
  resumeTitle?: string
}

/**
 * confirm-materials 的请求体，**预览与创建共用同一份**。
 *
 * 后端会用自己的组装结果重算令牌；字段一旦在两处各写一遍就会漂移 → 40901。
 */
function confirmMaterialsBody(payload: ConfirmMaterialsPayload) {
  return {
    taskUpdatedAt: payload.taskUpdatedAt,
    selectedMaterialIds: payload.selectedMaterialIds,
    forcedIncludedMaterialIds: payload.forcedIncludedMaterialIds ?? [],
    resumeTitle: payload.resumeTitle,
  }
}

/** 生成确认页的发送前预览（R32）。只读：不落库、不调模型。 */
export function previewConfirmMaterials(id: number, payload: ConfirmMaterialsPayload) {
  return apiClient
    .post<ApiResponse<AiTaskPreview>>(`/api/ai/tasks/${id}/confirm-materials/preview`, confirmMaterialsBody(payload))
    .then(response => response.data.data)
}

export function confirmMaterials(
  id: number,
  payload: ConfirmMaterialsPayload,
  idempotencyKey: string,
  previewToken: string,
) {
  return apiClient.post<ApiResponse<AiTask>>(`/api/ai/tasks/${id}/confirm-materials`, confirmMaterialsBody(payload), {
    headers: { 'Idempotency-Key': idempotencyKey, 'X-AI-Preview-Token': previewToken },
  })
}
