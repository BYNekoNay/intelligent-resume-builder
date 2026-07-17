import { apiClient, type ApiResponse } from './client'
export interface MaterialGenerationResponse { taskId: number; rawMaterialText: string; generatedResumeJson: Record<string, unknown>; suggestions: string[]; requiresManualConfirmation: boolean }
export function generateResumeFromMaterial(rawMaterialText: string, jobDescriptionId?: number) {
  return apiClient.post<ApiResponse<MaterialGenerationResponse>>('/api/ai/generate-resume-from-material', { rawMaterialText, jobDescriptionId })
}
