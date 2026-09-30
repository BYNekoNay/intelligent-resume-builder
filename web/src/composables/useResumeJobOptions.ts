import { computed, ref } from 'vue'
import { listJobs, type JobDescriptionSummary } from '@/api/jobDescription'
import { listResumes, listVersions, type ResumeSummary, type ResumeVersionSummary } from '@/api/resume'
import { useLocale } from '@/i18n'

/** Loads the choices shared by workflows that need a resume version and a target job. */
export function useResumeJobOptions() {
  const { t } = useLocale()
  const resumes = ref<ResumeSummary[]>([])
  const jobs = ref<JobDescriptionSummary[]>([])
  const versions = ref<ResumeVersionSummary[]>([])
  const selectedResumeId = ref<number | null>(null)
  const loading = ref(false)
  const error = ref('')
  const hasVersions = computed(() => versions.value.length > 0)

  // #58：请求世代（epoch）。选择器连续切换时旧响应可能晚于新响应返回，
  // 只有「最新一次请求」可以写入状态，否则旧简历的版本会覆盖新选择。
  let loadEpoch = 0
  let versionEpoch = 0

  async function load() {
    const epoch = ++loadEpoch
    // 记录本次加载开始时的选择。若在选项请求返回前调用方已选定另一份简历
    //（典型场景：投递页「编辑」需定位被引用旧版本所属的简历），
    // 则不得在下方按「当前选择」再拉一次版本列表——那会对同一份简历发出第二次
    // 列表请求，与调用方已发起的加载重复（#33「只重载目标简历一次」在 CI 偶发
    // 「期望 1 实际 2」的真实成因，已由 e2e 延迟选项响应确定性复现）。
    const selectionBeforeLoad = selectedResumeId.value
    loading.value = true
    error.value = ''
    try {
      const [resumeResponse, jobResponse] = await Promise.all([listResumes(), listJobs()])
      if (epoch !== loadEpoch) return
      resumes.value = resumeResponse.data.data
      jobs.value = jobResponse.data.data
      const assignedInitialSelection = selectedResumeId.value == null && resumes.value.length > 0
      if (assignedInitialSelection) selectedResumeId.value = resumes.value[0].id
      // 仅在「本次加载确立了选择」或「选择自加载开始未变」时加载版本列表：
      // 前者是首屏默认选中，后者保证重试/重载时版本列表与当前选择一致。
      if (assignedInitialSelection || selectedResumeId.value === selectionBeforeLoad) {
        await loadVersions()
      }
    } catch {
      if (epoch !== loadEpoch) return
      error.value = t('jobOptions.loadError')
    } finally {
      if (epoch === loadEpoch) loading.value = false
    }
  }

  async function loadVersions() {
    const epoch = ++versionEpoch
    versions.value = []
    const resumeId = selectedResumeId.value
    if (resumeId == null) return
    try {
      const response = await listVersions(resumeId)
      if (epoch !== versionEpoch) return
      versions.value = response.data.data
    } catch {
      if (epoch !== versionEpoch) return
      error.value = t('jobOptions.versionsLoadError')
    }
  }

  return { resumes, jobs, versions, selectedResumeId, loading, error, hasVersions, load, loadVersions }
}
