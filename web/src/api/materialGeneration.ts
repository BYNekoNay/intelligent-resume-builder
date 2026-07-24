import { apiClient, type ApiResponse } from './client'

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

/** 发起素材生成简历任务，轮询直到完成，返回脱壳后的结果 */
export async function generateResumeFromMaterial(rawMaterialText: string, jobDescriptionId?: number, associationReference?: string) {
  const taskId = await createMaterialTask({ rawMaterialText, ...(jobDescriptionId ? { jobDescriptionId } : {}), ...(associationReference ? { generationMode: 'ASSOCIATIVE_STRUCTURED_DRAFT', associationReference } : {}) })
  const task = await waitForTask(taskId)
  const r = task.resultJson as Record<string, unknown>
  return { data: { data: { taskId, rawMaterialText, generatedResumeJson: normalizeResumeJson((r.generatedResumeJson ?? r.draftResumeJson ?? r) as Record<string, unknown>), suggestions: Array.isArray(r.suggestions) ? r.suggestions as string[] : [], requiresManualConfirmation: true } } } as { data: ApiResponse<MaterialGenerationResponse> }
}

function normalizeResumeJson(value: Record<string, unknown>) {
  return Object.fromEntries(Object.entries(value).filter(([section]) => RESUME_SECTIONS.has(section)))
}

export function generateResumeFromAssociation(rawMaterialText: string, associationReference: string) {
  return generateResumeFromMaterial(rawMaterialText, undefined, associationReference)
}

export async function generateMaterialAssociation(rawMaterialText: string) {
  const taskId = await createMaterialTask({ rawMaterialText, generationMode: 'ASSOCIATIVE_EXPANSION' })
  const task = await waitForTask(taskId)
  const r = task.resultJson as Record<string, unknown>
  return { data: { data: { taskId, expandedMaterial: typeof r.expandedMaterial === 'string' ? r.expandedMaterial : '', verificationQuestions: Array.isArray(r.verificationQuestions) ? r.verificationQuestions as string[] : [], disclaimer: typeof r.disclaimer === 'string' ? r.disclaimer : '' } } } as { data: ApiResponse<MaterialAssociationResponse> }
}

async function createMaterialTask(input: Record<string, unknown>) {
  const createResp = await apiClient.post<ApiResponse<{ id: number; status: string }>>('/api/ai/generate-resume-for-job', { taskType: 'MATERIAL_IMPORT', input })
  return createResp.data.data.id
}

async function waitForTask(taskId: number) {
  for (let attempt = 0; attempt < 20; attempt++) {
    await delay(attempt < 3 ? [1000, 2000, 4000][attempt] : 5000)
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

  throw new Error('素材生成任务超时，请稍后重试')
}

function delay(ms: number) { return new Promise((resolve) => setTimeout(resolve, ms)) }
