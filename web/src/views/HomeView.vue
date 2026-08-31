<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ArrowRight,
  BriefcaseBusiness,
  Check,
  CheckCircle2,
  CircleDot,
  ClipboardList,
  ClipboardCheck,
  FilePenLine,
  FileSearch,
  FileText,
  FolderKanban,
  Import,
  LibraryBig,
  Radar,
  RefreshCw,
  Sparkles,
  Target,
} from 'lucide-vue-next'
import { getSystemHealth, type SystemHealth } from '@/api/system'
import { listResumes, type ResumeSummary } from '@/api/resume'
import { listApplications, type ApplicationRecord } from '@/api/application'
import { listTaskContinuations, type AiTask } from '@/api/ai'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/i18n'

const health = ref<SystemHealth | null>(null)
const healthLoading = ref(true)
const resumes = ref<ResumeSummary[]>([])
const applications = ref<ApplicationRecord[]>([])
const continuations = ref<AiTask[]>([])
const workspaceLoading = ref(false)
const workspaceError = ref(false)
const workspaceLoaded = ref(false)
const auth = useAuthStore()
const { locale, t } = useLocale()

function byUpdatedAtAndId<T extends { id: number; updatedAt: string }>(left: T, right: T) {
  return right.updatedAt.localeCompare(left.updatedAt) || right.id - left.id
}

const latestResume = computed(() => [...resumes.value].sort(byUpdatedAtAndId)[0] ?? null)
const applicationsByRecency = computed(() => [...applications.value].sort(byUpdatedAtAndId))
const interviewingApplications = computed(() => applicationsByRecency.value.filter(item => item.status === 'INTERVIEWING'))
const draftApplications = computed(() => applicationsByRecency.value.filter(item => item.status === 'DRAFT'))
const resumableTasks = computed(() => [...continuations.value].sort(byUpdatedAtAndId))
const primaryPath = computed(() => auth.accessToken && primaryWorkspaceAction.value ? primaryWorkspaceAction.value.to : auth.accessToken ? '/generate' : '/register')
const secondaryPath = computed(() => auth.accessToken ? '/resume-import' : '/login')
const primaryLabel = computed(() => auth.accessToken && primaryWorkspaceAction.value ? t(primaryWorkspaceAction.value.actionKey) : auth.accessToken ? t('home.primaryAuthenticated') : t('home.primaryGuest'))
const secondaryLabel = computed(() => auth.accessToken ? t('home.secondaryAuthenticated') : t('home.secondaryGuest'))

interface WorkspaceAction {
  key: string
  icon: any
  titleKey: string
  descriptionKey: string
  actionKey: string
  to: string
}

const workspaceActions = computed<WorkspaceAction[]>(() => {
  const actions: WorkspaceAction[] = []

  for (const interviewingApplication of interviewingApplications.value) {
    actions.push({
      key: `interview-${interviewingApplication.id}`,
      icon: BriefcaseBusiness,
      titleKey: 'home.nextInterviewTitle',
      descriptionKey: 'home.nextInterviewDesc',
      actionKey: 'home.prepareInterview',
      to: `/interviews?jobDescriptionId=${interviewingApplication.jobDescriptionId}`,
    })
  }

  for (const draftApplication of draftApplications.value) {
    actions.push({
      key: `application-${draftApplication.id}`,
      icon: ClipboardList,
      titleKey: 'home.nextApplicationTitle',
      descriptionKey: 'home.nextApplicationDesc',
      actionKey: 'home.continueApplication',
      to: '/applications',
    })
  }

  for (const task of resumableTasks.value) {
    const selection = task.taskType === 'JOB_MATERIAL_SELECTION'
    const awaitingConfirmation = task.status === 'SUCCESS'
    actions.push({
      key: `task-${task.id}`,
      icon: Sparkles,
      titleKey: selection
        ? (awaitingConfirmation ? 'home.nextSelectionTitle' : 'home.nextSelectionProgressTitle')
        : (awaitingConfirmation ? 'home.nextGenerationTitle' : 'home.nextGenerationProgressTitle'),
      descriptionKey: selection
        ? (awaitingConfirmation ? 'home.nextSelectionDesc' : 'home.nextSelectionProgressDesc')
        : (awaitingConfirmation ? 'home.nextGenerationDesc' : 'home.nextGenerationProgressDesc'),
      actionKey: selection ? 'home.continueSelection' : 'home.continueGeneration',
      to: selection ? `/generate/materials?taskId=${task.id}` : `/generate/confirm?taskId=${task.id}`,
    })
  }

  if (latestResume.value) {
    actions.push({
      key: `resume-${latestResume.value.id}`,
      icon: FileText,
      titleKey: 'home.nextResumeTitle',
      descriptionKey: 'home.nextResumeDesc',
      actionKey: 'home.currentContinue',
      to: `/resumes/${latestResume.value.id}/edit`,
    })
  }

  return actions
})

