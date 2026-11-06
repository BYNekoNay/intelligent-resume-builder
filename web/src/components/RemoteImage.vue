<!--
远程图片包装：占位渐变 + 加载闪光 + 加载完成后淡入，加载失败只保留占位、不显示裂图。
eager 为 false（默认）时用 IntersectionObserver 在进入视口前 120px 才真正请求 src；
eager 为 true 或环境没有 IntersectionObserver 时立即加载，保证首屏关键图不被拦截。
preset 决定预设宽高比与圆角，fallback 决定占位渐变；组件额外提供默认插槽供上层叠加内容。
-->
<script setup lang="ts">
/**
 * 远程图片组件(Unsplash 等)。
 *
 * 特性:
 * - 原生 loading="lazy" + decoding="async" 自动懒加载
 * - 加载失败回退到指定渐变占位(FALLBACK_GRADIENT)
 * - 加载中显示渐变占位 + 浅蓝脉冲
 * - 加载完成后淡入(opacity 过渡)
 * - IntersectionObserver 可选:visible-threshold 进入视口才真正请求
 */
import { computed, onMounted, onBeforeUnmount, ref } from 'vue'

const props = withDefaults(
  defineProps<{
    src: string
    alt?: string
    /** 'card' | 'hero' | 'flow' | 'cta' 决定预设的宽高比与圆角 */
    preset?: 'card' | 'hero' | 'flow' | 'cta' | 'tile'
    /** 占位渐变 */
    fallback?: string
    /** 跳过懒加载拦截立即请求（默认 false 即默认拦截；首屏关键图可设 true） */
    eager?: boolean
  }>(),
  {
    preset: 'card',
    fallback: 'linear-gradient(135deg, #B8E5FF 0%, #FFE7A0 100%)',
    eager: false,
  },
)

const loaded = ref(false)
const failed = ref(false)
const shouldLoad = ref(props.eager)
const wrapperRef = ref<HTMLElement | null>(null)

const presetStyle = computed(() => {
  switch (props.preset) {
    case 'hero':  return { aspectRatio: '16 / 9', borderRadius: '24px' }
    case 'cta':   return { aspectRatio: '16 / 5',  borderRadius: '20px' }
    case 'flow':  return { aspectRatio: '4 / 3',  borderRadius: '14px' }
    case 'tile':  return { aspectRatio: '1 / 1',  borderRadius: '12px' }
    case 'card':
    default:      return { aspectRatio: '16 / 10', borderRadius: '16px' }
  }
})

let observer: IntersectionObserver | null = null

onMounted(() => {
  if (props.eager) {
    shouldLoad.value = true
    return
  }
  if (!wrapperRef.value || typeof IntersectionObserver === 'undefined') {
    shouldLoad.value = true
    return
  }
  observer = new IntersectionObserver(
    (entries) => {
      entries.forEach((entry) => {
        if (entry.isIntersecting) {
          shouldLoad.value = true
          observer?.disconnect()
        }
      })
    },
    { rootMargin: '120px 0px', threshold: 0.05 },
  )
  observer.observe(wrapperRef.value)
})

onBeforeUnmount(() => {
  observer?.disconnect()
  observer = null
})

function onLoad() { loaded.value = true }
function onError() { failed.value = true; loaded.value = true }
</script>

<template>
  <div
    ref="wrapperRef"
    class="rimg"
    :style="{
      ...presetStyle,
      background: fallback,
    }"
  >
    <img
      v-if="shouldLoad && !failed"
      :src="src"
      :alt="alt ?? ''"
      loading="lazy"
      decoding="async"
      referrerpolicy="no-referrer"
      @load="onLoad"
      @error="onError"
      :class="['rimg-img', { 'is-loaded': loaded }]"
    />
    <div v-if="!loaded" class="rimg-shimmer" aria-hidden="true" />
    <slot />
  </div>
</template>

<style scoped>
.rimg {
  position: relative;
  overflow: hidden;
  display: block;
  width: 100%;
  isolation: isolate;
}

.rimg-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  opacity: 0;
  transition: opacity 0.45s ease;
}
.rimg-img.is-loaded { opacity: 1; }

.rimg-shimmer {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    100deg,
    rgba(255, 255, 255, 0) 30%,
    rgba(255, 255, 255, 0.45) 50%,
    rgba(255, 255, 255, 0) 70%
  );
  background-size: 200% 100%;
  animation: rimgShimmer 1.6s linear infinite;
  pointer-events: none;
}

@keyframes rimgShimmer {
  0%   { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}
</style>