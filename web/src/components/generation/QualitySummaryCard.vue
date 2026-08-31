<script setup lang="ts">
import { computed, ref } from 'vue'
import { ChevronDown } from 'lucide-vue-next'
import { useLocale } from '@/i18n'
import { useDraftReview, type QualitySummary } from '@/composables/useDraftReview'

const { t } = useLocale()
const { qualitySummary, warnings, missingInfo, processedCount, draftItems } = useDraftReview()

const qualityExpanded = ref(false)

const QUALITY_READINESS = computed<Record<QualitySummary['readiness'], { label: string; hint: string }>>(() => ({
  READY: { label: t('generationConfirm.qualityReadyLabel'), hint: t('generationConfirm.qualityReadyHint') },
  REVIEW_RECOMMENDED: { label: t('generationConfirm.qualityReviewLabel'), hint: t('generationConfirm.qualityReviewHint') },
  REQUIRES_ACTION: { label: t('generationConfirm.qualityActionLabel'), hint: t('generationConfirm.qualityActionHint') },
}))
</script>

<template>
  <section
    v-if="qualitySummary"
    :class="['quality-summary', `quality-summary--${qualitySummary.readiness.toLowerCase()}`]"
    :aria-label="t('generationConfirm.qualitySummaryAriaLabel')"
  >
    <div class="quality-summary__heading">
      <div>
        <h3>{{ t('generationConfirm.qualitySummaryTitle') }}</h3>
        <p>{{ processedCount }}/{{ draftItems.length }} {{ t('generationConfirm.reviewedCount') }}</p>
      </div>
      <div class="quality-summary__controls">
        <span class="quality-summary__status">{{ QUALITY_READINESS[qualitySummary.readiness].label }}</span>
        <button
          class="icon-button"
          :class="{ active: qualityExpanded }"
          :aria-expanded="qualityExpanded"
          :aria-label="t('generationConfirm.toggleQualityDetails')"
          :title="t('generationConfirm.toggleQualityDetails')"
          @click="qualityExpanded = !qualityExpanded"
        ><ChevronDown :size="16" /></button>
      </div>
    </div>
    <div class="quality-summary__metrics">
      <div><strong>{{ qualitySummary.sourcedItems }}</strong><span>{{ t('generationConfirm.hasSource') }}</span></div>
      <div><strong>{{ qualitySummary.draftGapCount }}</strong><span>{{ t('generationConfirm.draftPending') }}</span></div>
      <div><strong>{{ qualitySummary.unsupportedItems }}</strong><span>{{ t('generationConfirm.pendingReview') }}</span></div>
      <div><strong>{{ qualitySummary.missingRequirementCount }}</strong><span>{{ t('generationConfirm.uncovered') }}</span></div>
    </div>
    <div v-if="qualityExpanded" class="quality-summary__details">
      <p>{{ QUALITY_READINESS[qualitySummary.readiness].hint }}</p>
      <p v-for="warning in warnings" :key="warning" class="warning-item">{{ warning }}</p>
      <div v-if="missingInfo.length" class="missing-summary">
        <strong>{{ t('generationConfirm.missingInfoTitle') }}</strong>
        <ul>
          <li v-for="(missing, index) in missingInfo" :key="index">
            {{ missing.section }}：{{ missing.reason }}
          </li>
        </ul>
      </div>
    </div>
  </section>

  <div v-if="!qualitySummary && (warnings.length || missingInfo.length)" class="review-notices">
    <p v-for="warning in warnings" :key="warning" class="warning-item">{{ warning }}</p>
    <div v-if="missingInfo.length" class="missing-summary">
      <strong>{{ t('generationConfirm.missingInfoTitle') }}</strong>
      <ul>
        <li v-for="(missing, index) in missingInfo" :key="index">{{ missing.section }}：{{ missing.reason }}</li>
      </ul>
    </div>
  </div>
</template>

