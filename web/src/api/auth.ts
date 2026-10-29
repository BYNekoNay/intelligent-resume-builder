import { http } from './client'

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

export async function register(payload: RegisterPayload): Promise<TokenResponse> {
  return http.post<TokenResponse>('/api/auth/register', payload)
}

export async function login(payload: LoginPayload): Promise<TokenResponse> {
  return http.post<TokenResponse>('/api/auth/login', payload)
}

export async function logout(): Promise<void> {
  return http.post<void>('/api/auth/logout')
}

export async function refresh(): Promise<TokenResponse> {
  return http.post<TokenResponse>('/api/auth/refresh')
}

export async function fetchCurrentUser(): Promise<CurrentUser> {
  return http.get<CurrentUser>('/api/auth/me')
}

export async function updateProfile(payload: { displayName: string }): Promise<CurrentUser> {
  return http.patch<CurrentUser>('/api/auth/me', payload)
}

export async function changeEmail(payload: { email: string; currentPassword: string }): Promise<void> {
  return http.post<void>('/api/auth/me/email', payload)
}

export async function changePassword(payload: { currentPassword: string; newPassword: string }): Promise<void> {
  return http.post<void>('/api/auth/me/password', payload)
}

/**
 * #7：导出当前账号在各业务域的个人数据（服务端返回 JSON 文件内容，非统一响应信封）。
 * 数据量随简历版本/面试记录/职业资料增长且**无体积上限**（实测 200 条 62KB 职业资料的账号
 * 单次响应 11.92MB）：超时与同类下载路径（PDF 导出，体上限 10MB）对齐为 60s；
 * 30s 只够 ~3.2Mbps 的持续带宽，慢网络下合法下载会被中断。
 */
export async function exportAccountData(currentPassword: string): Promise<string> {
  return http.post<string>('/api/auth/export', { currentPassword }, { responseType: 'text', timeout: 60_000 })
}

/** #7：删除账号（服务端软删并撤销全部会话）。 */
export async function deleteAccount(): Promise<void> {
  return http.delete<void>('/api/auth/me')
}

/** D2 阶段 3：删除撤销 —— 撤销窗口内凭用户名/邮箱 + 密码恢复账号并直接登录（返回新 token）。 */
export async function restoreAccountDeletion(payload: LoginPayload): Promise<TokenResponse> {
  return http.post<TokenResponse>('/api/auth/deletion/restore', payload)
}
