import type { Component } from 'vue'
import {
  Activity,
  BriefcaseBusiness,
  FilePenLine,
  FileText,
  LayoutDashboard,
  LibraryBig,
  NotebookPen,
  Radar,
  Send,
  Sparkles,
  Target,
} from 'lucide-vue-next'

export const navigationGroupKeys = ['career', 'resume', 'prepare', 'applications'] as const
export type NavigationGroupKey = typeof navigationGroupKeys[number]

export interface NavigationItem {
  to: string
  key: string
  icon: Component
  labelKey: string
  descriptionKey?: string
}

export interface NavigationGroup {
  labelKey: string
  items: NavigationItem[]
}

export const navigationGroups: Record<NavigationGroupKey, NavigationGroup> = {
  career: {
    labelKey: 'navGroups.career.label',
    items: [
      { to: '/', key: 'home', icon: LayoutDashboard, labelKey: 'navGroups.career.home' },
      { to: '/career-materials', key: 'materials', icon: Sparkles, labelKey: 'navGroups.career.materials' },
      { to: '/resume-import', key: 'imports', icon: FileText, labelKey: 'navGroups.career.imports' },
    ],
  },
  resume: {
    labelKey: 'navGroups.resume.label',
    items: [
      { to: '/resumes', key: 'resumes', icon: NotebookPen, labelKey: 'navGroups.resume.resumes' },
      { to: '/generate', key: 'generate', icon: Sparkles, labelKey: 'navGroups.resume.generate', descriptionKey: 'navGroups.resume.generateDesc' },
      { to: '/material-generation', key: 'materialGeneration', icon: Sparkles, labelKey: 'navGroups.resume.materialGeneration', descriptionKey: 'navGroups.resume.materialGenerationDesc' },
      { to: '/achievement-guidance', key: 'achievements', icon: Target, labelKey: 'navGroups.resume.achievements' },
    ],
  },
  prepare: {
    labelKey: 'navGroups.prepare.label',
    items: [
      { to: '/jobs', key: 'jobs', icon: FileText, labelKey: 'navGroups.prepare.jobs' },
      { to: '/ats', key: 'ats', icon: Activity, labelKey: 'navGroups.prepare.ats' },
      { to: '/interviews', key: 'interviews', icon: Sparkles, labelKey: 'navGroups.prepare.interviews' },
      { to: '/interview-assets', key: 'answerAssets', icon: NotebookPen, labelKey: 'navGroups.prepare.answerAssets' },
    ],
  },
  applications: {
    labelKey: 'navGroups.applications.label',
    items: [
      { to: '/communications', key: 'communications', icon: Send, labelKey: 'navGroups.applications.communications' },
      { to: '/applications', key: 'applications', icon: Send, labelKey: 'navGroups.applications.applications' },
    ],
  },
}

export interface WorkflowStep {
  icon: Component
  to: string
  stepKey: string
  titleKey: string
  descKey: string
}

export const navigationWorkflow: WorkflowStep[] = [
  { icon: LibraryBig, to: '/career-materials', stepKey: 'home.workflowEvidenceStep', titleKey: 'home.workflowEvidenceTitle', descKey: 'home.workflowEvidenceDesc' },
  { icon: FilePenLine, to: '/generate', stepKey: 'home.workflowResumeStep', titleKey: 'home.workflowResumeTitle', descKey: 'home.workflowResumeDesc' },
  { icon: Radar, to: '/ats', stepKey: 'home.workflowCheckStep', titleKey: 'home.workflowCheckTitle', descKey: 'home.workflowCheckDesc' },
  { icon: BriefcaseBusiness, to: '/applications', stepKey: 'home.workflowApplyStep', titleKey: 'home.workflowApplyTitle', descKey: 'home.workflowApplyDesc' },
]

export const navigationTargets = [
  ...navigationGroupKeys.flatMap(group => navigationGroups[group].items.map(item => ({ to: item.to, source: `navGroups.${group}.${item.key}` }))),
  ...navigationWorkflow.map(step => ({ to: step.to, source: step.stepKey })),
  { to: '/account', source: 'account.title' },
  { to: '/ai-consent', source: 'actions.aiConsent' },
  { to: '/login', source: 'actions.signIn' },
  { to: '/register', source: 'actions.signUp' },
]

export function validateNavigationTargets(targets: readonly { to: string; source: string }[], routePaths: ReadonlySet<string>) {
  const failures: string[] = []
  for (const target of targets) {
    if (!routePaths.has(target.to)) failures.push(`navigation target has no route: ${target.to} (${target.source})`)
  }
  return failures
}
