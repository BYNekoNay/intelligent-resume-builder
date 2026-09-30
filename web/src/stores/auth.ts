import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchCurrentUser, login, logout, refresh, register, updateProfile, type CurrentUser, type LoginPayload, type RegisterPayload } from '@/api/auth'
import { clearUserLocalData } from '@/utils/localUserData'

const LEGACY_ACCESS_TOKEN_KEY = 'intelligent-resume.access-token'
sessionStorage.removeItem(LEGACY_ACCESS_TOKEN_KEY)

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref<string | null>(null)
  const currentUser = ref<CurrentUser | null>(null)
  const initialized = ref(false)
  const initializationError = ref<'NETWORK' | null>(null)

  function setAccessToken(token: string | null) {
    accessToken.value = token
    if (!token) {
      currentUser.value = null
    }
  }

  // #61：启动时若因断网导致会话校验失败，页面此前会永久停留在「未登录且不跳转」状态。
  // 现在允许重试（页头重试入口 / 后续路由跳转都会再次调用），并用同一个在途 Promise
  // 合并并发调用，避免并发 refresh 触发服务端复用检测导致整族会话被撤销。
  let initializeInFlight: Promise<void> | null = null

  async function initialize() {
    if (initialized.value && initializationError.value === null) return
    if (initializeInFlight) return initializeInFlight
    initializeInFlight = (async () => {
      initialized.value = true
      try {
        if (!accessToken.value) {
          accessToken.value = (await refresh()).data.accessToken
        }
        currentUser.value = (await fetchCurrentUser()).data
        initializationError.value = null
      } catch (error: any) {
        if (!error?.response) {
          // 保持错误标记直到重试成功，避免重试期间横幅闪烁。
          initializationError.value = 'NETWORK'
          return
        }
        setAccessToken(null)
        initializationError.value = null
      } finally {
        initializeInFlight = null
      }
    })()
    return initializeInFlight
  }

  async function signIn(payload: LoginPayload) {
    const response = await login(payload)
    setAccessToken(response.data.accessToken)
    initializationError.value = null
    currentUser.value = (await fetchCurrentUser()).data
  }

  async function signUp(payload: RegisterPayload) {
    const response = await register(payload)
    setAccessToken(response.data.accessToken)
    initializationError.value = null
    currentUser.value = (await fetchCurrentUser()).data
  }

  async function signOut() {
    try {
      await logout()
    } finally {
      // 退出登录即离开该账号：连同浏览器里留存的本账号数据（简历/编辑草稿、待恢复任务、
      // 导入原文、文案草稿）一起清掉，避免共享设备上换人使用后仍能读到上一账号的 PII。
      clearUserLocalData(currentUser.value?.id)
      setAccessToken(null)
    }
  }

  async function updateCurrentUser(displayName: string) {
    currentUser.value = (await updateProfile({ displayName })).data
  }

  return { accessToken, currentUser, initialized, initializationError, setAccessToken, initialize, signIn, signUp, signOut, updateCurrentUser }
})
