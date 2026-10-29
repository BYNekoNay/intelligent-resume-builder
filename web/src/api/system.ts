import { http } from './client'

export interface SystemHealth {
  service: string
  status: 'UP' | 'DEGRADED' | 'DOWN' | string
  version: string
  capabilities: string[]
  checks?: Array<{ capability: string; status: 'UP' | 'DOWN' | 'DEGRADED' | string }>
}

export async function getSystemHealth(): Promise<SystemHealth> {
  return http.get<SystemHealth>('/api/system/health')
}
