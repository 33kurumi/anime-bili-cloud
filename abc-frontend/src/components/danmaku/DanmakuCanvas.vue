<template>
  <div ref="wrapRef" class="danmaku-layer">
    <canvas ref="canvasRef" class="danmaku-canvas"></canvas>
  </div>
</template>

<script setup lang="ts">
/**
 * 弹幕渲染层（覆盖在 <video> 之上的一层透明 canvas）。
 * 播放页只负责"数据从哪来"，这里只负责"怎么画"，两者解耦：
 * 换播放器/换数据源都不影响弹幕引擎。
 */
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { DanmakuEngine } from './DanmakuEngine'
import type { DanmakuInput, DanmakuSettings } from './types'

const props = defineProps<{
  /** 播放器元素：弹幕时间轴跟随它的 currentTime */
  video: HTMLVideoElement | null
  settings: DanmakuSettings
}>()

const wrapRef = ref<HTMLDivElement | null>(null)
const canvasRef = ref<HTMLCanvasElement | null>(null)
let engine: DanmakuEngine | null = null
let resizeObserver: ResizeObserver | null = null

function currentTime(): number {
  return props.video ? props.video.currentTime || 0 : 0
}

function handleResize(): void {
  engine?.resize()
}

onMounted(() => {
  if (!canvasRef.value) return
  engine = new DanmakuEngine(canvasRef.value, {
    getTime: currentTime,
    getSettings: () => props.settings
  })
  engine.start()
  window.addEventListener('resize', handleResize)
  if (wrapRef.value && typeof ResizeObserver !== 'undefined') {
    resizeObserver = new ResizeObserver(handleResize)
    resizeObserver.observe(wrapRef.value)
  }
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  resizeObserver?.disconnect()
  resizeObserver = null
  engine?.destroy()
  engine = null
})

// 设置变化立即作用于引擎（字号/显示区域会重新布局轨道）
watch(() => props.settings, value => engine?.onSettingsChanged(value), { deep: true })

/** 时间窗数据到达 */
function addItems(items: DanmakuInput[]): void {
  engine?.addItems(items)
}

/** 整批替换（兜底：一次性拉全量） */
function replaceItems(items: DanmakuInput[]): void {
  engine?.replace(items)
}

/** WS 实时弹幕 */
function pushLive(item: DanmakuInput): void {
  engine?.pushLive(item)
}

/** 切集/换源：清空画面 */
function clear(): void {
  engine?.clear()
}

/** 拖进度条后重新对齐 */
function seekTo(time: number): void {
  engine?.seekTo(time)
}

defineExpose({ addItems, replaceItems, pushLive, clear, seekTo })
</script>

<style scoped>
.danmaku-layer {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none; /* 关键：不能挡住播放器控制条与双击全屏 */
  z-index: 5;
}
.danmaku-canvas { width: 100%; height: 100%; display: block; }
</style>
