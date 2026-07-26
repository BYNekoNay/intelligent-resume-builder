import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  listMaterials,
  type CareerMaterialSummary,
  type MaterialType,
} from '@/api/careerMaterial'

export const useCareerMaterialStore = defineStore('career-material', () => {
  const items = ref<CareerMaterialSummary[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function load(type?: MaterialType) {
    loading.value = true
    error.value = null
    try {
      const res = await listMaterials(type)
      items.value = res.data.data
    } catch (e) {
      error.value = e instanceof Error ? e.message : '加载失败'
    } finally {
      loading.value = false
    }
  }

  return { items, loading, error, load }
})
