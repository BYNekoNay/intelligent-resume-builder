<script setup lang="ts">
import { AlertCircle, Check, CheckCircle2, ChevronLeft, ChevronRight, Circle, ListFilter, Menu, Pencil, Trash2, X } from 'lucide-vue-next'
import { useLocale } from '@/i18n'
import { useDraftReview } from '@/composables/useDraftReview'
import DraftContentFields from '@/components/DraftContentFields.vue'

const { t } = useLocale()
const {
  draftItems,
  unselectedInfo,
  sectionEntries,
  visibleSectionEntries,
  attentionSectionCount,
  activeEntry,
  activeItems,
  activeMissingInfo,
  activeSectionIndex,
  activeNavigationIndex,
  attentionOnly,
  processedCount,
  pendingCount,
  customTitle,
  mobileNavigationOpen,
  selectSection,
  moveSection,
  toggleAttentionOnly,
  setDecision,
  openEdit,
} = useDraftReview()

defineProps<{
  error: string
  confirming: boolean
  rejecting: boolean
}>()

const emit = defineEmits<{
  confirm: []
  reject: []
}>()
</script>

<template>
  <button
    class="mobile-outline-trigger"
    :aria-expanded="mobileNavigationOpen"
    @click="mobileNavigationOpen = true"
  >
    <Menu :size="17" />
    <span>{{ activeEntry?.label }}</span>
    <b>{{ activeSectionIndex + 1 }}/{{ sectionEntries.length }}</b>
  </button>

  <div class="review-workspace">
    <aside :class="['review-rail', { open: mobileNavigationOpen }]">
      <div class="review-rail__heading">
        <div>
          <span>{{ t('generationConfirm.sectionNavigator') }}</span>
          <strong>{{ processedCount }}/{{ draftItems.length }}</strong>
        </div>
        <button
          class="icon-button mobile-only"
          :aria-label="t('common.close')"
          :title="t('common.close')"
          @click="mobileNavigationOpen = false"
        ><X :size="17" /></button>
      </div>
      <button
        class="attention-filter"
        :class="{ active: attentionOnly }"
        :aria-pressed="attentionOnly"
        @click="toggleAttentionOnly"
      >
        <ListFilter :size="15" />
        <span>{{ t('generationConfirm.attentionOnly') }}</span>
        <b>{{ attentionSectionCount }}</b>
      </button>
      <nav class="section-navigation" :aria-label="t('generationConfirm.sectionNavigationAria')">
        <button
          v-for="section in visibleSectionEntries"
          :key="section.key"
          :class="['section-navigation__item', { active: activeEntry?.key === section.key }]"
          :aria-current="activeEntry?.key === section.key ? 'step' : undefined"
          @click="selectSection(section.key)"
        >
          <AlertCircle v-if="section.needsAttention" :size="15" class="attention" />
          <Circle v-else-if="section.rejected" :size="15" class="rejected" />
          <CheckCircle2 v-else :size="15" />
          <span>{{ section.label }}</span>
          <b>{{ section.count }}</b>
        </button>
      </nav>
      <p v-if="attentionOnly && !visibleSectionEntries.length" class="rail-empty">
        {{ t('generationConfirm.noAttentionItems') }}
      </p>
      <details v-if="unselectedInfo.length" class="unselected-details">
        <summary>{{ t('generationConfirm.unusedMaterials').replace('{count}', String(unselectedInfo.length)) }}</summary>
        <ul>
          <li v-for="(unused, index) in unselectedInfo" :key="index">
            {{ unused.title || t('generationConfirm.unnamedMaterial') }}：{{ unused.unselectedReason }}
          </li>
        </ul>
      </details>
    </aside>

    <section class="review-stage">
      <header class="review-stage__heading">
        <div>
          <p>{{ t('generationConfirm.currentSection') }}</p>
          <h2>{{ activeEntry?.label }}</h2>
        </div>
        <span>{{ activeItems.length }} {{ t('generationConfirm.itemUnit') }}</span>
      </header>

      <div class="review-stage__scroll">
        <div v-if="activeMissingInfo.length" class="missing-section">
          <h3><AlertCircle :size="16" />{{ t('generationConfirm.missingInfoTitle') }}</h3>
          <ul>
            <li v-for="(missing, index) in activeMissingInfo" :key="index">{{ missing.reason }}</li>
          </ul>
        </div>

        <div v-if="activeEntry" class="draft-section">
          <h3 class="sr-only" aria-hidden="true">{{ activeEntry.label }}</h3>
          <div v-for="(item, itemIndex) in activeItems" :key="item.path" :class="['draft-item', item.decision?.toLowerCase()]">
            <div class="item-header">
              <span class="item-number">{{ t('generationConfirm.itemNumber').replace('{index}', String(itemIndex + 1)) }}</span>
              <span v-if="item.source" class="source-badge">{{ t('generationConfirm.sourceBadge') }}</span>
              <span v-if="item.pending" class="pending-badge">{{ t('generationConfirm.pendingBadge').replace('{pending}', item.pending) }}</span>
            </div>
            <DraftContentFields :model-value="item.content" />
            <div class="item-actions">
              <button
                :class="['action-btn accept', { active: item.decision === 'ACCEPT' }]"
                :aria-pressed="item.decision === 'ACCEPT'"
                @click="setDecision(item, 'ACCEPT')"
              ><Check :size="15" /><span>{{ t('generationConfirm.accept') }}</span></button>
              <button class="action-btn edit" @click="openEdit(item)"><Pencil :size="15" /><span>{{ t('generationConfirm.editAction') }}</span></button>
              <button
                :class="['action-btn reject', { active: item.decision === 'REJECT' }]"
                :aria-pressed="item.decision === 'REJECT'"
                @click="setDecision(item, 'REJECT')"
              ><Trash2 :size="15" /><span>{{ t('generationConfirm.deleteAction') }}</span></button>
            </div>
          </div>
          <div v-if="!activeItems.length" class="section-empty">
            <AlertCircle :size="24" />
            <p>{{ t('generationConfirm.noDraftForSection') }}</p>
          </div>
        </div>
      </div>

      <div class="section-pagination">
        <button
          class="btn-secondary"
          :disabled="activeNavigationIndex <= 0"
          @click="moveSection(-1)"
        ><ChevronLeft :size="15" />{{ t('generationConfirm.previousSection') }}</button>
        <span>{{ Math.max(0, activeNavigationIndex + 1) }} / {{ visibleSectionEntries.length }}</span>
        <button
          class="btn-secondary"
          :disabled="activeNavigationIndex < 0 || activeNavigationIndex >= visibleSectionEntries.length - 1"
          @click="moveSection(1)"
        >{{ t('generationConfirm.nextSection') }}<ChevronRight :size="15" /></button>
      </div>

      <footer class="confirm-actions">
        <div class="title-input">
          <label for="generated-resume-title">{{ t('generationConfirm.resumeNameShortLabel') }}</label>
          <input id="generated-resume-title" v-model="customTitle" :placeholder="t('generationConfirm.resumeNamePlaceholder')" class="input" />
        </div>
        <p v-if="error" class="error-msg">{{ error }}</p>
        <div class="confirm-actions__buttons">
          <button class="btn-secondary" @click="emit('reject')" :disabled="rejecting">
            {{ t('generationConfirm.rejectDraft') }}
          </button>
          <button class="btn-primary" @click="emit('confirm')" :disabled="confirming || pendingCount > 0">
            <span v-if="confirming" class="spinner"></span>
            {{ confirming ? t('generationConfirm.creating') : `${t('generationConfirm.confirmAndCreate')}${pendingCount > 0 ? `（${pendingCount}）` : ''}` }}
          </button>
        </div>
      </footer>
    </section>
  </div>
  <button v-if="mobileNavigationOpen" class="mobile-nav-scrim" :aria-label="t('common.close')" @click="mobileNavigationOpen = false"></button>