const primaryWorkspaceAction = computed(() => workspaceActions.value[0] ?? null)
const secondaryWorkspaceActions = computed(() => workspaceActions.value.slice(1))
const showEmptyStartAction = computed(() => workspaceLoaded.value && !workspaceError.value && !primaryWorkspaceAction.value)

const workflow = [
  { icon: LibraryBig, to: '/career-materials', stepKey: 'home.workflowEvidenceStep', titleKey: 'home.workflowEvidenceTitle', descKey: 'home.workflowEvidenceDesc' },
  { icon: FilePenLine, to: '/generate', stepKey: 'home.workflowResumeStep', titleKey: 'home.workflowResumeTitle', descKey: 'home.workflowResumeDesc' },
  { icon: Radar, to: '/ats', stepKey: 'home.workflowCheckStep', titleKey: 'home.workflowCheckTitle', descKey: 'home.workflowCheckDesc' },
  { icon: BriefcaseBusiness, to: '/applications', stepKey: 'home.workflowApplyStep', titleKey: 'home.workflowApplyTitle', descKey: 'home.workflowApplyDesc' },
]

const proofStages = [
  { icon: FolderKanban, labelKey: 'home.proofEvidence', complete: true },
  { icon: Sparkles, labelKey: 'home.proofTailor', complete: true },
  { icon: ClipboardCheck, labelKey: 'home.proofReview', complete: false },
]

function formatDate(value: string) {
  return new Intl.DateTimeFormat(locale.value, { month: 'short', day: 'numeric' }).format(new Date(value))
}

function updatedLabel(value: string) {
  return t('home.currentUpdated').replace('{date}', formatDate(value))
}

async function loadWorkspace() {
  if (!auth.accessToken) return
  workspaceLoading.value = true
  workspaceError.value = false
  const [resumeResult, applicationResult, continuationResult] = await Promise.allSettled([
    listResumes(),
    listApplications(),
    listTaskContinuations(),
  ])

  if (resumeResult.status === 'fulfilled') resumes.value = resumeResult.value.data.data
  else workspaceError.value = true
  if (applicationResult.status === 'fulfilled') applications.value = applicationResult.value.data.data
  else workspaceError.value = true
  if (continuationResult.status === 'fulfilled') continuations.value = continuationResult.value.data.data
  else workspaceError.value = true
  workspaceLoaded.value = true
  workspaceLoading.value = false
}

onMounted(async () => {
  const healthPromise = getSystemHealth()
    .then(response => { health.value = response.data })
    .catch(() => { health.value = null })
    .finally(() => { healthLoading.value = false })

  await loadWorkspace()

  await healthPromise
})
</script>

