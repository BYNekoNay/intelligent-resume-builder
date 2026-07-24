import { createRouter, createWebHistory } from 'vue-router'

import AppLayout from '@/layouts/AppLayout.vue'
import HomeView from '@/views/HomeView.vue'
import LoginView from '@/views/LoginView.vue'
import NotFoundView from '@/views/NotFoundView.vue'
import RegisterView from '@/views/RegisterView.vue'

import ResumeListView from '@/views/ResumeListView.vue'
import ResumeDetailView from '@/views/ResumeDetailView.vue'
import ResumeEditorView from '@/views/ResumeEditorView.vue'
import CareerMaterialView from '@/views/CareerMaterialView.vue'
import JobDescriptionView from '@/views/JobDescriptionView.vue'
import GenerationWorkbenchView from '@/views/GenerationWorkbenchView.vue'
import GenerationConfirmView from '@/views/GenerationConfirmView.vue'
import MatchResultView from '@/views/MatchResultView.vue'
import ExportView from '@/views/ExportView.vue'
import AiConsentView from '@/views/AiConsentView.vue'
import AtsCheckView from '@/views/AtsCheckView.vue'
import ApplicationsView from '@/views/ApplicationsView.vue'
import MaterialResumeGenerationView from '@/views/MaterialResumeGenerationView.vue'
import InterviewView from '@/views/InterviewView.vue'
import ResumeImportView from '@/views/ResumeImportView.vue'
import CommunicationView from '@/views/CommunicationView.vue'
import InterviewAssetsView from '@/views/InterviewAssetsView.vue'
import AchievementGuidanceView from '@/views/AchievementGuidanceView.vue'
import { useAuthStore } from '@/stores/auth'

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
        { path: 'career-materials', name: 'career-materials', component: CareerMaterialView, meta: { requiresAuth: true } },
        { path: 'generate', name: 'generate', component: GenerationWorkbenchView, meta: { requiresAuth: true } },
        { path: 'generate/confirm', name: 'generate-confirm', component: GenerationConfirmView, meta: { requiresAuth: true } },
        { path: 'jobs', name: 'jobs', component: JobDescriptionView, meta: { requiresAuth: true } },
        { path: 'ai-consent', name: 'ai-consent', component: AiConsentView, meta: { requiresAuth: true } },
        { path: 'ats', name: 'ats-check', component: AtsCheckView, meta: { requiresAuth: true } },
        { path: 'applications', name: 'applications', component: ApplicationsView, meta: { requiresAuth: true } },
        { path: 'material-generation', name: 'material-generation', component: MaterialResumeGenerationView, meta: { requiresAuth: true } },
        { path: 'interviews', name: 'interviews', component: InterviewView, meta: { requiresAuth: true } },
        { path: 'resume-import', name: 'resume-import', component: ResumeImportView, meta: { requiresAuth: true } },
        { path: 'communications', name: 'communications', component: CommunicationView, meta: { requiresAuth: true } },
        { path: 'interview-assets', name: 'interview-assets', component: InterviewAssetsView, meta: { requiresAuth: true } },
        { path: 'achievement-guidance', name: 'achievement-guidance', component: AchievementGuidanceView, meta: { requiresAuth: true } },
        { path: 'match/:matchResultId', name: 'match-result', component: MatchResultView, props: true, meta: { requiresAuth: true } },
        { path: 'exports/:exportTaskId', name: 'export', component: ExportView, props: true, meta: { requiresAuth: true } },
      ],
    },
    { path: '/login', name: 'login', component: LoginView },
    { path: '/register', name: 'register', component: RegisterView },
    { path: '/:pathMatch(.*)*', name: 'not-found', component: NotFoundView },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  await auth.initialize()
  if (to.meta.requiresAuth && !auth.accessToken && auth.initializationError !== 'NETWORK') {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  if ((to.name === 'login' || to.name === 'register') && auth.accessToken) {
    return { name: 'home' }
  }
})

export default router
