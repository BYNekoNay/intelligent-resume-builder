<script setup lang="ts">
import { Languages } from 'lucide-vue-next'
import { useLocale } from '@/i18n'

const { locale, setLocale, t } = useLocale()
</script>

<template>
  <div class="language-switcher" :aria-label="t('language.label')" role="group">
    <Languages :size="15" aria-hidden="true" />
    <button type="button" :class="{ active: locale === 'zh-CN' }" :aria-pressed="locale === 'zh-CN'" @click="setLocale('zh-CN')">{{ t('language.chinese') }}</button>
    <button type="button" :class="{ active: locale === 'en-US' }" :aria-pressed="locale === 'en-US'" @click="setLocale('en-US')">{{ t('language.english') }}</button>
  </div>
</template>
