import { onMounted, onBeforeUnmount, ref, type Ref } from 'vue'

interface Star {
  x: number; y: number; r: number; baseAlpha: number
  twinkleSpeed: number; twinklePhase: number
  hue: number
}

interface Meteor {
  x: number; y: number; vx: number; vy: number
  life: number; maxLife: number; length: number; hue: number
}

/**
 * 星空背景:Canvas 绘大量闪烁星点 + 偶发流星雨。
 *
 * - 自适应 DPR 与窗口尺寸
 * - prefers-reduced-motion 时只画静态星点
 * - 自动清理 RAF / ResizeObserver
 *
 * 用法:
 *   const canvasRef = ref<HTMLCanvasElement | null>(null)
 *   useStars(canvasRef, { starCount: 220, meteorIntervalMs: 2200 })
 *   <canvas ref="canvasRef" class="star-canvas" />
 */
export function useStars(
  canvasRef: Ref<HTMLCanvasElement | null>,
  options: { starCount?: number; meteorIntervalMs?: number } = {},
) {
  const { starCount = 200, meteorIntervalMs = 2400 } = options

  let ctx: CanvasRenderingContext2D | null = null
  let stars: Star[] = []
  let meteors: Meteor[] = []
  let rafId = 0
  let resizeObs: ResizeObserver | null = null
  let lastMeteorAt = 0
  let reducedMotion = false

  function sizeCanvas(canvas: HTMLCanvasElement) {
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    const rect = canvas.getBoundingClientRect()
    canvas.width = Math.max(1, Math.floor(rect.width * dpr))
    canvas.height = Math.max(1, Math.floor(rect.height * dpr))
    ctx?.setTransform(dpr, 0, 0, dpr, 0, 0)
  }

  function generateStars(width: number, height: number) {
    stars = []
    for (let i = 0; i < starCount; i++) {
      stars.push({
        x: Math.random() * width,
        y: Math.random() * height,
        r: Math.random() * 1.3 + 0.2,
        baseAlpha: 0.4 + Math.random() * 0.6,
        twinkleSpeed: 0.6 + Math.random() * 1.4,
        twinklePhase: Math.random() * Math.PI * 2,
        hue: Math.random() < 0.18 ? (Math.random() < 0.5 ? 210 : 290) : 0, // 18% 星带蓝色/紫色
      })
    }
  }

  function spawnMeteor(width: number, height: number) {
    const fromLeft = Math.random() < 0.6
    const startX = fromLeft ? Math.random() * width * 0.3 : width * (0.4 + Math.random() * 0.6)
    const startY = Math.random() * height * 0.4
    const speed = 6 + Math.random() * 6
    const angle = (Math.PI / 8) + (Math.random() - 0.5) * 0.2 // 偏右下的拖尾
    meteors.push({
      x: startX,
      y: startY,
      vx: Math.cos(angle) * speed,
      vy: Math.sin(angle) * speed,
      life: 0,
      maxLife: 60 + Math.random() * 30,
      length: 60 + Math.random() * 60,
      hue: Math.random() < 0.5 ? 200 : 300,
    })
    // 限制同时存在的流星
    if (meteors.length > 6) meteors.splice(0, meteors.length - 6)
  }

  function tick(t: number) {
    const canvas = canvasRef.value
    if (!canvas || !ctx) {
      rafId = requestAnimationFrame(tick)
      return
    }
    const rect = canvas.getBoundingClientRect()
    const w = rect.width
    const h = rect.height

    // 背景淡出(制造残影)
    ctx.fillStyle = 'rgba(10, 14, 39, 0.35)'
    ctx.fillRect(0, 0, w, h)

    // 绘制星点
    for (const s of stars) {
      if (reducedMotion) {
        ctx.globalAlpha = s.baseAlpha
      } else {
        const a = s.baseAlpha * (0.6 + 0.4 * Math.sin((t / 1000) * s.twinkleSpeed + s.twinklePhase))
        ctx.globalAlpha = Math.max(0.15, Math.min(1, a))
      }
      if (s.hue === 0) {
        ctx.fillStyle = '#FFFFFF'
      } else {
        ctx.fillStyle = `hsl(${s.hue} 90% 80%)`
      }
      ctx.beginPath()
      ctx.arc(s.x, s.y, s.r, 0, Math.PI * 2)
      ctx.fill()
    }

    // 流星
    if (!reducedMotion) {
      if (t - lastMeteorAt > meteorIntervalMs) {
        lastMeteorAt = t
        spawnMeteor(w, h)
      }
      ctx.lineCap = 'round'
      for (let i = meteors.length - 1; i >= 0; i--) {
        const m = meteors[i]
        m.life++
        m.x += m.vx
        m.y += m.vy
        const lifeRatio = m.life / m.maxLife
        const alpha = 1 - lifeRatio
        if (lifeRatio >= 1 || m.x > w + 100 || m.y > h + 100) {
          meteors.splice(i, 1)
          continue
        }
        const tailX = m.x - (m.vx / Math.hypot(m.vx, m.vy)) * m.length
        const tailY = m.y - (m.vy / Math.hypot(m.vx, m.vy)) * m.length
        const grad = ctx.createLinearGradient(m.x, m.y, tailX, tailY)
        grad.addColorStop(0, `hsla(${m.hue} 95% 80% / ${alpha})`)
        grad.addColorStop(1, `hsla(${m.hue} 95% 80% / 0)`)
        ctx.strokeStyle = grad
        ctx.lineWidth = 2
        ctx.beginPath()
        ctx.moveTo(m.x, m.y)
        ctx.lineTo(tailX, tailY)
        ctx.stroke()
      }
    }

    ctx.globalAlpha = 1
    rafId = requestAnimationFrame(tick)
  }

  onMounted(() => {
    const canvas = canvasRef.value
    if (!canvas) return
    ctx = canvas.getContext('2d')
    reducedMotion = window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ?? false

    const rect = canvas.getBoundingClientRect()
    sizeCanvas(canvas)
    generateStars(rect.width, rect.height)

    // 初始清屏为深空底
    if (ctx) {
      ctx.fillStyle = '#0A0E27'
      ctx.fillRect(0, 0, rect.width, rect.height)
    }

    resizeObs = new ResizeObserver(() => {
      const r = canvas.getBoundingClientRect()
      sizeCanvas(canvas)
      generateStars(r.width, r.height)
    })
    resizeObs.observe(canvas)

    rafId = requestAnimationFrame(tick)
  })

  onBeforeUnmount(() => {
    cancelAnimationFrame(rafId)
    rafId = 0
    resizeObs?.disconnect()
    resizeObs = null
    ctx = null
    stars = []
    meteors = []
  })
}