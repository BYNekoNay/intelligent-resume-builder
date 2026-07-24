<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ArrowRight,
  CheckCircle2,
  Sparkles,
  Database,
  Target,
  FileCheck,
  BarChart3,
} from 'lucide-vue-next'
import { getSystemHealth, type SystemHealth } from '@/api/system'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/i18n'

const health = ref<SystemHealth | null>(null)
const healthError = ref(false)
const healthLoading = ref(true)
const auth = useAuthStore()
const { t } = useLocale()
const startPath = computed(() => auth.accessToken ? '/generate' : '/register')

onMounted(async () => {
  try { health.value = (await getSystemHealth()).data }
  catch { healthError.value = true }
  finally { healthLoading.value = false }
})

const steps = [
  { icon: Database, color: '#5444F0', bg: '#EEEDFE', titleKey: 'home.step1Title', descKey: 'home.step1Desc' },
  { icon: Sparkles, color: '#1D9E75', bg: '#E1F5EE', titleKey: 'home.step2Title', descKey: 'home.step2Desc' },
  { icon: FileCheck, color: '#D85A30', bg: '#FFF2ED', titleKey: 'home.step3Title', descKey: 'home.step3Desc' },
  { icon: BarChart3, color: '#5444F0', bg: '#EEEDFE', titleKey: 'home.step4Title', descKey: 'home.step4Desc' },
]
</script>

<template>
  <section class="home-hero">
    <div class="home-hero-text">
      <span class="home-badge">✦ {{ t('home.badge') }}</span>
      <h1>{{ t('home.title') }}</h1>
      <p class="home-hero-desc">{{ t('home.description') }}</p>
      <div class="home-hero-actions">
        <RouterLink class="btn-neon btn-primary" :to="startPath">
          {{ t('home.start') }} <ArrowRight :size="16" />
        </RouterLink>
        <a class="btn-neon btn-ghost" href="https://github.com/" target="_blank" rel="noreferrer">
          {{ t('home.docs') }}
        </a>
      </div>
      <div class="home-stats">
        <span class="home-stat"><strong>6</strong> {{ t('home.statTypes') }}</span>
        <span class="home-stat"><strong>4</strong> {{ t('home.statSteps') }}</span>
        <span class="home-stat"><strong>100%</strong> {{ t('home.statTraceable') }}</span>
      </div>
    </div>

    <div class="home-hero-visual" aria-hidden="true">
      <div class="home-hero-card" style="top: 14px; left: 18px; right: 18px;">
        <div class="hero-card-label">{{ t('home.step4Title') }}</div>
        <div class="hero-card-progress">
          <div class="hero-card-track">
            <div class="hero-card-fill" style="width: 75%;" />
          </div>
          <div class="hero-card-value">85%</div>
        </div>
      </div>
      <div class="home-hero-card" style="top: 98px; left: 18px; right: 18px; display: grid; gap: 8px;">
        <div class="hero-card-label">{{ t('home.step2Title') }}</div>
        <div class="hero-draft-bars">
          <div class="hero-draft-bar" style="width: 60%;" />
          <div class="hero-draft-bar" style="width: 40%;" />
          <div class="hero-draft-bar" style="width: 35%;" />
        </div>
      </div>
    </div>
  </section>

  <section class="home-steps">
    <article v-for="step in steps" :key="step.titleKey" class="home-step-card">
      <div class="home-step-icon" :style="{ background: step.bg, color: step.color }">
        <component :is="step.icon" :size="18" />
      </div>
      <h2>{{ t(step.titleKey) }}</h2>
      <p>{{ t(step.descKey) }}</p>
    </article>
  </section>

  <section class="home-cta">
    <div class="home-cta-content">
      <div>
        <h2>{{ t('home.ctaTitle') }}</h2>
        <p>{{ t('home.ctaDesc') }}</p>
      </div>
      <RouterLink class="home-cta-btn" :to="startPath">{{ t('home.ctaButton') }}</RouterLink>
    </div>
  </section>

  <footer class="home-footer">
    <div class="home-footer-left">
      <Target :size="15" />
      <span>© {{ new Date().getFullYear() }} {{ t('home.copyRight') }}</span>
    </div>
    <div class="home-footer-status">
      <span v-if="healthLoading" style="color: var(--text-tertiary);">检查服务...</span>
      <span v-else-if="health" class="home-status-ok">
        <CheckCircle2 :size="14" /> {{ t('home.serviceStatus') }}
      </span>
      <span v-else class="home-status-err">服务离线</span>
    </div>
    <div class="home-footer-links">
      <a href="https://github.com/" target="_blank" rel="noreferrer">源码</a>
      <a href="#">API</a>
      <a href="#">隐私</a>
    </div>
  </footer>
</template>

<style scoped>
/* ============================================================
 *  Hero
 * ============================================================ */
.home-hero {
  display: grid;
  grid-template-columns: 1.15fr 0.85fr;
  gap: 40px;
  align-items: center;
  padding: 12px 0 40px;
}

.home-hero-text {
  display: grid;
  gap: 16px;
}

.home-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: fit-content;
  padding: 5px 14px;
  background: var(--accent-light);
  border-radius: var(--radius-full);
  font-size: 12px;
  font-weight: 600;
  color: var(--accent);
}

