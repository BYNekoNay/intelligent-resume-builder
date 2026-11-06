/**
 * 职业素材摘要列表的共享状态（列表页、生成工作台等复用）。
 *
 * 给第一天来的你：「职业素材」是用户拆解自己经历后存下的一条条证据（工作经历、项目、技能等，
 * 类型见 api/careerMaterial.ts 的 MaterialType），后续生成简历、选材都从这里取料。
 *
 * 使用方：GenerationWorkbenchView（生成工作台的素材选择区）。
 *
 * 持久化：仅内存，页面刷新即空，下次进入重新 load()。
 *
 * 边界：只缓存 listMaterials 的摘要结果（不含正文 contentJson），正文需要调用方自行 getMaterial；
 * load(type) 是「按类型覆盖式加载」，每次都会整体替换 items，不做多类型合并缓存——
 * 想同时展示多类型请在调用方自行聚合，或改用 api/careerMaterial.ts 的 searchMaterials 分页接口。
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
