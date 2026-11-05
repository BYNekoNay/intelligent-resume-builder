/**
 * 系统健康检查接口：读取 /api/system/health 的服务状态、版本与可用能力列表。
 * 边界：status 在后端是可扩展字符串（前端联合类型里保留了 string 兜底），不能假设只有 UP/DOWN 两种取值。
 */
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