</template>

<style scoped>
.missing-section {
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 8px;
  padding: 0.75rem 1rem;
  margin-bottom: 1.5rem;
}
.missing-section h3 {
  font-size: 0.9rem;
  color: #991b1b;
  margin-bottom: 0.5rem;
}
.missing-section li {
  font-size: 0.85rem;
  color: #7f1d1d;
}
.draft-section {
  margin-bottom: 1.5rem;
}
.draft-section h3 {
  font-size: 1rem;
  font-weight: 600;
  margin-bottom: 0.5rem;
  padding-bottom: 0.25rem;
  border-bottom: 1px solid #e5e7eb;
}
.draft-item {
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  padding: 0.75rem;
  margin-bottom: 0.5rem;
  transition: border-color 0.15s;
}
.draft-item.accept {
  border-color: #a7f3d0;
}
.draft-item.reject {
  border-color: #fca5a5;
  opacity: 0.6;
}
.draft-item.edit {
  border-color: #93c5fd;
}
.item-header {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 0.4rem;
  flex-wrap: wrap;
}
.item-number {
  font-size: 0.75rem;
  color: #64748b;
  font-weight: 600;
}
.source-badge {
  font-size: 0.7rem;
  padding: 0.1rem 0.4rem;
  background: #dbeafe;
  color: #1e40af;
  border-radius: 3px;
}
.pending-badge {
  font-size: 0.7rem;
  padding: 0.1rem 0.4rem;
  background: #fef3c7;
  color: #92400e;
  border-radius: 3px;
}
.item-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 14px;
  padding-top: 10px;
  border-top: 1px solid #edf1f5;
}
.action-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  min-width: 76px;
  height: 34px;
  padding: 0 12px;
  border: 1px solid #d9e0e8;
  border-radius: 6px;
  background: #fff;
  color: #475569;
  font: 600 13px/1 inherit;
  white-space: nowrap;
  cursor: pointer;
  transition: border-color 0.15s ease, background-color 0.15s ease, color 0.15s ease, transform 0.15s ease;
}
.action-btn svg {
  flex: 0 0 auto;
}
.action-btn:hover {
  border-color: #94a3b8;
  background: #f8fafc;
  transform: translateY(-1px);
}
.action-btn:focus-visible {
  outline: 2px solid rgba(14, 116, 144, 0.28);
  outline-offset: 2px;
}
.action-btn.accept {
  color: #047857;
}
.action-btn.accept:hover {
  border-color: #6ee7b7;
  background: #ecfdf5;
}
.action-btn.accept.active {
  background: #059669;
  border-color: #059669;
  color: #fff;
}
.action-btn.edit {
  color: #1d4ed8;
}
.action-btn.reject.active {
  background: #dc2626;
  border-color: #dc2626;
  color: #fff;
}
.action-btn.reject {
  color: #b91c1c;
}
.action-btn.reject:hover {
  border-color: #fca5a5;
  background: #fef2f2;
}
.unselected-details {
  margin-bottom: 1.5rem;
  font-size: 0.85rem;
  color: #6b7280;
}
.unselected-details summary {
  cursor: pointer;
  font-weight: 500;
}
.title-input {
  margin-bottom: 1.5rem;
}
.title-input label {
  display: block;
  font-size: 0.85rem;
  color: #6b7280;
  margin-bottom: 0.4rem;
}
.input {
  width: 100%;
  padding: 0.5rem 0.75rem;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.9rem;
}
.error-msg {
  color: #dc2626;
  font-size: 0.85rem;
  margin-bottom: 1rem;
}
.confirm-actions {
  display: flex;
  gap: 0.75rem;
  justify-content: flex-end;
}
.btn-primary {
  padding: 0.6rem 1.5rem;
  background: #0e7490;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 0.9rem;
  cursor: pointer;
  font-weight: 500;
  display: flex;
  align-items: center;
  gap: 0.4rem;
}
.btn-primary:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}
.btn-secondary {
  padding: 0.6rem 1.5rem;
  background: #fff;
  color: #374151;
  border: 1px solid #d1d5db;
  border-radius: 6px;
  font-size: 0.9rem;
  cursor: pointer;
}
.spinner {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255,255,255,0.3);
  border-top-color: #fff;
  border-radius: 50%;
  animation: spin 0.6s linear infinite;
}
@keyframes spin {
  to { transform: rotate(360deg); }
}
@media (max-width: 560px) {
  .item-actions {
    display: grid;
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .action-btn {
    width: 100%;
    min-width: 0;
    padding: 0 8px;
  }
}
@media (prefers-reduced-motion: reduce) {
  .action-btn {
    transition: none;
  }
  .action-btn:hover {
    transform: none;
  }
}
.missing-section { margin: 0; padding: 15px 18px; border: 1px solid color-mix(in srgb, var(--danger) 25%, var(--border)); border-left: 4px solid var(--danger); border-radius: 7px; background: var(--danger-light); }
.missing-section h3 { margin: 0 0 7px; color: var(--text-primary); font-size: 12px; }
.missing-section ul { margin: 0; padding-left: 18px; }
.missing-section li { color: var(--text-secondary); font-size: 10px; }
.draft-section { display: grid; gap: 0; margin: 0; padding: 20px 22px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.draft-section > h3 { margin: 0; padding: 0 0 13px; border-bottom: 1px solid var(--border); color: var(--text-primary); font-size: 14px; }
.draft-item { margin: 0; padding: 16px 4px; border: 0; border-bottom: 1px solid var(--border-soft); border-radius: 0; background: transparent; }
.draft-item:last-child { border-bottom: 0; }
.draft-item.accept { border-color: var(--border-soft); background: color-mix(in srgb, var(--success-light) 38%, transparent); box-shadow: inset 3px 0 0 var(--success); }
.draft-item.reject { border-color: var(--border-soft); background: color-mix(in srgb, var(--danger-light) 45%, transparent); box-shadow: inset 3px 0 0 var(--danger); opacity: .68; }
.draft-item.edit { border-color: var(--border-soft); background: color-mix(in srgb, var(--info-light) 40%, transparent); box-shadow: inset 3px 0 0 var(--info); }
.item-header { margin-bottom: 8px; }
.item-number { color: var(--text-tertiary); font-size: 9px; }
.source-badge, .pending-badge { min-height: 20px; padding: 3px 6px; border-radius: 3px; font-size: 9px; }
.source-badge { color: var(--info); background: var(--info-light); }
.pending-badge { color: var(--warning); background: var(--warning-light); }
.item-actions { margin-top: 13px; padding-top: 10px; border-top-color: var(--border-soft); }
.action-btn { min-width: 74px; height: 32px; padding: 0 10px; border-color: var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); font-size: 10px; }
.action-btn:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-light); transform: none; }
.action-btn.accept { color: var(--success); }
.action-btn.accept.active { border-color: var(--success); color: #fff; background: var(--success); }
.action-btn.edit { color: var(--info); }
.action-btn.reject { color: var(--danger); }
.action-btn.reject.active { border-color: var(--danger); color: #fff; background: var(--danger); }
.unselected-details { margin: 0; padding: 13px 16px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-secondary); background: var(--bg-surface); font-size: 10px; }
.title-input { display: grid; gap: 6px; margin: 0; padding: 18px 20px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); }
.title-input label { margin: 0; color: var(--text-secondary); font-size: 11px; font-weight: 650; }
.title-input .input { padding: 10px; border-color: var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font-size: 13px; }
.title-input .input:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.error-msg { margin: 0; color: var(--danger); font-size: 11px; }
.confirm-actions { gap: 8px; padding: 12px; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.btn-primary, .btn-secondary { display: inline-flex; align-items: center; justify-content: center; min-height: 36px; padding: 0 13px; border: 1px solid var(--border); border-radius: 6px; font-size: 11px; font-weight: 650; cursor: pointer; }
.btn-primary { border-color: var(--accent); color: #fff; background: var(--accent); }
.btn-secondary { color: var(--text-secondary); background: var(--bg-surface); }
.btn-secondary:hover { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }

@media (max-width: 560px) {
  .draft-section { padding: 17px 14px; }
  .item-actions { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .confirm-actions { display: grid; grid-template-columns: 1fr; }
  .confirm-actions button { width: 100%; }
}

.icon-button { display: inline-grid; width: 30px; height: 30px; flex: 0 0 30px; padding: 0; place-items: center; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); cursor: pointer; }
.icon-button:hover,
.icon-button.active { border-color: var(--accent); color: var(--accent); background: var(--accent-light); }
.review-workspace { display: grid; grid-template-columns: 220px minmax(0, 1fr); height: clamp(540px, calc(100dvh - 248px), 740px); min-height: 540px; overflow: hidden; border: 1px solid var(--border); border-radius: 7px; background: var(--bg-surface); box-shadow: var(--shadow-sm); }
.review-rail { display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; gap: 10px; min-width: 0; padding: 16px 12px; overflow: hidden; border-right: 1px solid var(--border); background: color-mix(in srgb, var(--bg-page) 70%, var(--bg-surface)); }
.review-rail__heading { display: flex; align-items: center; justify-content: space-between; padding: 0 4px 8px; }
.review-rail__heading > div { display: flex; align-items: baseline; justify-content: space-between; width: 100%; gap: 10px; }
.review-rail__heading span { color: var(--text-secondary); font-size: 10px; font-weight: 700; }
.review-rail__heading strong { color: var(--accent); font-family: var(--font-utility); font-size: 11px; }
.attention-filter { display: grid; grid-template-columns: 18px 1fr auto; align-items: center; width: 100%; min-height: 34px; padding: 0 9px; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: var(--bg-surface); font-size: 10px; font-weight: 650; text-align: left; cursor: pointer; }
.attention-filter b { display: grid; min-width: 19px; height: 19px; place-items: center; border-radius: 10px; color: var(--text-tertiary); background: var(--bg-page); font-size: 9px; }
.attention-filter.active { border-color: color-mix(in srgb, var(--warning) 38%, var(--border)); color: var(--warning); background: var(--warning-light); }
.section-navigation { display: grid; align-content: start; gap: 2px; min-height: 0; overflow-y: auto; padding-right: 3px; scrollbar-width: thin; }
.section-navigation__item { display: grid; grid-template-columns: 18px minmax(0, 1fr) auto; align-items: center; width: 100%; min-height: 36px; padding: 0 9px; border: 0; border-radius: 5px; color: var(--text-secondary); background: transparent; font-size: 10px; font-weight: 650; text-align: left; cursor: pointer; }
.section-navigation__item svg { color: var(--success); }
.section-navigation__item svg.attention { color: var(--warning); }
.section-navigation__item svg.rejected { color: var(--text-tertiary); }
.section-navigation__item b { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; }
.section-navigation__item:hover { color: var(--text-primary); background: var(--bg-surface); }
.section-navigation__item.active { color: var(--accent); background: var(--accent-light); box-shadow: inset 3px 0 0 var(--accent); }
.section-navigation__item.active svg,
.section-navigation__item.active b { color: var(--accent); }
.rail-empty { margin: 8px; color: var(--text-tertiary); font-size: 10px; line-height: 1.5; }
.review-rail .unselected-details { padding: 9px; background: transparent; }
.review-rail .unselected-details ul { max-height: 120px; overflow-y: auto; padding-left: 16px; }
.review-stage { display: grid; grid-template-rows: auto minmax(0, 1fr) auto auto; min-width: 0; min-height: 0; }
.review-stage__heading { display: flex; align-items: center; justify-content: space-between; min-height: 62px; padding: 11px 20px; border-bottom: 1px solid var(--border); }
.review-stage__heading p { margin: 0 0 2px; color: var(--text-tertiary); font-size: 9px; font-weight: 700; }
.review-stage__heading h2 { margin: 0; color: var(--text-primary); font-size: 17px; }
.review-stage__heading > span { color: var(--text-tertiary); font-size: 10px; }
.review-stage__scroll { min-height: 0; overflow-y: auto; padding: 12px 20px 24px; scrollbar-width: thin; }
.review-stage__scroll .missing-section { margin-bottom: 12px; padding: 11px 13px; border-left-width: 3px; }
.review-stage__scroll .missing-section h3 { display: flex; align-items: center; gap: 6px; }
.review-stage__scroll .draft-section { padding: 0; border: 0; border-radius: 0; box-shadow: none; }
.review-stage__scroll .draft-item { padding: 15px 12px; }
.review-stage__scroll .draft-item:first-of-type { padding-top: 8px; }
.section-empty { display: grid; justify-items: center; gap: 7px; padding: 48px 20px; color: var(--warning); text-align: center; }
.section-empty p { max-width: 360px; margin: 0; color: var(--text-secondary); font-size: 11px; line-height: 1.6; }
.section-pagination { display: grid; grid-template-columns: auto 1fr auto; align-items: center; gap: 12px; padding: 8px 20px; border-top: 1px solid var(--border-soft); }
.section-pagination > span { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; text-align: center; }
.section-pagination .btn-secondary { min-height: 32px; gap: 4px; padding: 0 10px; }
.section-pagination button:disabled { opacity: .45; cursor: not-allowed; }
.confirm-actions { display: grid; grid-template-columns: minmax(210px, 1fr) auto; align-items: end; gap: 10px 16px; padding: 10px 14px; border: 0; border-top: 1px solid var(--border); border-radius: 0; box-shadow: 0 -5px 14px rgba(28, 48, 37, .04); }
.confirm-actions .title-input { grid-template-columns: auto minmax(160px, 1fr); align-items: center; gap: 9px; padding: 0; border: 0; background: transparent; }
.confirm-actions .title-input label { white-space: nowrap; font-size: 10px; }
.confirm-actions .title-input .input { min-width: 0; height: 36px; padding: 0 10px; }
.confirm-actions__buttons { display: flex; gap: 8px; }
.confirm-actions .error-msg { grid-column: 1 / -1; grid-row: 1; }
.mobile-outline-trigger,
.mobile-only,
.mobile-nav-scrim { display: none; }
.sr-only { position: absolute; width: 1px; height: 1px; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }

@media (max-width: 900px) {
  .review-workspace { grid-template-columns: 190px minmax(0, 1fr); }
  .confirm-actions { grid-template-columns: 1fr; }
  .confirm-actions__buttons { justify-content: flex-end; }
}

@media (max-width: 767px) {
  .mobile-outline-trigger { display: grid; grid-template-columns: 20px minmax(0, 1fr) auto; align-items: center; min-height: 42px; padding: 0 12px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-surface); font-size: 11px; font-weight: 700; text-align: left; }
  .mobile-outline-trigger b { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; }
  .review-workspace { display: block; height: auto; min-height: 0; overflow: visible; }
  .review-rail { position: fixed; inset: 0 auto 0 0; z-index: 1002; width: min(310px, calc(100vw - 48px)); padding-top: 18px; transform: translateX(-105%); transition: transform .18s ease; box-shadow: var(--shadow-lg); }
  .review-rail.open { transform: translateX(0); }
  .review-rail__heading > div { width: auto; flex: 1; }
  .mobile-only { display: inline-grid; }
  .mobile-nav-scrim { position: fixed; inset: 0; z-index: 1001; display: block; width: 100%; height: 100%; padding: 0; border: 0; background: rgba(18, 36, 27, .42); }
  .review-stage { min-height: calc(100dvh - 220px); }
  .review-stage__heading { min-height: 56px; padding: 10px 14px; }
  .review-stage__scroll { overflow: visible; padding: 12px 14px 22px; }
  .review-stage__scroll .draft-item { padding-inline: 6px; }
  .section-pagination { padding: 8px 12px; }
  .section-pagination .btn-secondary { width: auto; }
  .confirm-actions { position: sticky; z-index: 8; bottom: 0; grid-template-columns: 1fr; padding: 10px 12px; background: var(--bg-surface); }
  .confirm-actions__buttons { display: grid; grid-template-columns: minmax(0, .75fr) minmax(0, 1.25fr); }
  .confirm-actions button { width: 100%; }
}

@media (prefers-reduced-motion: reduce) {
  .review-rail { transition: none; }
}
</style>
