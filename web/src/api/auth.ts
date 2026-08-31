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
