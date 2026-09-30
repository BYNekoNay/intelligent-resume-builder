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
 * 数据量随简历版本/面试记录增长，单独放宽超时。
 */
export async function exportAccountData(): Promise<string> {
  return (await apiClient.get<string>('/api/auth/export', { responseType: 'text', timeout: 30_000 })).data
}

/** #7：删除账号（服务端软删并撤销全部会话）。 */
export async function deleteAccount(): Promise<ApiResponse<void>> {
  return (await apiClient.delete<ApiResponse<void>>('/api/auth/me')).data
}