<template>
  <div class="home-workspace">
    <section class="home-command" :class="{ authenticated: auth.accessToken }" aria-labelledby="home-title">
      <div class="home-command-copy">
        <p class="home-kicker"><Target :size="15" /> {{ t('home.badge') }}</p>
        <h1 id="home-title">{{ t('home.title') }}</h1>
        <p class="home-intro">{{ t('home.description') }}</p>

        <div class="home-actions">
          <RouterLink class="btn-neon btn-primary home-primary-action" :to="primaryPath">
            <Sparkles :size="17" />
            {{ primaryLabel }}
            <ArrowRight :size="16" />
          </RouterLink>
          <RouterLink class="btn-neon btn-ghost" :to="secondaryPath">
            <Import v-if="auth.accessToken" :size="17" />
            <FileText v-else :size="17" />
            {{ secondaryLabel }}
          </RouterLink>
        </div>

        <ul class="home-promises" :aria-label="t('home.promiseLabel')">
          <li><Check :size="14" /> {{ t('home.promiseFacts') }}</li>
          <li><Check :size="14" /> {{ t('home.promiseControl') }}</li>
          <li><Check :size="14" /> {{ t('home.promiseTrace') }}</li>
        </ul>
      </div>

      <aside v-if="!auth.accessToken" class="home-proof-panel" :aria-label="t('home.proofLabel')">
        <header>
          <div>
            <span>{{ t('home.proofExample') }}</span>
            <strong>{{ t('home.proofTitle') }}</strong>
          </div>
          <span class="proof-status"><CircleDot :size="13" /> {{ t('home.proofStatus') }}</span>
        </header>

        <div class="proof-layout">
          <ol class="proof-rail">
            <li v-for="stage in proofStages" :key="stage.labelKey" :class="{ complete: stage.complete }">
              <span><component :is="stage.icon" :size="15" /></span>
              <small>{{ t(stage.labelKey) }}</small>
            </li>
          </ol>

          <div class="resume-snapshot">
            <div class="snapshot-heading">
              <div>
                <strong>{{ t('home.sampleName') }}</strong>
                <span>{{ t('home.sampleRole') }}</span>
              </div>
              <FileSearch :size="20" />
            </div>
            <div class="snapshot-rule" />
            <div class="snapshot-section">
              <span>{{ t('home.sampleExperience') }}</span>
              <strong>{{ t('home.sampleCompany') }}</strong>
              <p>{{ t('home.sampleOutcome') }}</p>
              <small><CheckCircle2 :size="12" /> {{ t('home.sampleVerified') }}</small>
            </div>
            <div class="snapshot-section snapshot-skills">
              <span>{{ t('home.sampleSkills') }}</span>
              <p>{{ t('home.sampleSkillList') }}</p>
            </div>
          </div>
        </div>

        <footer>
          <span>{{ t('home.proofCoverage') }}</span>
          <div class="coverage-meter" aria-hidden="true"><span /></div>
          <strong>84%</strong>
        </footer>
      </aside>
    </section>

    <section v-if="auth.accessToken" class="next-action-workspace" role="region" :aria-label="t('home.nextActionRegion')">
      <header>
        <p class="section-label">{{ t('home.nextActionEyebrow') }}</p>
        <h2>{{ t('home.nextActionRegion') }}</h2>
      </header>

      <div v-if="workspaceLoading" class="current-work-loading" role="status">{{ t('home.nextActionLoading') }}</div>
      <template v-else>
        <article v-if="primaryWorkspaceAction" class="next-action-primary">
          <span class="next-action-icon"><component :is="primaryWorkspaceAction.icon" :size="22" /></span>
          <div>
            <h3>{{ t(primaryWorkspaceAction.titleKey) }}</h3>
            <p>{{ t(primaryWorkspaceAction.descriptionKey) }}</p>
            <small v-if="primaryWorkspaceAction.key.startsWith('resume-') && latestResume">{{ latestResume.title }} · {{ updatedLabel(latestResume.updatedAt) }}</small>
          </div>
          <RouterLink class="btn-neon btn-primary" :to="primaryWorkspaceAction.to">
            {{ t(primaryWorkspaceAction.actionKey) }} <ArrowRight :size="15" />
          </RouterLink>
        </article>
        <article v-else-if="showEmptyStartAction" class="next-action-primary">
          <span class="next-action-icon"><Import :size="22" /></span>
          <div><h3>{{ t('home.nextStartTitle') }}</h3><p>{{ t('home.nextStartDesc') }}</p></div>
          <RouterLink class="btn-neon btn-primary" to="/resume-import">{{ t('home.currentImport') }} <ArrowRight :size="15" /></RouterLink>
        </article>

        <p v-if="workspaceError" class="workspace-status-warning" role="status">
          {{ t('home.nextActionUnavailable') }}
          <button type="button" @click="loadWorkspace"><RefreshCw :size="14" />{{ t('home.nextActionRetry') }}</button>
        </p>

        <div v-if="secondaryWorkspaceActions.length" class="next-action-secondary">
          <span>{{ t('home.otherWork') }}</span>
          <RouterLink v-for="action in secondaryWorkspaceActions" :key="action.key" :to="action.to">
            <component :is="action.icon" :size="16" />
            <span>{{ t(action.actionKey) }}</span>
            <ArrowRight :size="14" />
          </RouterLink>
        </div>
      </template>
    </section>

    <section class="home-workflow" aria-labelledby="workflow-title">
      <header>
        <div>
          <p class="section-label">{{ t('home.workflowEyebrow') }}</p>
          <h2 id="workflow-title">{{ t('home.workflowTitle') }}</h2>
        </div>
        <p>{{ t('home.workflowDesc') }}</p>
      </header>

      <div class="workflow-grid">
        <RouterLink v-for="item in workflow" :key="item.titleKey" class="workflow-card" :to="item.to">
          <div class="workflow-card-head">
            <span class="workflow-icon"><component :is="item.icon" :size="18" /></span>
            <small>{{ t(item.stepKey) }}</small>
          </div>
          <h3>{{ t(item.titleKey) }}</h3>
          <p>{{ t(item.descKey) }}</p>
          <span class="workflow-link">{{ t('home.workflowOpen') }} <ArrowRight :size="15" /></span>
        </RouterLink>
      </div>
    </section>

    <footer class="home-footer">
      <div>
        <Target :size="15" />
        <span>{{ t('home.copyRight') }}</span>
      </div>
      <span v-if="healthLoading" class="service-checking">{{ t('home.serviceChecking') }}</span>
      <span v-else-if="health" class="service-online"><CheckCircle2 :size="14" /> {{ t('home.serviceStatus') }}</span>
      <span v-else class="service-offline">{{ t('home.serviceOffline') }}</span>
      <a href="https://github.com/BYNekoNay/intelligent-resume-builder" target="_blank" rel="noreferrer">{{ t('home.sourceCode') }}</a>
    </footer>
  </div>
