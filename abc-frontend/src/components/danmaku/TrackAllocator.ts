/**
 * 弹幕轨道分配器（借鉴 weizhenye/Danmaku 的 allocate.js 思路，重新推导实现）。
 *
 * 【要解决的问题】滚动弹幕如果只按"当前哪条轨道空着"来放，会出现在轨道上"后一条追上并盖住前一条"的叠字。
 * 判定一条新弹幕能否放进某条轨道，需要同时满足两个条件（设画布宽 W，弹幕宽 w，速度 v px/ms）：
 *
 *   条件①「前一条已经完整进入屏幕」：
 *     前一条弹幕 A 的右边缘必须已经越过画布右边界，否则新弹幕 B 一出生就和 A 重叠。
 *     elapsed * vA >= wA
 *
 *   条件②「B 在 A 完全离场前追不上 A」：
 *     A 完全离场（右边缘到 x=0）的时刻 tExit = tA + (W + wA) / vA。
 *     要求 B 的左边缘在 tExit 时仍 >= 0，即 vB * (tExit - tB) <= W。
 *
 * 任一条件不满足就试下一条轨道；全部占用则返回 null（本帧丢弃，不强行叠字）。
 *
 * 顶部/底部弹幕是静止的，只判断"前一条是否还在显示时长内"。
 */

export type TrackKind = 'scroll' | 'top' | 'bottom'

interface TrackOccupant {
  /** 该弹幕出现时的视频时间（ms） */
  startTime: number
  /** 弹幕像素宽度 */
  width: number
  /** 水平速度 px/ms（仅滚动弹幕使用） */
  speed: number
  /** 存活时长 ms */
  duration: number
}

export class TrackAllocator {
  private width = 0
  private height = 0
  private trackHeight = 26
  private areaRatio = 1

  private scrollTracks: (TrackOccupant | null)[] = []
  private topTracks: (TrackOccupant | null)[] = []
  private bottomTracks: (TrackOccupant | null)[] = []

  /** 画布尺寸或字号变化时重建轨道（清空占用记录） */
  setSize(width: number, height: number, trackHeight: number, areaRatio: number): void {
    const sizeChanged = width !== this.width || height !== this.height
    this.width = width
    this.height = height
    this.trackHeight = Math.max(16, trackHeight)
    this.areaRatio = Math.min(1, Math.max(0.2, areaRatio))
    const trackCount = Math.max(1, Math.floor((height * this.areaRatio) / this.trackHeight))
    if (sizeChanged || this.scrollTracks.length !== trackCount) {
      this.scrollTracks = new Array<TrackOccupant | null>(trackCount).fill(null)
      this.topTracks = new Array<TrackOccupant | null>(trackCount).fill(null)
      this.bottomTracks = new Array<TrackOccupant | null>(trackCount).fill(null)
    }
  }

  /** 拖进度条/切集后清空占用，避免旧轨道的占位把新弹幕全挤掉 */
  reset(): void {
    this.scrollTracks.fill(null)
    this.topTracks.fill(null)
    this.bottomTracks.fill(null)
  }

  /**
   * 申请一条轨道，返回 y 坐标（画布 CSS 像素）；没有可用轨道返回 null。
   * 注意：轨道 y 使用的是"轨道索引"，顶部弹幕从第 0 轨开始，底部弹幕从最底轨往上排。
   */
  allocate(kind: TrackKind, textWidth: number, nowMs: number, durationMs: number): number | null {
    if (this.scrollTracks.length === 0) return null
    if (kind === 'scroll') {
      const speed = (this.width + textWidth) / durationMs
      for (let i = 0; i < this.scrollTracks.length; i++) {
        const last = this.scrollTracks[i]
        if (last && !this.isScrollTrackFree(last, nowMs, speed)) continue
        this.scrollTracks[i] = { startTime: nowMs, width: textWidth, speed, duration: durationMs }
        return i * this.trackHeight
      }
      return null
    }
    const tracks = kind === 'top' ? this.topTracks : this.bottomTracks
    for (let i = 0; i < tracks.length; i++) {
      const last = tracks[i]
      if (last && nowMs - last.startTime < last.duration) continue
      tracks[i] = { startTime: nowMs, width: textWidth, speed: 0, duration: durationMs }
      // 底部弹幕：第 0 轨贴最底，往上递增
      return kind === 'top' ? i * this.trackHeight : this.height * this.areaRatio - (i + 1) * this.trackHeight
    }
    return null
  }

  /** 轨道是否可容纳新滚动弹幕（条件①②） */
  private isScrollTrackFree(last: TrackOccupant, nowMs: number, speed: number): boolean {
    const elapsed = nowMs - last.startTime
    if (elapsed < 0) return false
    const vA = last.speed
    if (vA <= 0) return false
    // 条件①：前一条已完整进入画布
    if (elapsed * vA < last.width) return false
    // 条件②：新弹幕在 A 离场前追不上
    const timeToExit = (this.width + last.width) / vA
    if (speed * (timeToExit - elapsed) > this.width) return false
    return true
  }
}
