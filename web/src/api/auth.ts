import { apiClient, type ApiResponse } from './client'

export interface RegisterPayload {
  username: string
  email: string
  password: string
}

export interface LoginPayload {
  username: string
  password: string
}

export interface TokenResponse {
  accessToken: string
  accessTokenExpiresInSeconds: number
}

export interface CurrentUser {
  id: number
  username: string
  email: string
  displayName: string | null
}

export async function register(payload: RegisterPayload): Promise<ApiResponse<TokenResponse>> {
  return (await apiClient.post<ApiResponse<TokenResponse>>('/api/auth/register', payload)).data
}

export async function login(payload: LoginPayload): Promise<ApiResponse<TokenResponse>> {
  return (await apiClient.post<ApiResponse<TokenResponse>>('/api/auth/login', payload)).data
}

export async function logout(): Promise<ApiResponse<void>> {
  return (await apiClient.post<ApiResponse<void>>('/api/auth/logout')).data
}

export async function refresh(): Promise<ApiResponse<TokenResponse>> {
  return (await apiClient.post<ApiResponse<TokenResponse>>('/api/auth/refresh')).data
}

export async function fetchCurrentUser(): Promise<ApiResponse<CurrentUser>> {
  return (await apiClient.get<ApiResponse<CurrentUser>>('/api/auth/me')).data
}

export async function updateProfile(payload: { displayName: string }): Promise<ApiResponse<CurrentUser>> {
  return (await apiClient.patch<ApiResponse<CurrentUser>>('/api/auth/me', payload)).data
}

export async function changeEmail(payload: { email: string; currentPassword: string }): Promise<ApiResponse<void>> {
  return (await apiClient.post<ApiResponse<void>>('/api/auth/me/email', payload)).data
}

export async function changePassword(payload: { currentPassword: string; newPassword: string }): Promise<ApiResponse<void>> {
  return (await apiClient.post<ApiResponse<void>>('/api/auth/me/password', payload)).data
}

/**
 * #7：导出当前账号在各业务域的个人数据（服务端返回 JSON 文件内容，非统一响应信封）。
 * 数据量随简历版本/面试记录/职业资料增长且**无体积上限**（实测 200 条 62KB 职业资料的账号
 * 单次响应 11.92MB）：超时与同类下载路径（PDF 导出，体上限 10MB）对齐为 60s；
 * 30s 只够 ~3.2Mbps 的持续带宽，慢网络下合法下载会被中断。
 */
export async function exportAccountData(): Promise<string> {
  return (await apiClient.get<string>('/api/auth/export', { responseType: 'text', timeout: 60_000 })).data
}

/** #7：删除账号（服务端软删并撤销全部会话）。 */
export async function deleteAccount(): Promise<ApiResponse<void>> {
  return (await apiClient.delete<ApiResponse<void>>('/api/auth/me')).data
}

/** D2 阶段 3：删除撤销 —— 撤销窗口内凭用户名/邮箱 + 密码恢复账号并直接登录（返回新 token）。 */
export async function restoreAccountDeletion(payload: LoginPayload): Promise<ApiResponse<TokenResponse>> {
  return (await apiClient.post<ApiResponse<TokenResponse>>('/api/auth/deletion/restore', payload)).data
}
