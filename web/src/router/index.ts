/**
 * 路由表与全局导航守卫。
 *
 * 【这个文件是干嘛的】整个前端的「路网图」：每个 URL 对应哪个页面组件、进入前要做什么检查。
 * 除 AppLayout / NotFound / RouteLoadError 外，所有页面都是懒加载路由
 * （懒加载 = 用户第一次访问该页面时才去下载它的 JS 代码，减小首屏体积）。
 *
 * 【守卫规则】requiresAuth 由 beforeEach 统一拦截：
 * - 未登录且不是网络错误 → 重定向到 login 并带 redirect 参数（登录后跳回原页面）；
 * - 已登录访问 login/register → 直接回首页（登录页不给已登录用户看）；
 * - requiresDesktop 的页面先探测桌面端点是否可用，不可用送回首页。
 *
 * 【易错点】发版后旧的 JS 分包文件（chunk）可能已从服务器删除，用户浏览器里缓存的旧页面
 * 引用不到新文件就会加载失败（惰性分包失败）。走 recoverStaleLazyChunk：同一路径只强刷一次，
 * 二次失败落到 route-load-error 兜底页，避免无限刷新循环。
 */
import { createRouter, createWebHistory } from 'vue-router'

import AppLayout from '@/layouts/AppLayout.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import RouteLoadErrorView from '@/views/RouteLoadErrorView.vue'
import { useAuthStore } from '@/stores/auth'
import { lazyChunkRetryKey } from './lazyChunkRecovery'
import { navigationTargets, validateNavigationTargets } from '@/navigation/registry'
import { useDesktopMode } from '@/composables/useDesktopMode'

// 首屏三个页面也懒加载（2026-10-29 拆包）：
// 改造前 HomeView（1025 行）/Login/Register 是静态 import，全被塞进主包，
// 于是「只打开登录页」的用户也要下载整个工作台。三者都已在路由守卫里按需触发。
// NotFound / RouteLoadError 刻意保持静态：错误兜底页不能再依赖一次网络加载。
const HomeView = () => import('@/views/HomeView.vue')
const LoginView = () => import('@/views/LoginView.vue')
const RegisterView = () => import('@/views/RegisterView.vue')
// 隐私政策（B6-4）：纯静态页，未登录也必须可达（登录页与页脚都有入口）
const PrivacyPolicyView = () => import('@/views/PrivacyPolicyView.vue')

const ResumeListView = () => import('@/views/ResumeListView.vue')
const ResumeDetailView = () => import('@/views/ResumeDetailView.vue')
const ResumeEditorView = () => import('@/views/ResumeEditorView.vue')
const CompareVersionsView = () => import('@/views/CompareVersionsView.vue')
const CareerMaterialView = () => import('@/views/CareerMaterialView.vue')
const JobDescriptionView = () => import('@/views/JobDescriptionView.vue')
const GenerationWorkbenchView = () => import('@/views/GenerationWorkbenchView.vue')
const MaterialSelectionConfirmView = () => import('@/views/MaterialSelectionConfirmView.vue')
const GenerationConfirmView = () => import('@/views/GenerationConfirmView.vue')
const MatchResultView = () => import('@/views/MatchResultView.vue')
const ExportView = () => import('@/views/ExportView.vue')
const AiConsentView = () => import('@/views/AiConsentView.vue')
const AtsCheckView = () => import('@/views/AtsCheckView.vue')
const ApplicationsView = () => import('@/views/ApplicationsView.vue')
const MaterialResumeGenerationView = () => import('@/views/MaterialResumeGenerationView.vue')
const InterviewView = () => import('@/views/InterviewView.vue')
const InterviewHistoryView = () => import('@/views/InterviewHistoryView.vue')
const ResumeImportView = () => import('@/views/ResumeImportView.vue')
const CommunicationView = () => import('@/views/CommunicationView.vue')
const InterviewAssetsView = () => import('@/views/InterviewAssetsView.vue')
const AchievementGuidanceView = () => import('@/views/AchievementGuidanceView.vue')
const AccountView = () => import('@/views/AccountView.vue')
const DesktopWorkspaceView = () => import('@/views/DesktopWorkspaceView.vue')

const dynamicImportFailure = /Failed to fetch dynamically imported module|Importing a module script failed|Loading chunk .* failed|ChunkLoadError/i

function isDynamicImportFailure(error: unknown) {
  const message = error instanceof Error ? error.message : String(error)
  return dynamicImportFailure.test(message)
}

