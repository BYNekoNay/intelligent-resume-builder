import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchCurrentUser, login, logout, refresh, register, updateProfile, type CurrentUser, type LoginPayload, type RegisterPayload } from '@/api/auth'

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

  async function initialize() {
    if (initialized.value) return
    initialized.value = true
    initializationError.value = null
    try {
      if (!accessToken.value) {
        accessToken.value = (await refresh()).data.accessToken
      }
      currentUser.value = (await fetchCurrentUser()).data
    } catch (error: any) {
      if (!error?.response) {
        initializationError.value = 'NETWORK'
        return
      }
      setAccessToken(null)
    }
  }

  async function signIn(payload: LoginPayload) {
    const response = await login(payload)
    setAccessToken(response.data.accessToken)
    currentUser.value = (await fetchCurrentUser()).data
  }

  async function signUp(payload: RegisterPayload) {
    const response = await register(payload)
    setAccessToken(response.data.accessToken)
    currentUser.value = (await fetchCurrentUser()).data
  }

  async function signOut() {
    try {
      await logout()
    } finally {
      setAccessToken(null)
    }
  }

  async function updateCurrentUser(displayName: string) {
    currentUser.value = (await updateProfile({ displayName })).data
  }

  return { accessToken, currentUser, initialized, initializationError, setAccessToken, initialize, signIn, signUp, signOut, updateCurrentUser }
})
