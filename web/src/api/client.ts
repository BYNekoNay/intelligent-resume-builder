import axios, { type AxiosError, type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/stores/auth'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId: string
}

export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? 'http://localhost:8080',
  timeout: 10_000,
  withCredentials: true,
})

interface RetriableRequestConfig extends InternalAxiosRequestConfig {
  _retriedAfterRefresh?: boolean
}

let refreshPromise: Promise<string> | null = null

apiClient.interceptors.request.use((config) => {
  const authStore = useAuthStore()
  if (authStore.accessToken) {
    config.headers.Authorization = `Bearer ${authStore.accessToken}`
  }
  return config
})

apiClient.interceptors.response.use(
  (response) => response,
  async (error: AxiosError<ApiResponse<unknown>>) => {
    const originalRequest = error.config as RetriableRequestConfig | undefined
    const isUnauthorized = error.response?.status === 401 || error.response?.data?.code === 40101
    const isAuthRequest = originalRequest?.url?.startsWith('/api/auth/')

    if (!originalRequest || !isUnauthorized || isAuthRequest || originalRequest._retriedAfterRefresh) {
      return Promise.reject(error)
    }

    originalRequest._retriedAfterRefresh = true
    try {
      if (!refreshPromise) {
        refreshPromise = apiClient.post<ApiResponse<{ accessToken: string }>>('/api/auth/refresh')
          .then((response) => response.data.data.accessToken)
          .finally(() => { refreshPromise = null })
      }
      const accessToken = await refreshPromise
      useAuthStore().setAccessToken(accessToken)
      originalRequest.headers.Authorization = `Bearer ${accessToken}`
      return apiClient(originalRequest)
    } catch (refreshError) {
      useAuthStore().setAccessToken(null)
      return Promise.reject(refreshError)
    }
  },
)
