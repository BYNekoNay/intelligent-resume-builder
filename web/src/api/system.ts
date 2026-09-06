import { apiClient, type ApiResponse } from './client'

export interface SystemHealth {
  service: string
  status: 'UP' | 'DEGRADED' | 'DOWN' | string
  version: string
  capabilities: string[]
  checks?: Array<{ capability: string; status: 'UP' | 'DOWN' | 'DEGRADED' | string }>
}

export async function getSystemHealth(): Promise<ApiResponse<SystemHealth>> {
  const response = await apiClient.get<ApiResponse<SystemHealth>>('/api/system/health')
  return response.data
}
