/**
 * 会话状态（Pinia）：持有 accessToken 与当前用户，负责启动会话恢复（initialize）、
 * 登录/注册/删号恢复/登出、改 displayName。
 *
 * 给第一天来的你：所有页面都要先过「登录了吗」这一关，本 store 就是这一关的数据源——
 * 路由守卫（router/index.ts）靠 currentUser 判断放行或跳登录页，
 * AppLayout（页头）靠它显示用户名与退出入口，AccountView / LoginView / RegisterView /
 * ResumeEditorView / MaterialSelectionConfirmView 也直接读写它。
 *
 * token 存放策略（重点，为什么这么绕）：
 * - accessToken 只放内存：页面一刷新就没了，浏览器存储里不留任何长期登录凭据，
 *   即使页面被注入恶意脚本也偷不走能长期使用的令牌（登录页文案 secureSession 也是这个口径）；
 * - 「刷新页面后怎么还登录着？」靠后端下发的 HttpOnly refresh cookie——JS 读不到它，
 *   无法被脚本窃取；initialize() 启动时拿它换一个新 accessToken（api/auth.ts 的 refresh），
 *   client.ts 的 401 拦截器在 token 过期时也走同一条路，刷新成功后经 setAccessToken 回填。
 *
 * 边界：api/client.ts 的请求拦截器读取本 store 注入 Authorization 头；
 * 本 store 只管会话，token 之外的用户本地数据清理在 utils/localUserData.ts。
 */
import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchCurrentUser, login, logout, refresh, register, restoreAccountDeletion, updateProfile, type CurrentUser, type LoginPayload, type RegisterPayload } from '@/api/auth'
import { clearUserLocalData } from '@/utils/localUserData'

// 旧版把 accessToken 存 sessionStorage；现方案改为仅内存 + refresh cookie，模块加载时清掉历史遗留键。
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
  // 合并并发调用——initializeInFlight 的作用：多个页面同时调 initialize 时只发一次
  // refresh 请求。这不是省流量，而是安全要求：后端对 refresh cookie 有「复用检测」，
  // 并发多次刷新会被判定为令牌被盗用，导致整族会话被撤销、用户被强制登出。
  let initializeInFlight: Promise<void> | null = null

  /**
   * 启动会话恢复：应用加载/刷新页面后调一次，把「refresh cookie」换回可用的 accessToken + 用户信息。
   * 步骤：① 内存里已有 token 就只补拉用户信息；② 没有则先 refresh（拿 HttpOnly cookie 换 token）；
   * ③ 再拉当前用户。失败分两种：网络问题（error.response 不存在）标记 NETWORK 等待重试，
   * 服务端明确拒绝（如 cookie 已过期）则清空 token 当作未登录。
   */
  async function initialize() {
    if (initialized.value && initializationError.value === null) return
    if (initializeInFlight) return initializeInFlight
    initializeInFlight = (async () => {
      initialized.value = true
      try {
        if (!accessToken.value) {
          accessToken.value = (await refresh()).accessToken
        }
        currentUser.value = (await fetchCurrentUser())
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
    setAccessToken(response.accessToken)
    initializationError.value = null
    currentUser.value = (await fetchCurrentUser())
  }

  async function restoreAfterDeletion(payload: LoginPayload) {
    const response = await restoreAccountDeletion(payload)
    setAccessToken(response.accessToken)
    initializationError.value = null
    currentUser.value = (await fetchCurrentUser())
  }

  async function signUp(payload: RegisterPayload) {
    const response = await register(payload)
    setAccessToken(response.accessToken)
    initializationError.value = null
    currentUser.value = (await fetchCurrentUser())
  }

  async function signOut() {
    try {
      await logout()
    } finally {
      // finally：即使 logout 请求失败（如断网）也要完成本地清理——用户意图是离开，
      // 不能因为后端不可达就把 token 留在内存里
      // 退出登录即离开该账号：连同浏览器里留存的本账号数据（简历/编辑草稿、待恢复任务、
      // 导入原文、文案草稿）一起清掉，避免共享设备上换人使用后仍能读到上一账号的 PII。
      clearUserLocalData(currentUser.value?.id)
      setAccessToken(null)
    }
  }

  async function updateCurrentUser(displayName: string) {
    currentUser.value = (await updateProfile({ displayName }))
  }

  return { accessToken, currentUser, initialized, initializationError, setAccessToken, initialize, signIn, signUp, signOut, restoreAfterDeletion, updateCurrentUser }
})
