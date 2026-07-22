<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ArrowRight,
  BookOpen,
  CheckCircle2,
  Database,
  FileDown,
  FileText,
  Sparkles,
  Star,
  Target,
  Wand2,
} from 'lucide-vue-next'
import { getSystemHealth, type SystemHealth } from '@/api/system'
import { remotePhotos, FALLBACK_GRADIENT } from '@/assets/remote-photos'
import { useStars } from '@/composables/useStars'
import RemoteImage from '@/components/RemoteImage.vue'
import { useAuthStore } from '@/stores/auth'
import { useLocale } from '@/i18n'

const starCanvas = ref<HTMLCanvasElement | null>(null)
useStars(starCanvas, { starCount: 220, meteorIntervalMs: 2400 })

const health = ref<SystemHealth | null>(null)
const healthError = ref(false)
const healthLoading = ref(true)
const auth = useAuthStore()
const { t } = useLocale()
const startPath = computed(() => auth.accessToken ? '/career-materials' : '/register')

onMounted(async () => {
  try {
    health.value = (await getSystemHealth()).data
  } catch {
    healthError.value = true
  } finally {
    healthLoading.value = false
  }
})

const stars = computed(() => [
  { label: '4', text: t('home.statModules') },
  { label: '5', text: t('home.statSteps') },
  { label: '100%', text: t('home.statTraceable') },
  { label: '0', text: t('home.statLlm') },
])
</script>

