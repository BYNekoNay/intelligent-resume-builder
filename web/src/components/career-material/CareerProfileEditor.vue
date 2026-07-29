<script setup lang="ts">
import { computed } from 'vue'
import { ArrowLeft, Download, Save, Target, UserRound } from 'lucide-vue-next'
import type { PersonalProfile } from '@/api/personalProfile'
import type { ResumeSummary } from '@/api/resume'
import { useLocale } from '@/i18n'

const profile = defineModel<PersonalProfile>({ required: true })
const importResumeId = defineModel<number | null>('importResumeId', { required: true })
defineProps<{ resumes: ResumeSummary[]; loading: boolean; saving: boolean; message: string }>()
const emit = defineEmits<{ save: []; import: []; close: [] }>()
const { t } = useLocale()

function splitList(value: string) {
  return value.split(/[,，、\n]/).map(item => item.trim()).filter(Boolean)
}
const targetRolesText = computed({ get: () => profile.value.targetRoleTitles.join(', '), set: value => { profile.value.targetRoleTitles = splitList(value) } })
const industriesText = computed({ get: () => profile.value.targetIndustries.join(', '), set: value => { profile.value.targetIndustries = splitList(value) } })
const locationsText = computed({ get: () => profile.value.targetWorkPreferences.join(', '), set: value => { profile.value.targetWorkPreferences = splitList(value) } })
</script>

<template>
  <section class="profile-workspace" :aria-busy="loading">
    <header class="profile-workspace-heading">
      <button class="back-action" type="button" :title="t('careerMaterial.backToLibrary')" :aria-label="t('careerMaterial.backToLibrary')" @click="emit('close')"><ArrowLeft :size="18" /></button>
      <span class="heading-icon"><UserRound :size="19" /></span>
      <div><p>{{ t('careerMaterial.profileEyebrow') }}</p><h2>{{ t('careerMaterial.profileTitle') }}</h2><small>{{ t('careerMaterial.profileDescription') }}</small></div>
      <button class="btn-neon btn-primary" type="button" :disabled="loading || saving" @click="emit('save')"><Save :size="16" /> {{ saving ? t('careerMaterial.profileSaving') : t('careerMaterial.profileSave') }}</button>
    </header>

    <fieldset class="profile-fieldset" :disabled="loading || saving">
    <div class="profile-scroll">
      <div class="profile-fields">
        <label>{{ t('careerMaterial.fullName') }}<input v-model.trim="profile.fullName" autocomplete="name" /></label>
        <label>{{ t('careerMaterial.email') }}<input v-model.trim="profile.email" type="email" autocomplete="email" /></label>
        <label>{{ t('careerMaterial.phone') }}<input v-model.trim="profile.phone" autocomplete="tel" /></label>
        <label>{{ t('careerMaterial.location') }}<input v-model.trim="profile.location" autocomplete="address-level2" /></label>
        <label class="wide-field">{{ t('careerMaterial.website') }}<input v-model.trim="profile.website" type="url" placeholder="https://" /></label>
        <label class="wide-field">{{ t('careerMaterial.profileSummary') }}<textarea v-model.trim="profile.profileSummary" rows="4" :placeholder="t('careerMaterial.profileSummaryPlaceholder')" /></label>
      </div>

      <section class="career-targets">
        <header><Target :size="17" /><div><h3>{{ t('careerMaterial.careerTargetsTitle') }}</h3><p>{{ t('careerMaterial.careerTargetsDescription') }}</p></div></header>
        <div class="profile-fields">
          <label>{{ t('careerMaterial.targetRoles') }}<input v-model.trim="targetRolesText" :placeholder="t('careerMaterial.targetRolesPlaceholder')" /></label>
          <label>{{ t('careerMaterial.targetSeniority') }}<input v-model.trim="profile.targetSeniority" :placeholder="t('careerMaterial.targetSeniorityPlaceholder')" /></label>
          <label>{{ t('careerMaterial.industryPreference') }}<input v-model.trim="industriesText" :placeholder="t('careerMaterial.listSeparatorHint')" /></label>
          <label>{{ t('careerMaterial.locationPreference') }}<input v-model.trim="locationsText" :placeholder="t('careerMaterial.locationPreferencePlaceholder')" /></label>
          <label class="wide-field">{{ t('careerMaterial.careerPositioningSummary') }}<textarea v-model.trim="profile.careerPositioningSummary" rows="3" :placeholder="t('careerMaterial.careerPositioningSummaryPlaceholder')" /></label>
        </div>
      </section>

      <section v-if="resumes.length" class="profile-import">
        <label>{{ t('careerMaterial.importFromResume') }}<select v-model="importResumeId"><option :value="null">{{ t('careerMaterial.selectResume') }}</option><option v-for="resume in resumes" :key="resume.id" :value="resume.id">{{ resume.title }}</option></select></label>
        <button class="btn-neon btn-ghost" type="button" :disabled="!importResumeId || loading" @click="emit('import')"><Download :size="16" /> {{ t('careerMaterial.importSuggestion') }}</button>
        <small>{{ t('careerMaterial.importDoesNotSave') }}</small>
      </section>
      <p v-if="message" class="profile-message" role="status">{{ message }}</p>
    </div>
    </fieldset>
  </section>