</template>

<style scoped>
.home-workspace {
  display: grid;
  gap: 72px;
}

.home-command {
  display: grid;
  grid-template-columns: minmax(0, 1.02fr) minmax(420px, .98fr);
  align-items: center;
  gap: 72px;
  min-height: 540px;
  padding: 28px 0 10px;
}

.home-command.authenticated {
  grid-template-columns: minmax(0, 760px);
  min-height: 390px;
}

.home-command-copy {
  max-width: 650px;
}

.home-kicker,
.section-label {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 18px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 750;
  letter-spacing: 0;
}

.home-kicker {
  padding-bottom: 8px;
  border-bottom: 2px solid var(--highlight);
}

.home-command h1 {
  max-width: 620px;
  margin: 0;
  color: var(--text-primary);
  font-family: var(--font-display);
  font-size: 48px;
  font-weight: 650;
  line-height: 1.16;
  letter-spacing: 0;
  white-space: pre-line;
}

.home-intro {
  max-width: 570px;
  margin: 24px 0 0;
  color: var(--text-secondary);
  font-size: 16px;
  line-height: 1.8;
}

.home-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  margin-top: 30px;
}

.home-primary-action {
  min-width: 190px;
  justify-content: center;
}

.home-promises {
  display: flex;
  flex-wrap: wrap;
  gap: 10px 20px;
  margin: 24px 0 0;
  padding: 0;
  color: var(--text-secondary);
  font-size: 12px;
  list-style: none;
}

.home-promises li {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.home-promises svg {
  color: var(--success);
}

.home-proof-panel {
  overflow: hidden;
  border: 1px solid #1c4e3b;
  border-radius: 8px;
  background: #163d2f;
  box-shadow: 0 24px 60px rgba(22, 61, 47, .18);
  color: #fff;
}

.home-proof-panel > header,
.home-proof-panel > footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  padding: 18px 20px;
}

.home-proof-panel > header {
  border-bottom: 1px solid rgba(255, 255, 255, .13);
}

.home-proof-panel > header div {
  display: grid;
  gap: 2px;
}

.home-proof-panel > header span,
.home-proof-panel > footer span {
  color: rgba(255, 255, 255, .62);
  font-family: var(--font-utility);
  font-size: 10px;
}

.home-proof-panel > header strong {
  font-size: 14px;
}

.proof-status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  color: #b9efd8 !important;
}

.proof-layout {
  display: grid;
  grid-template-columns: 92px minmax(0, 1fr);
  min-height: 350px;
}

.proof-rail {
  display: grid;
  align-content: center;
  gap: 38px;
  margin: 0;
  padding: 24px 16px;
  border-right: 1px solid rgba(255, 255, 255, .13);
  list-style: none;
}

.proof-rail li {
  position: relative;
  display: grid;
  justify-items: center;
  gap: 7px;
  color: rgba(255, 255, 255, .5);
  text-align: center;
}

.proof-rail li:not(:last-child)::after {
  position: absolute;
  top: 35px;
  width: 1px;
  height: 30px;
  background: rgba(255, 255, 255, .2);
  content: '';
}