<template>
  <!-- ============ 全页星空 Canvas(流星雨) ============ -->
  <canvas ref="starCanvas" class="star-canvas" aria-hidden="true" />

  <!-- ============ Hero ============ -->
  <section class="hero" :aria-label="t('home.title')">
    <div class="hero-text">
      <p class="eyebrow sparkle-on-hover">
        <Star :size="14" /> {{ t('home.eyebrow') }}
      </p>
      <h1 class="neon-title">{{ t('home.title') }}</h1>
      <p class="lead">{{ t('home.description') }}</p>

      <div class="hero-actions">
        <RouterLink class="btn-neon btn-primary" :to="startPath">
          {{ t('home.start') }} <ArrowRight :size="16" />
        </RouterLink>
        <a class="btn-neon btn-ghost" href="https://github.com/" target="_blank" rel="noreferrer">
          <BookOpen :size="15" /> {{ t('home.docs') }}
        </a>
        <RouterLink class="btn-neon btn-ghost" to="/career-materials">
          <Database :size="15" /> {{ t('home.materials') }}
        </RouterLink>
      </div>

      <div class="hero-meta">
        <div v-for="s in stars" :key="s.text">
          <strong>{{ s.label }}</strong>
          <span>{{ s.text }}</span>
        </div>
      </div>
    </div>

    <!-- 右侧主视觉:银河背景图 + 轨道动画 -->
    <div class="hero-visual" aria-hidden="true">
      <RemoteImage
        :src="remotePhotos.hero.bg"
        preset="tile"
        :fallback="FALLBACK_GRADIENT"
        alt="银河星空"
        class="hero-bg"
        eager
      />

      <!-- 渐变蒙版 -->
      <div class="hero-mask" />

      <!-- 轨道 + 流星 -->
      <div class="hero-orbit"><span class="orbit-dot" /></div>
      <div class="hero-orbit inner"><span class="orbit-dot" /></div>

      <!-- 中心「星核」标识 -->
      <div class="hero-core">
        <Sparkles :size="40" />
      </div>
    </div>
  </section>

  <!-- ============ 功能特性卡 ============ -->
  <section class="section" :aria-label="t('home.features')">
    <header class="section-head">
      <div>
        <p class="eyebrow"><Star :size="14" /> / {{ t('home.features') }}</p>
        <h2 class="neon-title">{{ t('home.featureTitle') }}</h2>
      </div>
      <p>{{ t('home.featureDescription') }}</p>
    </header>

    <div class="feature-grid">
      <article v-for="(f, idx) in remotePhotos.features" :key="f.title" class="feature-card">
        <RemoteImage :src="f.src" preset="card" :fallback="FALLBACK_GRADIENT" :alt="f.title" class="feature-img" />
        <div class="feature-mask" :class="`mask-${f.tint}`" />
        <div class="feature-body">
          <div class="icon-frame">
            <Database v-if="idx === 0" :size="20" />
            <Wand2 v-else-if="idx === 1" :size="20" />
            <Target v-else-if="idx === 2" :size="20" />
            <FileDown v-else :size="20" />
          </div>
          <h3>{{ f.title }}</h3>
          <p>
            <template v-if="idx === 0">统一管理工作、项目、技能与教育经历。每条资料都有来源原文,被历史版本引用后仍可追溯。</template>
            <template v-else-if="idx === 1">基于 JD + 资料库生成结构化草稿,逐项标记 <code>_source</code> 与 <code>_pending</code>,禁止一键确认 AI 自行生成的事实。</template>
            <template v-else-if="idx === 2">关键词 / 技能 / 经历三维加权打分,固定展示「非企业 ATS 结果、非录用概率」声明,可解释、可回放。</template>
            <template v-else>经典、现代、极简三种模板与编辑器预览保持一致，异步渲染并提供 24 小时私有下载。</template>
          </p>
          <div class="stat">
            <template v-if="idx === 0">
              <div><strong>6</strong>类型</div>
              <div><strong>3</strong>偏好</div>
              <div><strong>∞</strong>快照</div>
            </template>
            <template v-else-if="idx === 1">
              <div><strong>注入</strong>拦截</div>
              <div><strong>确定性</strong>Mock</div>
              <div><strong>幂等</strong>防重</div>
            </template>
            <template v-else-if="idx === 2">
              <div><strong>0.4/0.4/0.2</strong>权重</div>
              <div><strong>v1.0.0</strong>规则</div>
              <div><strong>同义词</strong>词典</div>
            </template>
            <template v-else>
              <div><strong>15s</strong>超时</div>
              <div><strong>10MB</strong>上限</div>
              <div><strong>sha256</strong>校验</div>
            </template>
          </div>
        </div>
      </article>
    </div>
  </section>

  <!-- ============ 5 步流程 ============ -->
  <section class="section" :aria-label="t('home.flow')">
    <header class="section-head">
      <div>
        <p class="eyebrow"><Sparkles :size="14" /> / {{ t('home.flow') }}</p>
        <h2 class="neon-title">{{ t('home.flowTitle') }}</h2>
      </div>
      <p>{{ t('home.flowDescription') }}</p>
    </header>

    <div class="flow-strip">
      <div v-for="(src, i) in remotePhotos.flow" :key="i" class="flow-cell">
        <RemoteImage :src="src" preset="flow" :fallback="FALLBACK_GRADIENT" alt="流程配图" class="flow-img" />
        <div class="step-num">STEP 0{{ i + 1 }}</div>
        <h4>{{ ['沉淀资料', '解析 JD', 'AI 生成', '逐项确认', '导出 PDF'][i] }}</h4>
        <p>{{ ['把经历、技能、证书录入资料库,标注使用偏好。', '确定性规则提取关键词与年限,不调外部大模型。', '提交任务后轮询;草稿每条都带 _source / _pending。', '接受、编辑或拒绝每条要点;事务性创建新版本。', '异步渲染、24h 受控下载,过期等同不存在。'][i] }}</p>
      </div>
    </div>
  </section>

  <!-- ============ CTA 横幅 ============ -->
  <section class="cta-banner" :aria-label="t('home.service')">
    <div>
      <p class="eyebrow"><Star :size="14" /> / {{ t('home.service') }}</p>
      <h2 class="neon-title">{{ t('home.serviceTitle') }}</h2>
      <p>
        <span v-if="healthLoading">正在检测后端健康度...</span>
        <span v-else-if="health">
          <CheckCircle2 :size="16" style="vertical-align: -3px; color: var(--nebula-pink);" />
          {{ health.service }} · {{ health.status }} · 阶段 {{ health.stage }}
        </span>
        <span v-else style="color: var(--blush, #FF8A8A);">后端未启动,先 docker compose up -d mysql 启动依赖</span>
      </p>
    </div>
    <div class="hero-actions">
      <RouterLink class="btn-neon btn-primary" to="/register">{{ t('home.creating') }} <ArrowRight :size="16" /></RouterLink>
      <RouterLink class="btn-neon btn-ghost" to="/jobs"><FileText :size="15" /> {{ t('home.tryJobs') }}</RouterLink>
    </div>
  </section>

  <!-- ============ 页脚 ============ -->
  <footer class="app-footer">
    <div style="display: inline-flex; align-items: center; gap: 10px;">
      <Star :size="16" /> © {{ new Date().getFullYear() }} 智历 · Intelligent Resume Builder
    </div>
    <div class="footer-links">
      <a href="https://github.com/" target="_blank" rel="noreferrer">源码</a>
      <a href="#" class="glow-link">API 契约</a>
      <a href="#" class="glow-link">隐私</a>
    </div>
  </footer>
