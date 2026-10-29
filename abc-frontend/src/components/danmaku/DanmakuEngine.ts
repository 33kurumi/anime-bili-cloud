import { ShieldFilter } from './ShieldFilter'
import { TrackAllocator, type TrackKind } from './TrackAllocator'
import type { DanmakuInput, DanmakuSettings } from './types'

/**
 * canvas 弹幕渲染引擎。
 *
 * 【设计要点】
 * 1. 时间轴完全由 video.currentTime 驱动（不是自己累加 setInterval）：
 *    暂停时画面静止、倍速时自动变快、拖进度条后不会"弹幕错位"，这是 DOM 版弹幕最常见的 bug。
 * 2. 一帧一次绘制：所有弹幕画到同一个 canvas 上，避免几百个 DOM 节点重排。
 * 3. 增量装填：播放页按时间窗（/api/danmaku/window）往池子里 append，
 *    引擎内部用游标 cursor 顺序消费，不重复扫描历史数据。
 * 4. 弹幕反查：seek 后调用 seekTo()，二分定位到当前时间之后的第一条，避免把整集弹幕重放一遍。
 */

export interface EngineOptions {
  /** 返回当前视频播放时间（秒） */
  getTime: () => number
  /** 返回最新弹幕设置（每帧读取，改设置立即生效） */
  getSettings: () => DanmakuSettings
}

interface ActiveItem {
  item: DanmakuInput
  kind: TrackKind
  /** 视频时间（ms） */
  startTime: number
  duration: number
  width: number
  /** 纵向位置（CSS 像素） */
  y: number
  /** 水平速度 px/ms（滚动弹幕） */
  speed: number
}

/** 滚动弹幕横穿屏幕的基准时长（ms），受"速度"设置影响 */
const BASE_DURATION = 8000
/** 静止弹幕（顶部/底部）停留时长 */
const FIXED_DURATION = 5000
const FONT_FAMILY = '"Microsoft YaHei", "PingFang SC", "Hiragino Sans GB", sans-serif'
const MAX_ACTIVE = 400

export class DanmakuEngine {
  private canvas: HTMLCanvasElement
  private ctx: CanvasRenderingContext2D
  private options: EngineOptions
  private allocator = new TrackAllocator()
  private shield = new ShieldFilter()
  private pool: DanmakuInput[] = []
  private cursor = 0
  private active: ActiveItem[] = []
  private raf = 0
  private destroyed = false
  private lastVideoMs = -1
  private cssWidth = 0
  private cssHeight = 0
  private dpr = 1
  private lastFontSize = 0
  private lastArea = 0
  private seenKeys = new Set<string>()

  constructor(canvas: HTMLCanvasElement, options: EngineOptions) {
    const ctx = canvas.getContext('2d')
    if (!ctx) throw new Error('浏览器不支持 canvas 2d，弹幕无法渲染')
    this.canvas = canvas
    this.ctx = ctx
    this.options = options
    this.resize()
  }

