import { computed, ref } from 'vue'
import { listJobs, type JobDescription } from '@/api/jobDescription'
import { listResumes, listVersions, type ResumeSummary, type ResumeVersion } from '@/api/resume'

/** Loads the choices shared by workflows that need a resume version and a target job. */
export function useResumeJobOptions() {
  const resumes = ref<ResumeSummary[]>([])
  const jobs = ref<JobDescription[]>([])
  const versions = ref<ResumeVersion[]>([])
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
      error.value = '可选简历或 JD 无法加载，请检查网络后重试。'
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
      error.value = '该简历的版本无法加载，请重新选择简历。'
    }
  }

  return { resumes, jobs, versions, selectedResumeId, loading, error, hasVersions, load, loadVersions }
}