</template>

<style scoped>
/* —— Hero 背景图 + 蒙版 —— */
.hero-bg {
  position: absolute !important;
  inset: 0;
  width: 100%;
  height: 100%;
  border-radius: 32px !important;
  z-index: 0;
}
.hero-text h1 { white-space: pre-line; }
.hero-mask {
  position: absolute;
  inset: 0;
  z-index: 1;
  pointer-events: none;
  background:
    radial-gradient(circle at 30% 20%, rgba(107, 43, 175, 0.25) 0%, transparent 55%),
    radial-gradient(circle at 75% 70%, rgba(31, 79, 217, 0.25) 0%, transparent 55%),
    linear-gradient(180deg, rgba(10, 14, 39, 0.4) 0%, rgba(10, 14, 39, 0.6) 100%);
}

/* —— Hero 中心星核 —— */
.hero-core {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  z-index: 3;
  color: var(--star-yellow);
  filter: drop-shadow(0 0 12px rgba(255, 228, 92, 0.7));
  animation: coreFloat 6s ease-in-out infinite;
}
@keyframes coreFloat {
  0%, 100% { transform: scale(1); }
  50%      { transform: scale(1.08); }
}

/* —— 功能卡 —— */
.feature-card {
  padding: 0 !important;
}
.feature-img {
  border-radius: 0 !important;
  width: 100%;
  height: 168px;
  border-bottom: 1px solid var(--line);
}
.feature-mask {
  position: absolute;
  top: 0; left: 0; right: 0;
  height: 168px;
  pointer-events: none;
  background: linear-gradient(180deg, rgba(10, 14, 39, 0.45) 0%, rgba(10, 14, 39, 0.96) 100%);
  z-index: 1;
}
.feature-mask.mask-pink   { background: linear-gradient(180deg, rgba(192, 76, 253, 0.25) 0%, rgba(10, 14, 39, 0.96) 100%); }
.feature-mask.mask-violet { background: linear-gradient(180deg, rgba(107, 43, 175, 0.35) 0%, rgba(10, 14, 39, 0.96) 100%); }
.feature-mask.mask-cyan   { background: linear-gradient(180deg, rgba(91, 183, 232, 0.25) 0%, rgba(10, 14, 39, 0.96) 100%); }

.feature-body {
  position: relative;
  padding: 14px 24px 24px;
  z-index: 2;
}

/* —— 流程图 —— */
.flow-img {
  width: 100%;
  height: 88px;
  margin-bottom: 12px;
  border: 1px solid var(--line-soft);
  border-radius: 10px !important;
}
</style>
