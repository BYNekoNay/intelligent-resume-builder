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

  async function load() {
    loading.value = true
    error.value = ''
    try {
      const [resumeResponse, jobResponse] = await Promise.all([listResumes(), listJobs()])
      resumes.value = resumeResponse.data.data
      jobs.value = jobResponse.data.data
      if (selectedResumeId.value == null && resumes.value.length > 0) selectedResumeId.value = resumes.value[0].id
      await loadVersions()
    } catch {
      error.value = t('jobOptions.loadError')
    } finally {
      loading.value = false
    }
  }

  async function loadVersions() {
    versions.value = []
    if (selectedResumeId.value == null) return
    try {
      versions.value = (await listVersions(selectedResumeId.value)).data.data
    } catch {
      error.value = t('jobOptions.versionsLoadError')
    }
  }

  return { resumes, jobs, versions, selectedResumeId, loading, error, hasVersions, load, loadVersions }
}
