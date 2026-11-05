/**
 * 职业素材摘要列表的共享状态（列表页、生成工作台等复用）。
 * 边界：只缓存 listMaterials 的摘要结果，正文需要调用方自行 getMaterial；
 * load(type) 是「按类型覆盖式加载」，每次都会整体替换 items，不做多类型合并缓存。
 */
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
      items.value = res
    } catch (e) {
      error.value = e instanceof Error ? e.message : '加载失败'
    } finally {
      loading.value = false
    }
  }

  return { items, loading, error, load }
})
