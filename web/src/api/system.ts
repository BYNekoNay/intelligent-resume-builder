import { apiClient, type ApiResponse } from './client'

export interface SystemHealth {
  service: string
  status: 'UP'
  stage: 'SCAFFOLD'
}

export async function getSystemHealth(): Promise<ApiResponse<SystemHealth>> {
  const response = await apiClient.get<ApiResponse<SystemHealth>>('/api/system/health')
  return response.data
}