.home-hero-text h1 {
  font-size: clamp(28px, 4vw, 42px);
  font-weight: 700;
  line-height: 1.18;
  letter-spacing: -0.025em;
  color: var(--text-primary);
  white-space: pre-line;
  margin: 0;
}

.home-hero-desc {
  font-size: 15px;
  color: var(--text-secondary);
  line-height: 1.7;
  margin: 0;
  max-width: 460px;
}

.home-hero-actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.home-stats {
  display: flex;
  gap: 24px;
  padding-top: 4px;
}

.home-stat {
  font-size: 12px;
  color: var(--text-tertiary);
}

.home-stat strong {
  color: var(--accent);
  font-weight: 700;
  font-size: 14px;
}

/* --- Hero visual: floating product cards --- */
.home-hero-visual {
  position: relative;
  aspect-ratio: 1;
  background: linear-gradient(135deg, var(--accent-light) 0%, #fff 55%, var(--success-light) 100%);
  border-radius: var(--radius-xl);
  border: 1px solid var(--border);
  overflow: hidden;
}

.home-hero-card {
  position: absolute;
  background: #fff;
  border-radius: var(--radius-md);
  padding: 14px 16px;
  box-shadow: var(--shadow-sm);
  border: 1px solid var(--border-soft);
}

.hero-card-label {
  font-size: 12px;
  font-weight: 600;
  color: var(--text-secondary);
  margin-bottom: 8px;
}

.hero-card-progress {
  display: flex;
  align-items: center;
  gap: 10px;
}

.hero-card-track {
  flex: 1;
  height: 5px;
  background: var(--border-soft);
  border-radius: var(--radius-full);
  overflow: hidden;
}

.hero-card-fill {
  height: 100%;
  background: var(--accent);
  border-radius: var(--radius-full);
}

.hero-card-value {
  font-size: 14px;
  font-weight: 700;
  color: var(--accent);
  min-width: 32px;
  text-align: right;
}

.hero-draft-bars {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.hero-draft-bar {
  height: 18px;
  border-radius: 5px;
  background: var(--accent-light);
}

/* ============================================================
 *  Steps
 * ============================================================ */
.home-steps {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
  margin-top: 48px;
}

.home-step-card {
  background: var(--bg-surface);
  border: 1px solid var(--border);
  border-radius: var(--radius-lg);
  padding: 22px 18px;
  box-shadow: var(--shadow-sm);
}

.home-step-icon {
  width: 38px;
  height: 38px;
  border-radius: var(--radius-md);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 14px;
}

.home-step-card h2 {
  font-size: 15px;
  font-weight: 600;
  color: var(--text-primary);
  margin: 0 0 6px;
}

.home-step-card p {
  font-size: 12px;
  color: var(--text-tertiary);
  line-height: 1.55;
  margin: 0;
}

/* ============================================================
 *  CTA
 * ============================================================ */
.home-cta {
  margin-top: 56px;
  padding: 36px 40px;
  background: var(--accent);
  border-radius: var(--radius-xl);
}

.home-cta-content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 28px;
  flex-wrap: wrap;
}

.home-cta h2 {
  font-size: 22px;
  font-weight: 700;
  color: #fff;
  margin: 0 0 6px;
}

.home-cta p {
  font-size: 14px;
  color: rgba(255, 255, 255, 0.75);
  margin: 0;
}

.home-cta-btn {
  padding: 11px 24px;
  background: #fff;
  color: var(--accent);
  border-radius: var(--radius-md);
  font-size: 13px;
  font-weight: 700;
  text-decoration: none;
  white-space: nowrap;
  transition: box-shadow 0.15s;
}

.home-cta-btn:hover {
  text-decoration: none;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}

/* ============================================================
 *  Footer
 * ============================================================ */
.home-footer {
  margin-top: 56px;
  padding: 24px 0;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  border-top: 1px solid var(--border-soft);
  color: var(--text-tertiary);
  font-size: 13px;
}

.home-footer-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.home-footer-status {
  display: flex;
  align-items: center;
  gap: 6px;
}

.home-status-ok {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--success);
  font-weight: 600;
}

.home-status-err {
  color: var(--danger);
  font-weight: 600;
}

.home-footer-links {
  display: flex;
  gap: 18px;
}

.home-footer-links a {
  color: var(--text-secondary);
  text-decoration: none;
}

.home-footer-links a:hover {
  color: var(--accent);
}

/* ============================================================
 *  Responsive
 * ============================================================ */
@media (max-width: 900px) {
  .home-hero {
    grid-template-columns: 1fr;
    gap: 28px;
  }

  .home-hero-visual {
    aspect-ratio: 16/10;
    max-height: 260px;
  }

  .home-steps {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .home-hero-text h1 {
    font-size: 24px;
  }

  .home-steps {
    grid-template-columns: 1fr;
  }

  .home-cta {
    padding: 28px 22px;
  }

  .home-cta-content {
    flex-direction: column;
    align-items: stretch;
    text-align: center;
  }

  .home-cta-btn {
    text-align: center;
  }

  .home-footer {
    flex-direction: column;
    text-align: center;
  }
}
</style>
