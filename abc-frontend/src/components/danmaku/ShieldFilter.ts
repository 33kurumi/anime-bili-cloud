import type { DanmakuInput, DanmakuSettings } from './types'

/**
 * 弹幕屏蔽过滤器（对齐哔哩哔哩/青空次元的"弹幕设置"能力）。
 *
 * 支持三类屏蔽：
 * 1. 关键词：包含即屏蔽（大小写不敏感）
 * 2. 正则：高级用户可写 /广告\d+/ 之类规则（写错不抛异常，忽略并继续）
 * 3. 分类：屏蔽滚动/顶部/底部某一类
 *
 * 设计上把"规则编译"和"逐条判断"分开：关键词数组与正则对象在设置变化时才重建，
 * 播放时只做字符串包含判断，避免每条弹幕都重复构造 RegExp（几千条弹幕时是明显的开销）。
 */
export class ShieldFilter {
  private keywords: string[] = []
  private regex: RegExp | null = null
  private blockMode = { SCROLL: false, TOP: false, BOTTOM: false }
  private enabled = false

  update(settings: DanmakuSettings): void {
    this.enabled = settings.shieldEnabled
    this.blockMode.SCROLL = settings.blockScroll
    this.blockMode.TOP = settings.blockTop
    this.blockMode.BOTTOM = settings.blockBottom
    this.keywords = settings.shieldKeywords
      .split(/[,，\n]/)
      .map(k => k.trim().toLowerCase())
      .filter(k => k.length > 0)
    const pattern = settings.shieldRegex.trim()
    if (pattern) {
      try {
        this.regex = new RegExp(pattern, 'i')
      } catch {
        this.regex = null
      }
    } else {
      this.regex = null
    }
  }

  /** 是否应该屏蔽这条弹幕 */
  isBlocked(item: DanmakuInput): boolean {
    if (!this.enabled) return false
    const mode = item.mode || 'SCROLL'
    if (this.blockMode[mode]) return true
    if (this.keywords.length === 0 && !this.regex) return false
    const text = item.text || ''
    if (this.keywords.length > 0) {
      const lower = text.toLowerCase()
      if (this.keywords.some(k => lower.includes(k))) return true
    }
    if (this.regex && this.regex.test(text)) return true
    return false
  }
}
