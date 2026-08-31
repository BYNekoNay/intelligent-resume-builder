<script setup lang="ts">
import { computed } from 'vue'
import { ChevronLeft, ChevronRight, Database, Pencil, RotateCcw, Trash2 } from 'lucide-vue-next'
import type { CareerMaterialSearchPage } from '@/api/careerMaterial'
import { useLocale } from '@/i18n'
import { MATERIAL_TYPE_OPTIONS, USAGE_OPTIONS, optionLabel } from './options'

const props = defineProps<{
  result: CareerMaterialSearchPage
  loading: boolean
  error: string
  selectedId: number | null
}>()

const emit = defineEmits<{
  select: [id: number]
  edit: [id: number]
  delete: [id: number]
  retry: []
  page: [page: number]
}>()

const { locale, t } = useLocale()
const pages = computed(() => {
  const total = props.result.totalPages
  if (total <= 1) return []
  const start = Math.max(0, Math.min(props.result.page - 2, total - 5))
  return Array.from({ length: Math.min(5, total) }, (_, index) => start + index)
})

function formatDate(value: string) {
  return new Intl.DateTimeFormat(locale.value === 'zh-CN' ? 'zh-CN' : 'en-US', { month: 'short', day: 'numeric' })
    .format(new Date(value))
}
</script>

<template>
  <div class="library-results" :aria-busy="loading">
    <div v-if="loading" class="result-state" role="status">
      <span class="loading-line"></span><span class="loading-line short"></span><span class="loading-line"></span>
      <p>{{ t('careerMaterial.loading') }}</p>
    </div>
    <div v-else-if="error" class="result-state error-state" role="alert">
      <RotateCcw :size="20" />
      <p>{{ error }}</p>
      <button class="btn-neon btn-ghost" type="button" @click="emit('retry')">{{ t('careerMaterial.retry') }}</button>
    </div>
    <div v-else-if="!result.items.length" class="result-state empty-state">
      <Database :size="22" />
      <strong>{{ t('careerMaterial.emptyTitle') }}</strong>
      <p>{{ t('careerMaterial.emptyWorkspace') }}</p>
    </div>
    <div v-else class="material-rows">
      <article
        v-for="item in result.items"
        :key="item.id"
        class="material-list-row"
        :class="{ selected: selectedId === item.id }"
        :aria-label="item.title"
      >
        <button class="row-select" type="button" @click="emit('select', item.id)">
          <span class="row-heading">
            <span class="row-type">{{ optionLabel(MATERIAL_TYPE_OPTIONS, item.materialType, t) }}</span>
            <span :class="`usage usage-${item.usagePreference.toLowerCase()}`">{{ optionLabel(USAGE_OPTIONS, item.usagePreference, t) }}</span>
            <time :datetime="item.updatedAt">{{ formatDate(item.updatedAt) }}</time>
          </span>
          <strong>{{ item.title }}</strong>
          <span v-if="item.excerpt" class="row-excerpt">{{ item.excerpt }}</span>
        </button>
        <div class="row-actions">
          <button type="button" :title="t('common.edit')" :aria-label="`${t('common.edit')} ${item.title}`" @click="emit('edit', item.id)"><Pencil :size="15" /></button>
          <button class="danger" type="button" :title="t('careerMaterial.deleteAction')" :aria-label="`${t('careerMaterial.deleteAction')} ${item.title}`" @click="emit('delete', item.id)"><Trash2 :size="15" /></button>
        </div>
      </article>
    </div>

    <footer v-if="result.totalPages > 1" class="pagination" :aria-label="t('careerMaterial.paginationLabel')">
      <button type="button" :disabled="result.page === 0" :title="t('careerMaterial.previousPage')" :aria-label="t('careerMaterial.previousPage')" @click="emit('page', result.page - 1)"><ChevronLeft :size="16" /></button>
      <button v-for="pageNumber in pages" :key="pageNumber" type="button" :class="{ active: pageNumber === result.page }" :aria-label="`${t('careerMaterial.paginationLabel')} ${pageNumber + 1}`" :aria-current="pageNumber === result.page ? 'page' : undefined" @click="emit('page', pageNumber)">{{ pageNumber + 1 }}</button>
      <button type="button" :disabled="result.page >= result.totalPages - 1" :title="t('careerMaterial.nextPage')" :aria-label="t('careerMaterial.nextPage')" @click="emit('page', result.page + 1)"><ChevronRight :size="16" /></button>
    </footer>
  </div>
</template>

<style scoped>
.library-results { min-height: 0; overflow: auto; }
.material-rows { display: grid; }
.material-list-row { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; border-bottom: 1px solid var(--border-soft); background: var(--bg-surface); }
.material-list-row:hover { background: color-mix(in srgb, var(--accent-light) 35%, var(--bg-surface)); }
.material-list-row.selected { background: var(--accent-light); box-shadow: inset 3px 0 var(--accent); }
.row-select { display: grid; min-width: 0; gap: 6px; padding: 14px 12px 14px 16px; border: 0; color: var(--text-primary); background: transparent; text-align: left; cursor: pointer; }
.row-select > strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.row-heading { display: flex; min-width: 0; align-items: center; gap: 6px; color: var(--text-tertiary); font-size: 9px; }
.row-heading time { margin-left: auto; white-space: nowrap; }
.row-type, .usage { padding: 2px 5px; border: 1px solid var(--border); border-radius: 3px; font-weight: 700; }
.usage-preferred { border-color: color-mix(in srgb, var(--highlight) 35%, var(--border)); color: var(--highlight); }
.usage-excluded { color: var(--text-tertiary); text-decoration: line-through; }
.row-excerpt { display: -webkit-box; overflow: hidden; color: var(--text-secondary); font-size: 11px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.row-actions { display: flex; gap: 4px; padding-right: 10px; }
.row-actions button, .pagination button { display: grid; width: 32px; height: 32px; place-items: center; padding: 0; border: 1px solid transparent; border-radius: 5px; color: var(--text-secondary); background: transparent; cursor: pointer; }
.row-actions button:hover, .pagination button:hover:not(:disabled) { border-color: var(--border); color: var(--accent); background: var(--bg-surface); }
.row-actions button.danger:hover { color: var(--danger); background: var(--danger-light); }
.result-state { display: grid; min-height: 260px; place-items: center; align-content: center; gap: 10px; padding: 30px; color: var(--text-secondary); text-align: center; }
.result-state p { max-width: 340px; margin: 0; font-size: 12px; line-height: 1.6; }
.empty-state svg { color: var(--accent); }
.empty-state strong { color: var(--text-primary); font-size: 14px; }
.error-state svg { color: var(--danger); }
.loading-line { width: min(100%, 360px); height: 10px; border-radius: 3px; background: var(--border-soft); animation: pulse 1.2s ease-in-out infinite alternate; }
.loading-line.short { width: min(70%, 260px); }
.pagination { display: flex; align-items: center; justify-content: center; gap: 4px; padding: 14px; border-top: 1px solid var(--border); }
.pagination button { border-color: var(--border); background: var(--bg-surface); font-size: 11px; }
.pagination button.active { border-color: var(--accent); color: #fff; background: var(--accent); }
.pagination button:disabled { opacity: .4; cursor: not-allowed; }
@keyframes pulse { to { opacity: .45; } }
@media (prefers-reduced-motion: reduce) { .loading-line { animation: none; } }
@media (max-width: 767px) { .library-results { overflow: visible; } .row-actions { padding-right: 6px; } .row-select { padding-left: 14px; } }
</style>
