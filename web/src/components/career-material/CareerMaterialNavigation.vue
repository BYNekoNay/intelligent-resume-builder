<script setup lang="ts">
import { computed } from 'vue'
import { Database, UserRound } from 'lucide-vue-next'
import type { MaterialType } from '@/api/careerMaterial'
import type { PersonalProfile } from '@/api/personalProfile'
import { useLocale } from '@/i18n'
import { MATERIAL_TYPE_OPTIONS } from './options'

const props = defineProps<{
  profile: PersonalProfile
  typeCounts: Partial<Record<MaterialType, number>>
  total: number
  activeType: '' | MaterialType
  profileActive: boolean
}>()

const emit = defineEmits<{
  selectProfile: []
  selectType: [type: '' | MaterialType]
}>()

const { t } = useLocale()
const profileFields = computed(() => [
  props.profile.fullName,
  props.profile.email,
  props.profile.phone,
  props.profile.location,
  props.profile.website,
  props.profile.profileSummary,
  props.profile.targetRoleTitles.length,
  props.profile.targetSeniority,
  props.profile.targetIndustries.length,
  props.profile.targetWorkPreferences.length,
  props.profile.careerPositioningSummary,
])
const profileCompletion = computed(() => Math.round(
  profileFields.value.filter(value => Boolean(value)).length / profileFields.value.length * 100,
))
const profileName = computed(() => props.profile.fullName || t('careerMaterial.profileNotSet'))
const profileTarget = computed(() => props.profile.targetRoleTitles[0] || t('careerMaterial.profileTargetNotSet'))
</script>

<template>
  <aside class="material-index" :aria-label="t('careerMaterial.indexLabel')">
    <button class="profile-index-entry" :class="{ active: profileActive }" type="button" @click="emit('selectProfile')">
      <span class="profile-index-icon"><UserRound :size="18" /></span>
      <span class="profile-index-copy">
        <strong>{{ profileName }}</strong>
        <small>{{ profileTarget }}</small>
      </span>
      <span class="profile-progress" :aria-label="t('careerMaterial.profileCompletion', { value: profileCompletion })">
        {{ profileCompletion }}%
      </span>
    </button>

    <div class="index-divider"><span>{{ t('careerMaterial.evidenceIndex') }}</span></div>

    <nav class="type-index" :aria-label="t('careerMaterial.filterLabel')">
      <button :class="{ active: !profileActive && activeType === '' }" type="button" @click="emit('selectType', '')">
        <Database :size="15" /><span>{{ t('careerMaterial.filterAll') }}</span><strong>{{ total }}</strong>
      </button>
      <button
        v-for="option in MATERIAL_TYPE_OPTIONS"
        :key="option.value"
        :class="{ active: !profileActive && activeType === option.value }"
        type="button"
        @click="emit('selectType', option.value)"
      >
        <span class="type-marker" aria-hidden="true"></span>
        <span>{{ t(option.key) }}</span>
        <strong>{{ typeCounts[option.value] ?? 0 }}</strong>
      </button>
    </nav>
  </aside>
</template>

<style scoped>
.material-index { display: grid; align-content: start; min-width: 0; overflow: auto; border-right: 1px solid var(--border); background: color-mix(in srgb, var(--bg-page) 72%, #fff); }
.profile-index-entry { display: grid; grid-template-columns: 36px minmax(0, 1fr) auto; align-items: center; gap: 10px; width: 100%; padding: 18px 16px; border: 0; border-bottom: 1px solid var(--border); color: var(--text-primary); background: transparent; text-align: left; cursor: pointer; }
.profile-index-entry:hover, .profile-index-entry.active { background: var(--bg-surface); }
.profile-index-entry.active { box-shadow: inset 3px 0 var(--accent); }
.profile-index-icon { display: grid; width: 36px; height: 36px; place-items: center; border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--border)); border-radius: 6px; color: var(--accent); background: var(--accent-light); }
.profile-index-copy { display: grid; min-width: 0; gap: 3px; }
.profile-index-copy strong, .profile-index-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.profile-index-copy strong { font-size: 12px; }
.profile-index-copy small { color: var(--text-tertiary); font-size: 10px; }
.profile-progress { color: var(--accent); font-family: var(--font-utility); font-size: 10px; font-weight: 750; }
.index-divider { padding: 18px 16px 8px; color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 750; text-transform: uppercase; }
.type-index { display: grid; padding: 0 8px 14px; }
.type-index button { display: grid; grid-template-columns: 16px minmax(0, 1fr) auto; align-items: center; gap: 8px; width: 100%; min-height: 38px; padding: 7px 9px; border: 0; border-radius: 5px; color: var(--text-secondary); background: transparent; text-align: left; cursor: pointer; }
.type-index button:hover { color: var(--text-primary); background: var(--bg-surface); }
.type-index button.active { color: var(--accent); background: var(--accent-light); font-weight: 700; }
.type-index button strong { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; }
.type-index button.active strong { color: var(--accent); }
.type-marker { width: 6px; height: 6px; margin-left: 4px; border: 1px solid currentColor; border-radius: 50%; }
@media (max-width: 767px) {
  .material-index { display: block; overflow: visible; border-right: 0; border-bottom: 1px solid var(--border); }
  .profile-index-entry { padding: 12px 14px; }
  .index-divider { display: none; }
  .type-index { display: flex; gap: 6px; overflow-x: auto; padding: 9px 12px 11px; scrollbar-width: thin; }
  .type-index button { flex: none; display: inline-flex; width: auto; min-height: 34px; padding: 6px 10px; border: 1px solid var(--border); background: var(--bg-surface); white-space: nowrap; }
  .type-index button strong { margin-left: 2px; }
}
</style>