.proof-rail li.complete:not(:last-child)::after {
  background: #7fd5ae;
}

.proof-rail li > span {
  display: grid;
  width: 31px;
  height: 31px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, .22);
  border-radius: 50%;
}

.proof-rail li.complete > span {
  border-color: #7fd5ae;
  background: rgba(127, 213, 174, .12);
  color: #b9efd8;
}

.proof-rail small {
  font-size: 10px;
  line-height: 1.25;
}

.resume-snapshot {
  width: calc(100% - 44px);
  min-height: 310px;
  margin: 22px;
  padding: 28px 30px;
  background: #fff;
  box-shadow: 0 12px 34px rgba(0, 0, 0, .18);
  color: #17221b;
}

.snapshot-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.snapshot-heading div {
  display: grid;
  gap: 2px;
}

.snapshot-heading strong {
  font-family: var(--font-display);
  font-size: 21px;
}

.snapshot-heading span,
.snapshot-section > span {
  color: #617067;
  font-size: 10px;
  font-weight: 700;
}

.snapshot-heading svg {
  color: var(--highlight);
}

.snapshot-rule {
  height: 2px;
  margin: 17px 0 18px;
  background: #1f674d;
}

.snapshot-section {
  display: grid;
  gap: 5px;
}

.snapshot-section strong {
  font-size: 12px;
}

.snapshot-section p {
  margin: 0;
  color: #445249;
  font-size: 10px;
  line-height: 1.55;
}

.snapshot-section small {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  width: fit-content;
  margin-top: 3px;
  color: #1f674d;
  font-size: 9px;
  font-weight: 700;
}

.snapshot-skills {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid #e4e9e5;
}

.home-proof-panel > footer {
  border-top: 1px solid rgba(255, 255, 255, .13);
}

.coverage-meter {
  flex: 1;
  height: 4px;
  overflow: hidden;
  background: rgba(255, 255, 255, .15);
}

.coverage-meter span {
  display: block;
  width: 84%;
  height: 100%;
  background: #7fd5ae;
}

.home-proof-panel > footer strong {
  color: #b9efd8;
  font-family: var(--font-utility);
  font-size: 13px;
}

.next-action-workspace {
  display: grid;
  gap: 18px;
  padding: 28px 0;
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
}

.next-action-workspace .section-label,
.home-workflow .section-label {
  margin-bottom: 6px;
}

.next-action-workspace h2,
.home-workflow h2 {
  margin: 0;
  font-size: 24px;
  letter-spacing: 0;
}

.next-action-primary {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) auto;
  align-items: center;
  gap: 16px;
  padding: 20px;
  border: 1px solid var(--border);
  border-left: 4px solid var(--accent);
  border-radius: var(--radius-md);
  background: var(--bg-surface);
  box-shadow: var(--shadow-sm);
}

.next-action-icon {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border: 1px solid color-mix(in srgb, var(--accent) 24%, var(--border));
  border-radius: 6px;
  background: var(--accent-light);
  color: var(--accent);
}

.next-action-primary > div {
  display: grid;
  gap: 4px;
}

.next-action-primary h3 {
  margin: 0;
  font-size: 16px;
}

.next-action-primary p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 13px;
  line-height: 1.6;
}

.next-action-primary small,
.current-work-loading {
  color: var(--text-tertiary);
  font-size: 12px;
}

.next-action-secondary {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.next-action-secondary > span {
  margin-right: 4px;
  color: var(--text-tertiary);
  font-size: 11px;
  font-weight: 700;
}

.next-action-secondary a {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-height: 36px;
  padding: 7px 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-sm);
  color: var(--accent);
  background: var(--bg-surface);
  font-size: 12px;
  font-weight: 650;
}

.next-action-secondary a:hover {
  border-color: var(--accent);
  background: var(--accent-light);
  text-decoration: none;
}

.workspace-status-warning {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0;
  padding: 10px 12px;
  border-left: 3px solid var(--warning);
  color: var(--warning);
  background: var(--warning-light);
  font-size: 12px;
}

.workspace-status-warning button {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: none;
  padding: 5px 8px;
  border: 1px solid currentColor;
  border-radius: var(--radius-sm);
  color: inherit;
  background: transparent;
  cursor: pointer;
  font-size: 11px;
  font-weight: 700;
}

.home-workflow {
  display: grid;
  gap: 28px;
}

.home-workflow > header {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(260px, 430px);
  align-items: end;
  gap: 40px;
}

