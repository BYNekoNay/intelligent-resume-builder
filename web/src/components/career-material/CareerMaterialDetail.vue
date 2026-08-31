<script setup lang="ts">
import { computed } from 'vue'
import { FileText, Pencil, Trash2, X } from 'lucide-vue-next'
import type { CareerMaterial } from '@/api/careerMaterial'
import { useLocale } from '@/i18n'
import { MATERIAL_TYPE_OPTIONS, USAGE_OPTIONS, optionLabel } from './options'

const props = defineProps<{ material: CareerMaterial | null; loading: boolean }>()
const emit = defineEmits<{ edit: []; delete: []; close: [] }>()
const { t } = useLocale()

const contentEntries = computed(() => Object.entries(props.material?.contentJson ?? {})
  .filter(([, value]) => value !== null && value !== '' && (!Array.isArray(value) || value.length)))

const fieldLabels: Record<string, string> = {
  scenario: 'careerMaterial.scenario', action: 'careerMaterial.action', outcome: 'careerMaterial.outcome', period: 'careerMaterial.period',
  metricName: 'careerMaterial.metricName', metricDisplayMode: 'careerMaterial.metricDisplayMode', metricDisplayValue: 'careerMaterial.metricDisplayValue', metricExactValue: 'careerMaterial.metricExactValue',
  responsibilityScope: 'careerMaterial.responsibilityScope', collaborationTargets: 'careerMaterial.collaborationTargets', teamSize: 'careerMaterial.teamSize',
  crossFunctionalRelationship: 'careerMaterial.crossFunctionalRelationship', keyDecision: 'careerMaterial.keyDecision', result: 'careerMaterial.result',
  skillName: 'careerMaterial.skillName', category: 'careerMaterial.skillCategory', proficiency: 'careerMaterial.proficiency', yearsOfExperience: 'careerMaterial.yearsOfExperience',
  lastUsedAt: 'careerMaterial.lastUsedAt', applicationDescription: 'careerMaterial.applicationDescription', outcomeEvidence: 'careerMaterial.outcomeEvidence',
}

function fieldLabel(key: string) {
  const translation = fieldLabels[key]
  return translation ? t(translation) : key.replace(/([A-Z])/g, ' $1').replace(/^./, value => value.toUpperCase())
}

function displayValue(value: unknown) {
  if (Array.isArray(value)) return value.join(', ')
  if (typeof value === 'object') return JSON.stringify(value)
  return String(value)
}
</script>

<template>
  <section class="material-detail" :aria-busy="loading">
    <header class="detail-toolbar">
      <span>{{ t('careerMaterial.detailTitle') }}</span>
      <button type="button" :title="t('careerMaterial.closePanel')" :aria-label="t('careerMaterial.closePanel')" @click="emit('close')"><X :size="17" /></button>
    </header>
    <div v-if="loading" class="detail-state" role="status">{{ t('careerMaterial.loadingDetail') }}</div>
    <div v-else-if="!material" class="detail-state">
      <FileText :size="24" />
      <strong>{{ t('careerMaterial.selectMaterialTitle') }}</strong>
      <p>{{ t('careerMaterial.selectMaterialHint') }}</p>
    </div>
    <template v-else>
      <div class="detail-scroll">
        <div class="detail-title-block">
          <div class="detail-tags">
            <span>{{ optionLabel(MATERIAL_TYPE_OPTIONS, material.materialType, t) }}</span>
            <span>{{ optionLabel(USAGE_OPTIONS, material.usagePreference, t) }}</span>
          </div>
          <h2>{{ material.title }}</h2>
        </div>
        <section v-if="material.sourceText" class="detail-section">
          <h3>{{ t('careerMaterial.sourceLabel') }}</h3>
          <p>{{ material.sourceText }}</p>
        </section>
        <section v-if="contentEntries.length" class="detail-section">
          <h3>{{ t('careerMaterial.structuredDetails') }}</h3>
          <dl>
            <template v-for="([key, value]) in contentEntries" :key="key">
              <dt>{{ fieldLabel(key) }}</dt><dd>{{ displayValue(value) }}</dd>
            </template>
          </dl>
        </section>
      </div>
      <footer class="detail-actions">
        <button class="btn-neon btn-ghost danger-action" type="button" @click="emit('delete')"><Trash2 :size="15" /> {{ t('careerMaterial.deleteAction') }}</button>
        <button class="btn-neon btn-primary" type="button" @click="emit('edit')"><Pencil :size="15" /> {{ t('common.edit') }}</button>
      </footer>
    </template>
  </section>
</template>

<style scoped>
.material-detail { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; min-width: 0; min-height: 0; background: var(--bg-surface); }
.detail-toolbar { display: flex; min-height: 48px; align-items: center; justify-content: space-between; padding: 0 14px 0 18px; border-bottom: 1px solid var(--border); color: var(--text-tertiary); font-family: var(--font-utility); font-size: 10px; font-weight: 750; text-transform: uppercase; }
.detail-toolbar button { display: grid; width: 30px; height: 30px; place-items: center; padding: 0; border: 0; border-radius: 4px; color: var(--text-secondary); background: transparent; cursor: pointer; }
.detail-toolbar button:hover { color: var(--accent); background: var(--accent-light); }
.detail-scroll { min-height: 0; overflow: auto; padding: 22px 20px; }
.detail-title-block { padding-bottom: 20px; border-bottom: 1px solid var(--border-soft); }
.detail-title-block h2 { margin: 9px 0 0; font-size: 20px; line-height: 1.35; }
.detail-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.detail-tags span { padding: 3px 6px; border: 1px solid var(--border); border-radius: 3px; color: var(--text-secondary); font-size: 9px; font-weight: 700; }
.detail-section { padding-top: 20px; }
.detail-section h3 { margin: 0 0 10px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; text-transform: uppercase; }
.detail-section p { margin: 0; color: var(--text-secondary); font-size: 12px; line-height: 1.75; white-space: pre-wrap; }
.detail-section dl { display: grid; grid-template-columns: minmax(90px, .7fr) minmax(0, 1.7fr); margin: 0; border-top: 1px solid var(--border-soft); }
.detail-section dt, .detail-section dd { margin: 0; padding: 9px 0; border-bottom: 1px solid var(--border-soft); font-size: 11px; line-height: 1.55; overflow-wrap: anywhere; }
.detail-section dt { padding-right: 12px; color: var(--text-tertiary); }
.detail-section dd { color: var(--text-primary); }
.detail-state { display: grid; place-items: center; align-content: center; gap: 10px; padding: 30px; color: var(--text-tertiary); text-align: center; }
.detail-state strong { color: var(--text-primary); font-size: 14px; }
.detail-state p { max-width: 260px; margin: 0; font-size: 11px; line-height: 1.6; }
.detail-actions { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 16px; border-top: 1px solid var(--border); }
.danger-action { color: var(--danger); }
@media (max-width: 767px) { .material-detail { min-height: 100dvh; } .detail-scroll { overflow: visible; } }
</style>