<style scoped>
.warning-item {
  font-size: 0.85rem;
  color: #92400e;
}
.quality-summary {
  border: 1px solid #bfdbfe;
  border-radius: 8px;
  background: #eff6ff;
  padding: 1rem;
  margin-bottom: 1rem;
}
.quality-summary--review_recommended {
  border-color: #fde68a;
  background: #fffbeb;
}
.quality-summary--requires_action {
  border-color: #fecaca;
  background: #fef2f2;
}
.quality-summary__heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
  margin-bottom: 0.85rem;
}
.quality-summary__heading h3 {
  margin: 0 0 0.25rem;
  color: #0f3d75;
  font-size: 0.95rem;
}
.quality-summary--review_recommended .quality-summary__heading h3 { color: #92400e; }
.quality-summary--requires_action .quality-summary__heading h3 { color: #991b1b; }
.quality-summary__heading p {
  margin: 0;
  color: #475569;
  font-size: 0.82rem;
  line-height: 1.45;
}
.quality-summary__status {
  flex: 0 0 auto;
  padding: 0.2rem 0.45rem;
  border-radius: 4px;
  color: #1d4ed8;
  background: #dbeafe;
  font-size: 0.75rem;
  font-weight: 600;
}
.quality-summary--review_recommended .quality-summary__status {
  color: #92400e;
  background: #fef3c7;
}
.quality-summary--requires_action .quality-summary__status {
  color: #b91c1c;
  background: #fee2e2;
}
.quality-summary__metrics {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 0.5rem;
}
.quality-summary__metrics > div {
  min-width: 0;
  padding: 0.55rem;
  border-radius: 5px;
  background: rgba(255, 255, 255, 0.65);
}
.quality-summary__metrics strong,
.quality-summary__metrics span {
  display: block;
}
.quality-summary__metrics strong {
  color: #1e293b;
  font-size: 1.05rem;
}
.quality-summary__metrics span {
  margin-top: 0.15rem;
  color: #64748b;
  font-size: 0.72rem;
  line-height: 1.3;
}
@media (max-width: 560px) {
  .quality-summary__heading { flex-direction: column; gap: 0.5rem; }
  .quality-summary__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
.warning-item { margin: 0; color: var(--warning); font-size: 10px; }
.quality-summary { margin: 0; padding: 20px; border: 1px solid color-mix(in srgb, var(--info) 25%, var(--border)); border-radius: 7px; background: var(--info-light); }
.quality-summary--review_recommended { border-color: color-mix(in srgb, var(--warning) 28%, var(--border)); background: var(--warning-light); }
.quality-summary--requires_action { border-color: color-mix(in srgb, var(--danger) 25%, var(--border)); background: var(--danger-light); }
.quality-summary__heading { margin-bottom: 14px; }
.quality-summary__heading h3 { color: var(--text-primary); font-size: 14px; }
.quality-summary--review_recommended .quality-summary__heading h3, .quality-summary--requires_action .quality-summary__heading h3 { color: var(--text-primary); }
.quality-summary__heading p { color: var(--text-secondary); font-size: 10px; }
.quality-summary__status { border: 1px solid color-mix(in srgb, var(--info) 25%, var(--border)); border-radius: 4px; color: var(--info); background: #fff; font-size: 9px; }
.quality-summary--review_recommended .quality-summary__status { border-color: color-mix(in srgb, var(--warning) 25%, var(--border)); color: var(--warning); background: #fff; }
.quality-summary--requires_action .quality-summary__status { border-color: color-mix(in srgb, var(--danger) 25%, var(--border)); color: var(--danger); background: #fff; }
.quality-summary__metrics { gap: 7px; }
.quality-summary__metrics > div { padding: 9px; border: 1px solid color-mix(in srgb, var(--border) 70%, transparent); border-radius: 5px; background: color-mix(in srgb, #fff 82%, transparent); }
.quality-summary__metrics strong { color: var(--text-primary); font-family: var(--font-utility); font-size: 15px; }
.quality-summary__metrics span { color: var(--text-secondary); font-size: 9px; }
.quality-summary__details { grid-column: 1 / -1; padding: 10px 0 2px; border-top: 1px solid var(--border-soft); }
.quality-summary__details > p { margin: 0 0 5px; color: var(--text-secondary); font-size: 10px; }
.missing-summary { margin-top: 8px; color: var(--text-secondary); font-size: 10px; }
.missing-summary ul { margin: 5px 0 0; padding-left: 18px; }
.review-notices { padding: 11px 14px; border: 1px solid color-mix(in srgb, var(--warning) 30%, var(--border)); border-left: 3px solid var(--warning); border-radius: 6px; background: var(--bg-surface); }
.review-notices .missing-summary:first-child { margin-top: 0; }
.icon-button { display: inline-grid; width: 30px; height: 30px; flex: 0 0 30px; padding: 0; place-items: center; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); cursor: pointer; }
.icon-button:hover,
.icon-button.active { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.icon-button.active svg { transform: rotate(180deg); }

@media (max-width: 900px) {
  .quality-summary { grid-template-columns: 1fr; }
  .quality-summary__metrics { border-left: 0; }
  .quality-summary__details { grid-column: auto; }
}

@media (max-width: 767px) {
  .quality-summary { padding: 10px 12px; }
  .quality-summary__metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .quality-summary__metrics > div { border-bottom: 1px solid var(--border-soft); }
}

@media (max-width: 560px) {
  .quality-summary { padding: 16px; }
}
</style>