.home-workflow > header > p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 14px;
  line-height: 1.7;
}

.workflow-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border-top: 1px solid var(--border);
  border-bottom: 1px solid var(--border);
}

.workflow-card {
  display: grid;
  min-height: 250px;
  padding: 24px 22px;
  border-right: 1px solid var(--border);
  color: var(--text-primary);
  transition: background .18s ease, color .18s ease;
}

.workflow-card:last-child {
  border-right: 0;
}

.workflow-card:hover {
  background: var(--accent);
  color: #fff;
  text-decoration: none;
}

.workflow-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.workflow-icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 1px solid var(--border);
  border-radius: 6px;
  background: var(--bg-surface);
  color: var(--accent);
}

.workflow-card-head small {
  color: var(--text-tertiary);
  font-family: var(--font-utility);
  font-size: 10px;
}

.workflow-card h3 {
  align-self: end;
  margin: 30px 0 8px;
  color: inherit;
  font-size: 17px;
}

.workflow-card p {
  margin: 0;
  color: var(--text-secondary);
  font-size: 12px;
  line-height: 1.6;
}

.workflow-link {
  display: inline-flex;
  align-items: center;
  align-self: end;
  gap: 5px;
  margin-top: 20px;
  color: var(--accent);
  font-size: 12px;
  font-weight: 750;
}

.workflow-card:hover p,
.workflow-card:hover .workflow-card-head small,
.workflow-card:hover .workflow-link {
  color: rgba(255, 255, 255, .78);
}

.home-footer {
  display: flex;
  align-items: center;
  gap: 18px;
  padding: 22px 0 0;
  border-top: 1px solid var(--border-soft);
  color: var(--text-tertiary);
  font-size: 12px;
}

.home-footer > div,
.service-online {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.home-footer > div {
  margin-right: auto;
}

.service-online {
  color: var(--success);
}

.service-offline {
  color: var(--danger);
}

.home-footer a {
  color: var(--text-secondary);
}

@media (max-width: 1020px) {
  .home-command {
    grid-template-columns: 1fr;
    gap: 42px;
    min-height: 0;
  }

  .home-command-copy {
    max-width: 760px;
  }

  .home-proof-panel {
    width: min(100%, 680px);
  }

  .workflow-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .workflow-card:nth-child(2) {
    border-right: 0;
  }

  .workflow-card:nth-child(-n + 2) {
    border-bottom: 1px solid var(--border);
  }
}

@media (max-width: 720px) {
  .home-workspace {
    gap: 54px;
  }

  .home-command {
    padding-top: 10px;
  }

  .home-command h1 {
    font-size: 36px;
  }

  .home-intro {
    font-size: 15px;
  }

  .home-workflow > header {
    grid-template-columns: 1fr;
    gap: 18px;
  }

  .next-action-primary {
    grid-template-columns: 48px minmax(0, 1fr);
  }

  .next-action-primary > a {
    grid-column: 2;
    justify-self: start;
  }
}

@media (max-width: 520px) {
  .home-command h1 {
    font-size: 31px;
  }

  .home-actions {
    align-items: stretch;
    flex-direction: column;
  }

  .home-actions a {
    justify-content: center;
    width: 100%;
  }

  .next-action-primary {
    grid-template-columns: 1fr;
    padding: 18px 16px;
  }

  .next-action-primary > a {
    grid-column: auto;
    width: 100%;
    justify-content: center;
  }

  .workspace-status-warning {
    align-items: flex-start;
    flex-direction: column;
  }

  .proof-layout {
    grid-template-columns: 66px minmax(0, 1fr);
  }

  .proof-rail {
    padding: 20px 8px;
  }

  .proof-rail small {
    font-size: 9px;
  }

  .resume-snapshot {
    width: calc(100% - 24px);
    margin: 12px;
    padding: 22px 18px;
  }

  .home-proof-panel > header,
  .home-proof-panel > footer {
    padding: 15px 14px;
  }

  .workflow-grid {
    grid-template-columns: 1fr;
  }

  .workflow-card,
  .workflow-card:nth-child(2) {
    min-height: 220px;
    border-right: 0;
    border-bottom: 1px solid var(--border);
  }

  .workflow-card:last-child {
    border-bottom: 0;
  }

  .home-footer {
    align-items: flex-start;
    flex-direction: column;
    gap: 10px;
  }

  .home-footer > div {
    margin-right: 0;
  }
}
</style>