</template>

<style scoped>
.profile-workspace { display: grid; grid-template-rows: auto minmax(0, 1fr); min-width: 0; min-height: 0; background: var(--bg-surface); }
.profile-fieldset { display: grid; min-width: 0; min-height: 0; height: 100%; margin: 0; padding: 0; border: 0; grid-template-rows: minmax(0, 1fr); }
.profile-workspace-heading { display: grid; grid-template-columns: 34px 40px minmax(0, 1fr) auto; align-items: center; gap: 12px; padding: 16px 20px; border-bottom: 1px solid var(--border); }
.back-action { display: grid; width: 32px; height: 32px; place-items: center; padding: 0; border: 1px solid var(--border); border-radius: 5px; color: var(--text-secondary); background: transparent; cursor: pointer; }
.heading-icon { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 6px; color: var(--accent); background: var(--accent-light); }
.profile-workspace-heading p, .profile-workspace-heading h2, .profile-workspace-heading small { margin: 0; }
.profile-workspace-heading p { color: var(--text-tertiary); font-family: var(--font-utility); font-size: 9px; font-weight: 700; text-transform: uppercase; }
.profile-workspace-heading h2 { margin-top: 2px; font-size: 17px; }
.profile-workspace-heading small { display: block; margin-top: 4px; color: var(--text-secondary); font-size: 10px; }
.profile-scroll { min-height: 0; overflow: auto; padding: 24px; }
.profile-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px 16px; }
.profile-fields label, .profile-import label { display: grid; gap: 6px; color: var(--text-secondary); font-size: 12px; font-weight: 650; }
.profile-fields input, .profile-fields textarea, .profile-import select { width: 100%; padding: 10px; border: 1px solid var(--border); border-radius: 6px; color: var(--text-primary); background: var(--bg-input); font: inherit; font-size: 13px; }
.profile-fields input:focus, .profile-fields textarea:focus, .profile-import select:focus { outline: none; border-color: var(--border-focus); box-shadow: 0 0 0 3px var(--accent-light); }
.wide-field { grid-column: 1 / -1; }
.career-targets { display: grid; gap: 16px; margin-top: 24px; padding-top: 22px; border-top: 1px solid var(--border-soft); }
.career-targets > header { display: flex; align-items: start; gap: 9px; color: var(--accent); }
.career-targets h3, .career-targets p { margin: 0; }
.career-targets h3 { color: var(--text-primary); font-size: 14px; }
.career-targets p { margin-top: 3px; color: var(--text-secondary); font-size: 11px; }
.profile-import { display: flex; align-items: end; gap: 12px; margin-top: 24px; padding-top: 22px; border-top: 1px solid var(--border-soft); }
.profile-import label { min-width: min(100%, 300px); }
.profile-import small { max-width: 250px; color: var(--text-tertiary); font-size: 10px; line-height: 1.5; }
.profile-message { color: var(--accent); font-size: 11px; font-weight: 650; }
@media (max-width: 767px) { .profile-workspace { min-height: 100dvh; } .profile-workspace-heading { grid-template-columns: 34px 40px minmax(0, 1fr); padding: 12px 14px; } .profile-workspace-heading .btn-primary { grid-column: 1 / -1; justify-content: center; } .profile-scroll { overflow: visible; padding: 18px 14px 30px; } .profile-fields { grid-template-columns: 1fr; } .wide-field { grid-column: auto; } .profile-import { align-items: stretch; flex-direction: column; } }
</style>
