import { apiClient, type ApiResponse } from './client'
import { AI_TASK_POLL_ATTEMPTS, AiTaskTimeoutError } from './ai'

export interface MaterialGenerationResponse {
  taskId: number
  rawMaterialText: string
  generatedResumeJson: Record<string, unknown>
  suggestions: string[]
  requiresManualConfirmation: boolean
}

export interface MaterialAssociationResponse {
  taskId: number
  expandedMaterial: string
  verificationQuestions: string[]
  disclaimer: string
}

const RESUME_SECTIONS = new Set(['basics', 'work', 'education', 'skills', 'projects', 'certificates', 'languages', 'awards'])

/** 发起素材生成简历任务，轮询直到完成，返回脱壳后的结果。idempotencyKey 由调用方在动作发起时生成并在该次逻辑提交生命周期内复用 */
export async function generateResumeFromMaterial(
  rawMaterialText: string,
  jobDescriptionId: number | undefined,
  associationReference: string | undefined,
  idempotencyKey: string,
) {
  const taskId = await createMaterialTask({ rawMaterialText, ...(jobDescriptionId ? { jobDescriptionId } : {}), ...(associationReference ? { generationMode: 'ASSOCIATIVE_STRUCTURED_DRAFT', associationReference } : {}) }, idempotencyKey)
  const task = await waitForTask(taskId)
  const r = task.resultJson as Record<string, unknown>
  return { data: { data: { taskId, rawMaterialText, generatedResumeJson: normalizeResumeJson((r.generatedResumeJson ?? r.draftResumeJson ?? r) as Record<string, unknown>), suggestions: Array.isArray(r.suggestions) ? r.suggestions as string[] : [], requiresManualConfirmation: true } } } as { data: ApiResponse<MaterialGenerationResponse> }
}

function normalizeResumeJson(value: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(value).filter(([section]) => RESUME_SECTIONS.has(section)))
}

export function generateResumeFromAssociation(rawMaterialText: string, associationReference: string, idempotencyKey: string) {
  return generateResumeFromMaterial(rawMaterialText, undefined, associationReference, idempotencyKey)
}

export async function generateMaterialAssociation(rawMaterialText: string, idempotencyKey: string) {
  const taskId = await createMaterialTask({ rawMaterialText, generationMode: 'ASSOCIATIVE_EXPANSION' }, idempotencyKey)
  const task = await waitForTask(taskId)
  const r = task.resultJson as Record<string, unknown>
  return { data: { data: { taskId, expandedMaterial: typeof r.expandedMaterial === 'string' ? r.expandedMaterial : '', verificationQuestions: Array.isArray(r.verificationQuestions) ? r.verificationQuestions as string[] : [], disclaimer: typeof r.disclaimer === 'string' ? r.disclaimer : '' } } } as { data: ApiResponse<MaterialAssociationResponse> }
}

async function createMaterialTask(input: Record<string, unknown>, idempotencyKey: string) {
  const createResp = await apiClient.post<ApiResponse<{ id: number; status: string }>>('/api/ai/tasks', { taskType: 'MATERIAL_IMPORT', input }, {
    headers: { 'Idempotency-Key': idempotencyKey },
  })
  return createResp.data.data.id
}

async function waitForTask(taskId: number, maxAttempts: number = AI_TASK_POLL_ATTEMPTS) {
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    await delay(attempt < 3 ? [1000, 2000, 4000][attempt] : 2000)
    const pollResp = await apiClient.get<ApiResponse<{
      id: number; status: string; resultJson: Record<string, unknown>; errorMessage: string | null
    }>>(`/api/ai/tasks/${taskId}`)
    const task = pollResp.data.data

    if (task.status === 'SUCCESS' && task.resultJson) {
      return task
    }

    if (task.status === 'FAILED') {
      throw new Error(task.errorMessage || '素材生成任务失败')
    }
  }

  throw new AiTaskTimeoutError(taskId)
}

function delay(ms: number) { return new Promise((resolve) => setTimeout(resolve, ms)) }
