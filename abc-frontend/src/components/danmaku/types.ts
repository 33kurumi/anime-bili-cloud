/**
 * 弹幕引擎的公共类型与设置持久化。
 *
 * 【为什么自己写弹幕引擎】DPlayer 的弹幕是 DOM 实现：每条弹幕一个 <div>，
 * 几百条后主线程开始卡、无法精确吸附 video.currentTime（拖进度条会错位），
 * 也无法做"轨道防重叠 + 屏蔽过滤 + 显示区域"这类需要全局调度的能力。
 * canvas + rAF 可以做到：一份数据 → 一帧一次绘制，性能稳定，时间完全由 video.currentTime 驱动。
 */

export type DanmakuMode = 'SCROLL' | 'TOP' | 'BOTTOM'

/** 引擎输入：一条待显示弹幕 */
export interface DanmakuInput {
  id?: string
  text: string
  /** 出现时间（秒，视频时间） */
  time: number
  mode: DanmakuMode
  color?: string
  fontSize?: number
}

export interface DanmakuSettings {
  /** 弹幕总开关 */
  enabled: boolean
  /** 不透明度 0.2 - 1 */
  opacity: number
  /** 字号基数 14 - 30 */
  fontSize: number
  /** 滚动速度系数 0.5 - 2（越大越快穿过屏幕） */
  speed: number
  /** 显示区域高度占比：0.25 / 0.5 / 0.75 / 1 */
  displayArea: number
  /** 屏蔽功能开关 */
  shieldEnabled: boolean
  /** 屏蔽关键词，逗号/换行分隔 */
  shieldKeywords: string
  /** 屏蔽正则（可选，填写后与关键词同时生效） */
  shieldRegex: string
  /** 分类屏蔽 */
  blockScroll: boolean
  blockTop: boolean
  blockBottom: boolean
}

export const DEFAULT_SETTINGS: DanmakuSettings = {
  enabled: true,
  opacity: 0.9,
  fontSize: 18,
  speed: 1,
  displayArea: 1,
  shieldEnabled: true,
  shieldKeywords: '',
  shieldRegex: '',
  blockScroll: false,
  blockTop: false,
  blockBottom: false
}

export const SETTINGS_STORAGE_KEY = 'abc_danmaku_settings'

/** 从 localStorage 读取设置（损坏时回退默认值，避免一处脏数据导致整个播放页崩） */
export function loadDanmakuSettings(): DanmakuSettings {
  try {
    const raw = localStorage.getItem(SETTINGS_STORAGE_KEY)
    if (!raw) return { ...DEFAULT_SETTINGS }
    const parsed = JSON.parse(raw) as Partial<DanmakuSettings>
    return { ...DEFAULT_SETTINGS, ...parsed }
  } catch {
    return { ...DEFAULT_SETTINGS }
  }
}

export function saveDanmakuSettings(settings: DanmakuSettings): void {
  try {
    localStorage.setItem(SETTINGS_STORAGE_KEY, JSON.stringify(settings))
  } catch {
    /* 隐私模式下 localStorage 可能不可写，忽略即可 */
  }
}
