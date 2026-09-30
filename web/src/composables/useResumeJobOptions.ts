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
    loading.value = true
    error.value = ''
    try {
      const [resumeResponse, jobResponse] = await Promise.all([listResumes(), listJobs()])
      if (epoch !== loadEpoch) return
      resumes.value = resumeResponse.data.data
      jobs.value = jobResponse.data.data
      if (selectedResumeId.value == null && resumes.value.length > 0) selectedResumeId.value = resumes.value[0].id
      await loadVersions()
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