function recoverStaleLazyChunk(error: unknown, fullPath: string) {
  // 旧页面引用的发版前 chunk 已被服务器删除时，整页刷新一次就能拿到新版本的引用。
  // 用 sessionStorage 记「这个路径已经刷过一次」：再失败就直接放弃（返回 false 交给兜底页），
  // 防止刷新本身又触发同一个加载失败、形成无限刷新循环。
  if (!isDynamicImportFailure(error)) return false
  try {
    const key = lazyChunkRetryKey(fullPath)
    if (sessionStorage.getItem(key)) return false
    sessionStorage.setItem(key, '1')
    window.location.replace(fullPath)
    return true
  } catch {
    // Without session storage, a reload could become an unbounded loop.
    return false
  }
}

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', name: 'home', component: HomeView },
        { path: 'resumes', name: 'resume-list', component: ResumeListView, meta: { requiresAuth: true } },
        { path: 'resumes/:id', name: 'resume-detail', component: ResumeDetailView, props: true, meta: { requiresAuth: true } },
        { path: 'resumes/:id/edit', name: 'resume-editor', component: ResumeEditorView, props: true, meta: { requiresAuth: true } },
        { path: 'resumes/:id/compare', name: 'resume-compare', component: CompareVersionsView, props: true, meta: { requiresAuth: true } },
        { path: 'career-materials', name: 'career-materials', component: CareerMaterialView, meta: { requiresAuth: true } },
        { path: 'generate', name: 'generate', component: GenerationWorkbenchView, meta: { requiresAuth: true } },
        { path: 'generate/materials', name: 'generate-materials', component: MaterialSelectionConfirmView, meta: { requiresAuth: true } },
        { path: 'generate/confirm', name: 'generate-confirm', component: GenerationConfirmView, meta: { requiresAuth: true } },
        { path: 'jobs', name: 'jobs', component: JobDescriptionView, meta: { requiresAuth: true } },
        { path: 'ai-consent', name: 'ai-consent', component: AiConsentView, meta: { requiresAuth: true } },
        { path: 'account', name: 'account', component: AccountView, meta: { requiresAuth: true } },
        { path: 'route-load-error', name: 'route-load-error', component: RouteLoadErrorView },
        { path: 'ats', name: 'ats-check', component: AtsCheckView, meta: { requiresAuth: true } },
        { path: 'applications', name: 'applications', component: ApplicationsView, meta: { requiresAuth: true } },
        { path: 'material-generation', name: 'material-generation', component: MaterialResumeGenerationView, meta: { requiresAuth: true } },
        { path: 'interviews', name: 'interviews', component: InterviewView, meta: { requiresAuth: true } },
        { path: 'interviews/history', name: 'interview-history', component: InterviewHistoryView, meta: { requiresAuth: true } },
        { path: 'resume-import', name: 'resume-import', component: ResumeImportView, meta: { requiresAuth: true } },
        { path: 'communications', name: 'communications', component: CommunicationView, meta: { requiresAuth: true } },
        { path: 'interview-assets', name: 'interview-assets', component: InterviewAssetsView, meta: { requiresAuth: true } },
        { path: 'achievement-guidance', name: 'achievement-guidance', component: AchievementGuidanceView, meta: { requiresAuth: true } },
        { path: 'match/:matchResultId', name: 'match-result', component: MatchResultView, props: true, meta: { requiresAuth: true } },
        { path: 'exports/:exportTaskId', name: 'export', component: ExportView, props: true, meta: { requiresAuth: true } },
        { path: 'desktop', name: 'desktop-workspace', component: DesktopWorkspaceView, meta: { requiresAuth: true, requiresDesktop: true } },
      ],
    },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/register', name: 'register', component: RegisterView },
    { path: '/privacy', name: 'privacy', component: PrivacyPolicyView },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
  ],
})

const navigationRoutePaths = new Set(router.getRoutes()
  .filter(route => typeof route.path === 'string' && !route.path.includes(':'))
  .map(route => route.path))
const navigationFailures = validateNavigationTargets(navigationTargets, navigationRoutePaths)
if (navigationFailures.length) {
  throw new Error(`Navigation registry is inconsistent:\n${navigationFailures.join('\n')}`)
}

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.initialize()
  if (to.meta.requiresAuth && !auth.accessToken && auth.initializationError !== 'NETWORK') {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if ((to.name === 'login' || to.name === 'register') && auth.accessToken) {
    return { name: 'home' }
  }
  if (to.meta.requiresDesktop) {
    // 桌面端点在在线部署下不存在，探测失败就把用户送回工作台，而不是让页面报一堆 404。
    // 这里 await 是刻意的：只有访问桌面页面时才阻塞，其他页面在布局挂载后异步探测。
    const { probe } = useDesktopMode()
    const desktopAvailable = await probe()
    if (!desktopAvailable) return { name: 'home' }
  }
})

router.onError((error, to) => {
  const fullPath = to?.fullPath ?? router.currentRoute.value.fullPath
  if (recoverStaleLazyChunk(error, fullPath) || !isDynamicImportFailure(error)) return
  void router.replace({ name: 'route-load-error', query: { retry: fullPath } })
})

router.afterEach((to, _from, failure) => {
  if (failure) return
  try {
    sessionStorage.removeItem(lazyChunkRetryKey(to.fullPath))
  } catch {
    // Storage is optional; recovery simply remains unavailable in this browser.
  }
})

export default router
