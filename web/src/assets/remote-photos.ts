/**
 * 远程图片资源清单(全部 Unsplash CC0 / Unsplash License,可商用)。
 *
 * 主题:宇宙群星(深空 + 星云 + 银河 + 行星 + 星轨)
 *
 * URL 规则:`https://images.unsplash.com/photo-{id}?w={px}&q={quality}&auto=format&fit=crop`
 *   - auto=format:自动按浏览器选 WebP/AVIF
 *   - fit=crop:按比例裁剪
 *   - q=75:质量(0-100)
 *
 * 字段含义:
 *   bg:    Hero 背景层
 *   card*: 装饰卡配图
 *   flow*: 流程步骤配图
 *
 * 所有 ID 均为 Unsplash 公开 ID,如不可用只需替换 src 字符串即可。
 */

const u = (id: string, w = 1600, q = 75) =>
  `https://images.unsplash.com/photo-${id}?w=${w}&q=${q}&auto=format&fit=crop`

export const remotePhotos = {
  hero: {
    /** 银河 / 星云(给 Hero 视觉当背景层) */
    bg:    u('1462331940025-496df031c0e4', 2200, 70), // 银河
    bgAlt: u('1419242902214-272b3f66ee7a', 2200, 70), // 夜空星轨
  },
  /** 功能卡配图(2x2) */
  features: [
    { title: '职业资料库',   src: u('1502134249126-9f3755a50d78', 900, 70), tint: 'blue'   }, // 星轨
    { title: '岗位定制生成', src: u('1444703686981-a3abbc4d4fe3', 900, 70), tint: 'pink'   }, // 极光
    { title: '规则覆盖度',   src: u('1462331940025-496df031c0e4', 900, 70), tint: 'violet' }, // 银河
    { title: '私有 PDF 导出',src: u('1614728263952-84ea256f9679', 900, 70), tint: 'cyan'   }, // 行星
  ] as Array<{ title: string; src: string; tint: 'blue' | 'pink' | 'violet' | 'cyan' }>,

  /** 流程步骤配图(5 步) */
  flow: [
    u('1502134249126-9f3755a50d78', 600, 70), // 星轨
    u('1539593395743-7da5ee10ff07', 600, 70), // 蓝色星云
    u('1462331940025-496df031c0e4', 600, 70), // 银河
    u('1444703686981-a3abbc4d4fe3', 600, 70), // 极光
    u('1614728263952-84ea256f9679', 600, 70), // 行星
  ],

  /** CTA 横幅背景 */
  cta: u('1419242902214-272b3f66ee7a', 1800, 65),
}

/** 备用兜底:任何加载失败的图都退回到深空渐变,不出现破图。 */
export const FALLBACK_GRADIENT = 'linear-gradient(135deg, #0A0E27 0%, #3A1078 50%, #4F2E8E 100%)'