  /** 容器尺寸变化时重建 canvas 像素尺寸（按 devicePixelRatio 缩放，保证高分屏文字不糊） */
  resize(): void {
    const width = this.canvas.clientWidth || this.canvas.parentElement?.clientWidth || 0
    const height = this.canvas.clientHeight || this.canvas.parentElement?.clientHeight || 0
    if (width <= 0 || height <= 0) return
    this.dpr = Math.min(window.devicePixelRatio || 1, 2)
    this.cssWidth = width
    this.cssHeight = height
    this.canvas.width = Math.round(width * this.dpr)
    this.canvas.height = Math.round(height * this.dpr)
    this.ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0)
  }

  start(): void {
    if (this.raf) return
    const loop = (): void => {
      if (this.destroyed) return
      this.raf = requestAnimationFrame(loop)
      this.tick()
    }
    this.raf = requestAnimationFrame(loop)
  }

  stop(): void {
    if (this.raf) cancelAnimationFrame(this.raf)
    this.raf = 0
  }

  destroy(): void {
    this.destroyed = true
    this.stop()
    this.pool = []
    this.active = []
  }

  /** 拖进度条/切集：清空当前屏内弹幕，并把游标定位到新时间点 */
  seekTo(timeSec: number): void {
    this.active = []
    this.allocator.reset()
    const targetMs = timeSec * 1000
    this.cursor = this.lowerBound(targetMs - 200)
    this.lastVideoMs = targetMs
  }

  /** 清空所有数据（切集时调用） */
  clear(): void {
    this.pool = []
    this.seenKeys.clear()
    this.cursor = 0
    this.active = []
    this.allocator.reset()
    this.ctx.clearRect(0, 0, this.cssWidth, this.cssHeight)
  }

  /** 用一整批数据替换池子（旧接口 /api/danmaku/list 全量兜底时用） */
  replace(items: DanmakuInput[]): void {
    this.pool = []
    this.seenKeys.clear()
    this.addItems(items)
    this.seekTo(this.options.getTime())
  }

  /** 增量追加（时间窗预取），按 id 或 text+time 去重，保证池子按时间有序 */
  addItems(items: DanmakuInput[]): void {
    if (!items.length) return
    let appended = false
    let appendedOutOfOrder = false
    const lastTime = this.pool.length ? this.pool[this.pool.length - 1].time : -1
    for (const raw of items) {
      if (!raw || typeof raw.text !== 'string' || !raw.text.trim()) continue
      const key = raw.id ? 'id:' + raw.id : `${raw.time}|${raw.text}|${raw.mode}`
      if (this.seenKeys.has(key)) continue
      this.seenKeys.add(key)
      const item: DanmakuInput = {
        id: raw.id,
        text: raw.text,
        time: Number.isFinite(raw.time) ? raw.time : 0,
        mode: raw.mode || 'SCROLL',
        color: raw.color || '#ffffff',
        fontSize: raw.fontSize
      }
      if (item.time < lastTime) appendedOutOfOrder = true
      this.pool.push(item)
      appended = true
    }
    if (appended && appendedOutOfOrder) this.pool.sort((a, b) => a.time - b.time)
    // 池子被整体替换后游标可能失效
    if (this.cursor > this.pool.length) this.cursor = this.pool.length
  }

  /** 实时弹幕（WS 收到别人的弹幕）：不进入池子，直接按当前时间发射 */
  pushLive(item: DanmakuInput): void {
    const settings = this.options.getSettings()
    if (!settings.enabled) return
    const normalized: DanmakuInput = {
      text: item.text,
      time: item.time,
      mode: item.mode || 'SCROLL',
      color: item.color || '#ffffff',
      fontSize: item.fontSize
    }
    this.shield.update(settings)
    if (this.shield.isBlocked(normalized)) return
    this.spawn(normalized, settings, this.options.getTime() * 1000)
  }

  /** 设置变化：重建屏蔽规则与轨道尺寸（字号/显示区域会改变轨道数量） */
  onSettingsChanged(settings: DanmakuSettings): void {
    this.shield.update(settings)
    const trackHeight = settings.fontSize * 1.45
    if (settings.fontSize !== this.lastFontSize || settings.displayArea !== this.lastArea) {
      this.lastFontSize = settings.fontSize
      this.lastArea = settings.displayArea
      this.allocator.setSize(this.cssWidth, this.cssHeight, trackHeight, settings.displayArea)
      this.active = []
    }
  }

  /** 二分查找：第一条 time >= targetMs 的池子下标 */
  private lowerBound(targetMs: number): number {
    let low = 0
    let high = this.pool.length
    while (low < high) {
      const mid = (low + high) >> 1
      if (this.pool[mid].time * 1000 < targetMs) low = mid + 1
      else high = mid
    }
    return low
  }

  private tick(): void {
    const settings = this.options.getSettings()
    const videoMs = this.options.getTime() * 1000

    if (this.cssWidth <= 0 || this.cssHeight <= 0) this.resize()
    this.ensureLayout(settings)

    if (!settings.enabled) {
      this.ctx.clearRect(0, 0, this.cssWidth, this.cssHeight)
      this.lastVideoMs = videoMs
      return
    }

    // 拖进度条检测：一次 rAF 内时间跳跃超过 1.5s 视为 seek
    if (this.lastVideoMs >= 0 && Math.abs(videoMs - this.lastVideoMs) > 1500) {
      this.seekTo(videoMs / 1000)
    }
    this.lastVideoMs = videoMs

    this.consume(settings, videoMs)
    this.render(settings, videoMs)
  }

  private ensureLayout(settings: DanmakuSettings): void {
    const trackHeight = settings.fontSize * 1.45
    if (settings.fontSize !== this.lastFontSize || settings.displayArea !== this.lastArea) {
      this.lastFontSize = settings.fontSize
      this.lastArea = settings.displayArea
      this.allocator.setSize(this.cssWidth, this.cssHeight, trackHeight, settings.displayArea)
    } else {
      this.allocator.setSize(this.cssWidth, this.cssHeight, trackHeight, settings.displayArea)
    }
    this.shield.update(settings)
  }

  /** 消费池子中"已到时间"的弹幕 */
  private consume(settings: DanmakuSettings, videoMs: number): void {
    const limit = videoMs + 20
    while (this.cursor < this.pool.length && this.pool[this.cursor].time * 1000 <= limit) {
      const item = this.pool[this.cursor]
      this.cursor++
      // 已经过去超过 1s 的弹幕不补发（快进后不要一次性糊满屏幕）
      if (videoMs - item.time * 1000 > 1000) continue
      if (this.shield.isBlocked(item)) continue
      this.spawn(item, settings, videoMs)
    }
  }

  private spawn(item: DanmakuInput, settings: DanmakuSettings, videoMs: number): void {
    if (this.active.length >= MAX_ACTIVE) return
    const fontSize = item.fontSize && item.fontSize > 0 ? Math.max(item.fontSize, 12) : settings.fontSize
    this.ctx.font = `bold ${fontSize}px ${FONT_FAMILY}`
    const width = Math.ceil(this.ctx.measureText(item.text).width) + 4
    const kind: TrackKind = item.mode === 'TOP' ? 'top' : item.mode === 'BOTTOM' ? 'bottom' : 'scroll'
    const duration = kind === 'scroll' ? BASE_DURATION / Math.max(0.4, settings.speed) : FIXED_DURATION
    const y = this.allocator.allocate(kind, width, videoMs, duration)
    if (y === null) return // 没有空闲轨道：丢弃而不是叠字
    const speed = kind === 'scroll' ? (this.cssWidth + width) / duration : 0
    this.active.push({ item, kind, startTime: videoMs, duration, width, y, speed })
  }

  private render(settings: DanmakuSettings, videoMs: number): void {
    const ctx = this.ctx
    ctx.clearRect(0, 0, this.cssWidth, this.cssHeight)
    ctx.textBaseline = 'top'
    const survivors: ActiveItem[] = []
    for (const active of this.active) {
      const elapsed = videoMs - active.startTime
      if (elapsed > active.duration || elapsed < -200) continue
      const fontSize = active.item.fontSize && active.item.fontSize > 0
        ? Math.max(active.item.fontSize, 12)
        : settings.fontSize
      let x: number
      if (active.kind === 'scroll') {
        x = this.cssWidth - active.speed * elapsed
      } else {
        x = (this.cssWidth - active.width) / 2
      }
      // 离场前 350ms 渐隐，避免弹幕"啪"一下消失
      const remain = active.duration - elapsed
      const alpha = remain < 350 ? Math.max(0, remain / 350) : 1
      ctx.globalAlpha = Math.max(0, Math.min(1, settings.opacity * alpha))
      ctx.font = `bold ${fontSize}px ${FONT_FAMILY}`
      // 先描边再填充：深色/浅色画面上都能看清（哔哩哔哩同款做法）
      ctx.lineWidth = 3
      ctx.strokeStyle = 'rgba(0,0,0,0.72)'
      ctx.strokeText(active.item.text, x, active.y)
      ctx.fillStyle = active.item.color || '#ffffff'
      ctx.fillText(active.item.text, x, active.y)
      survivors.push(active)
    }
    ctx.globalAlpha = 1
    this.active = survivors
  }
}
