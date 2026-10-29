import axios, { type AxiosError, type AxiosRequestConfig, type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/stores/auth'

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
  traceId: string
}

/**
 * 后端业务失败（`code !== 0`）。
 *
 * 为什么要有它：改造前 api 函数把整个信封（`{code,message,data,traceId}`）交给调用方，
 * 于是每个视图各自判断成败、各自拼 fallback 文案，后端**错误码体系**在 UI 侧被整体压平成
 * 一句「操作失败」。有了 ApiError，错误码与 traceId 都能一路传到界面，用户截图就是排障入口。
 */
export class ApiError extends Error {
  readonly code: number
  readonly traceId: string | null

  constructor(code: number, message: string, traceId: string | null) {
    super(message || `API error ${code}`)
    this.name = 'ApiError'
    this.code = code
    this.traceId = traceId
  }
}

export const apiClient = axios.create({
  // Keep local development same-origin so HttpOnly refresh cookies work
  // regardless of whether the UI is opened via localhost or 127.0.0.1.
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '/',
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

/**
 * 统一请求入口：**解包 `ApiResponse` 信封**，业务码非 0 抛 {@link ApiError}。
 *
 * 三条设计约束（改造时刻意选的，别再退回旧写法）：
 * 1. **不放 axios `transformResponse`**：它只负责字符串→对象，拿不到「信封 / 失败」的语义；
 * 2. **不在响应拦截器里改返回值**：那会让 axios 的静态类型（`AxiosResponse`）与实际值不一致，
 *    类型检查从此失效——正是本仓 `vue-tsc` 门禁存在的意义；
 * 3. **非信封响应原样返回**：PDF 下载（`responseType: 'blob'`）与代理错误页不是 ApiResponse，
 *    不能因为「没有 code 字段」就拒绝。
 */
export async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await apiClient.request<ApiResponse<T>>(config)
  const body = response.data as unknown

  const looksLikeEnvelope = body != null && typeof body === 'object' && 'code' in (body as Record<string, unknown>)
  if (!looksLikeEnvelope) {
    return body as T
  }

  const envelope = body as ApiResponse<T>
  if (envelope.code !== 0) {
    throw new ApiError(envelope.code, envelope.message ?? '', envelope.traceId ?? null)
  }
  return envelope.data
}

/** 各 api 模块只用这组方法，视图层因此永远拿不到信封（`.data.data` 归零）。 */
export const http = {
  get: <T>(url: string, config?: AxiosRequestConfig): Promise<T> =>
    request<T>({ ...config, method: 'GET', url }),
  post: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> =>
    request<T>({ ...config, method: 'POST', url, data }),
  put: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> =>
    request<T>({ ...config, method: 'PUT', url, data }),
  patch: <T>(url: string, data?: unknown, config?: AxiosRequestConfig): Promise<T> =>
    request<T>({ ...config, method: 'PATCH', url, data }),
  delete: <T>(url: string, config?: AxiosRequestConfig): Promise<T> =>
    request<T>({ ...config, method: 'DELETE', url }),
}
