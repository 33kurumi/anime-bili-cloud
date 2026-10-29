<template>
  <div class="play-page" ref="pageRef">
    <!-- 顶栏：返回 / 标题 / 换源 / 设置 -->
    <header class="topbar">
      <button class="icon-btn" title="返回" @click="goBack"><el-icon><ArrowLeft /></el-icon></button>
      <div class="top-title">
        <span class="ellipsis">{{ animeTitle || animeKey }}</span>
        <span class="weak top-ep">第 {{ epNum }} 话</span>
      </div>
      <div class="top-actions">
        <el-select
          :model-value="String(currentRuleId)"
          size="small"
          class="src-select"
          placeholder="播放线路"
          @change="onSelectSource"
        >
          <el-option label="自动择优" value="0" />
          <el-option
            v-for="c in candidates"
            :key="String(c.ruleId)"
            :label="(c.name || ('线路' + c.ruleId)) + (c.score ? ' · ' + c.score : '')"
            :value="String(c.ruleId)"
          />
        </el-select>
        <button class="icon-btn" title="重新解析" @click="reloadSource"><el-icon><Refresh /></el-icon></button>
      </div>
    </header>

    <!-- 播放器区域 -->
    <div class="player-wrap" ref="wrapRef" @mousemove="showControlsTemporarily">
      <video
        ref="videoEl"
        class="video"
        crossorigin="anonymous"
        playsinline
        @click="togglePlay"
        @loadedmetadata="onLoadedMetadata"
        @timeupdate="onTimeUpdate"
        @play="isPlaying = true"
        @pause="isPlaying = false"
        @ended="onEnded"
        @waiting="buffering = true"
        @playing="buffering = false"
        @canplay="buffering = false"
        @error="onVideoError"
      ></video>

      <!-- 自研 canvas 弹幕层（位于 video 之上，pointer-events:none） -->
      <DanmakuCanvas ref="danmakuRef" :video="videoEl" :settings="settings" />

      <!-- 加载中 -->
      <div v-if="loading" class="overlay">
        <div class="spinner"></div>
        <p>{{ loadingText }}</p>
      </div>

      <!-- 解析失败 / 全部线路失败 -->
      <div v-else-if="errorMsg" class="overlay error">
        <p class="err-title">播放失败</p>
        <p class="err-msg">{{ errorMsg }}</p>
        <div class="err-actions">
          <el-button type="primary" @click="reloadSource">重试</el-button>
          <el-button v-if="candidates.length" @click="openSourcePanel">选择其他线路</el-button>
          <el-button text @click="goBack">返回详情</el-button>
        </div>
        <div v-if="candidates.length" class="cand-list">
          <div v-for="c in candidates" :key="String(c.ruleId)" class="cand-item" @click="onSelectSource(String(c.ruleId))">
            <span>{{ c.name || ('线路' + c.ruleId) }}</span>
            <span class="weak">{{ c.rankReason || ('匹配分 ' + (c.score ?? '-')) }}</span>
          </div>
        </div>
      </div>

      <!-- 暂停时的中央播放按钮 -->
      <button v-if="!loading && !errorMsg && !isPlaying" class="big-play" @click="togglePlay">
        <el-icon><VideoPlay /></el-icon>
      </button>

      <!-- 缓冲提示 -->
      <div v-if="buffering && !loading && !errorMsg" class="buffering">缓冲中…</div>

      <!-- 控制条 -->
      <div class="controls" :class="{ hide: !controlsVisible }" @click.stop>
        <!-- 进度条 + 弹幕热度 -->
        <div class="progress-row">
          <div class="progress" ref="barRef" @click="onBarClick">
            <div class="bar-bg"></div>
            <div v-for="(h, i) in heatBars" :key="i" class="heat" :style="h.style"></div>
            <div class="bar-played" :style="{ width: playedPercent + '%' }"></div>
            <div class="bar-dot" :style="{ left: playedPercent + '%' }"></div>
          </div>
        </div>

        <div class="ctrl-row">
          <button class="icon-btn" :title="isPlaying ? '暂停' : '播放'" @click="togglePlay">
            <el-icon><component :is="isPlaying ? VideoPause : VideoPlay" /></el-icon>
          </button>
          <span class="time">{{ fmt(currentTime) }} / {{ fmt(duration) }}</span>

          <div class="spacer"></div>

          <button class="txt-btn" :class="{ off: !settings.enabled }" title="弹幕开关" @click="toggleDanmaku">弹</button>
          <button class="icon-btn" title="弹幕设置" @click="showSettings = !showSettings">
            <el-icon><Setting /></el-icon>
          </button>

          <el-select v-model="rate" size="small" class="rate-select" @change="onRateChange">
            <el-option v-for="r in RATES" :key="r" :label="r + 'x'" :value="r" />
          </el-select>

          <div class="volume">
            <button class="icon-btn" :title="muted ? '取消静音' : '静音'" @click="toggleMute">
              <el-icon><component :is="muted ? Mute : Microphone" /></el-icon>
            </button>
            <input type="range" min="0" max="1" step="0.05" :value="volume" @input="onVolumeInput" class="vol-range" />
          </div>

          <button class="icon-btn" title="全屏" @click="toggleFullscreen"><el-icon><FullScreen /></el-icon></button>
        </div>
      </div>

      <!-- 弹幕设置面板 -->
      <DanmakuSettingsPanel
        v-if="showSettings"
        v-model="settings"
        :total="danmakuCount"
        @click.stop
      />

      <!-- 弹幕输入条 -->
      <div class="dm-bar" @click.stop>
        <span class="dm-status" :class="wsStatus">{{ wsStatusText }}</span>
        <el-color-picker v-model="dmColor" size="small" :predefine="COLORS" />
        <el-select v-model="dmPos" size="small" class="dm-pos">
          <el-option label="滚动" :value="0" />
          <el-option label="顶部" :value="1" />
          <el-option label="底部" :value="2" />
        </el-select>
        <el-input
          v-model="dmText"
          size="small"
          maxlength="100"
          :placeholder="userStore.isLoggedIn() ? '发个弹幕吧～' : '登录后可发送弹幕（仍可观看弹幕）'"
          :disabled="!userStore.isLoggedIn()"
          class="dm-input"
          @keyup.enter="sendDanmaku"
        />
        <el-button size="small" type="primary" :disabled="!userStore.isLoggedIn()" @click="sendDanmaku">发送</el-button>
        <span class="weak dm-online">在线 {{ online }} 人</span>
      </div>
    </div>

    <!-- 选集 -->
    <section class="ep-section">
      <div class="ep-head">
        <span class="ep-title">选集</span>
        <span class="weak">共 {{ episodes.length }} 话</span>
      </div>
      <div class="ep-strip">
        <button
          v-for="ep in episodes"
          :key="ep.episodeNum"
          class="ep-chip"
          :class="{ active: ep.episodeNum === epNum }"
          @click="switchEpisode(ep.episodeNum)"
        >
          {{ String(ep.episodeNum).padStart(2, '0') }}
        </button>
      </div>
    </section>

    <div class="play-foot">
      <span class="weak">播放线路：{{ playInfo?.sourceName || '未命中' }}</span>
      <span v-if="playInfo?.matchedTitle" class="weak">匹配：{{ playInfo.matchedTitle }}</span>
      <span class="weak">阶段：{{ playInfo?.stageUsed || '-' }}</span>
      <span class="weak">解析耗时：{{ playInfo?.elapsedMs ?? '-' }} ms</span>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 播放页（核心页面）。
 *
 * 【与旧版的差异 —— 答辩重点】
 * 1) 播放器：弃用 DPlayer，改原生 <video> + hls.js。原因：DPlayer 封装了 hls 实例，
 *    无法在 fatal error 时按候选线路自动换源，也无法给 hls 请求加 headers；
 * 2) 换源：/api/source/play/{animeKey}/{ep} 返回 candidates[]，失败自动按顺序切，
 *    手动可从下拉框选；直链带 Referer/UA 防盗链时优先走后端签名代理 proxyUrl，避免浏览器跨域；
 * 3) 弹幕：自研 canvas 引擎（DanmakuCanvas），时间窗增量拉取 /api/danmaku/window，
 *    WS /ws/danmaku/{animeKey}/{ep} 实时收发，断线指数退避重连；
 * 4) 进度：每 10s + 暂停/卸载上报 /api/user/progress，进入时读取断点并询问续播；
 * 5) 匿名可看：未登录照样播放（/api/source/play 在白名单），只是不能发弹幕。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft, FullScreen, Microphone, Mute, Refresh, Setting, VideoPause, VideoPlay
} from '@element-plus/icons-vue'
import Hls from 'hls.js'
import DanmakuCanvas from '@/components/danmaku/DanmakuCanvas.vue'
import DanmakuSettingsPanel from '@/components/danmaku/DanmakuSettingsPanel.vue'
import { loadDanmakuSettings, type DanmakuInput, type DanmakuSettings } from '@/components/danmaku/types'
import { get, post, tokenStore, type Query } from '@/api'
import { useUserStore } from '@/stores/user'
import type { DanmakuItem, EpisodeItem, PlayInfo, ProgressSyncResult, SourceCandidate } from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const RATES = [0.5, 0.75, 1, 1.25, 1.5, 2]
const COLORS = ['#FFFFFF', '#FF6699', '#FFD700', '#00C4FF', '#7CFC00', '#FF4500']
const WINDOW_SPAN = 60      // 一次拉 60 秒的弹幕
const PREFETCH_AHEAD = 20   // 距窗口末尾 20 秒时预取下一窗
const SEEK_THRESHOLD = 3    // 进度跳变超过 3 秒视为拖动
const MAX_RETRY = 3         // 自动换源最多尝试次数

const pageRef = ref<HTMLElement | null>(null)
const wrapRef = ref<HTMLElement | null>(null)
const barRef = ref<HTMLElement | null>(null)
const videoEl = ref<HTMLVideoElement | null>(null)
const danmakuRef = ref<InstanceType<typeof DanmakuCanvas> | null>(null)

const animeKey = computed(() => decodeURIComponent(String(route.params.animeKey ?? '')))
const epNum = computed(() => Number(route.params.episodeNum ?? 1))

const animeTitle = ref('')
const episodes = ref<EpisodeItem[]>([])
const playInfo = ref<PlayInfo | null>(null)
const candidates = ref<SourceCandidate[]>([])
const currentRuleId = ref(0)

const loading = ref(true)
const loadingText = ref('正在解析播放线路…')
const errorMsg = ref('')
const buffering = ref(false)
const isPlaying = ref(false)
const controlsVisible = ref(true)
const currentTime = ref(0)
const duration = ref(0)
const volume = ref(0.8)
const muted = ref(false)
const rate = ref(1)
const online = ref(0)
const danmakuCount = ref(0)

const settings = ref<DanmakuSettings>(loadDanmakuSettings())
const showSettings = ref(false)
const dmText = ref('')
const dmColor = ref('#FFFFFF')
const dmPos = ref(0)

const wsStatus = ref<'idle' | 'connecting' | 'open' | 'closed'>('idle')
const heat = ref<Array<{ startTime: number; count: number }>>([])

let hls: Hls | null = null
let ws: WebSocket | null = null
let wsTries = 0
let wsTimer: number | undefined
let progressTimer: number | undefined
let hideTimer: number | undefined
let failTimer: number | undefined
let destroyed = false
let attempts = 0
let lastTime = 0
let windowEnd = 0
let lastWindowLoadAt = 0
const fetchedRanges: Array<[number, number]> = []

const playedPercent = computed(() => (duration.value > 0 ? (currentTime.value / duration.value) * 100 : 0))
const wsStatusText = computed(() => {
  if (wsStatus.value === 'open') return '弹幕已连接'
  if (wsStatus.value === 'connecting') return '弹幕连接中…'
  if (wsStatus.value === 'closed') return '连接已断开'
  return '弹幕未连接'
})
const maxHeat = computed(() => Math.max(1, ...heat.value.map(h => h.count)))

/** 弹幕热度柱：按秒定位到进度条上 */
const heatBars = computed(() => {
  if (!duration.value) return []
  return heat.value
    .filter(h => h.count > 0)
    .map(h => ({
      style: {
        left: Math.min(100, (h.startTime / duration.value) * 100) + '%',
        height: Math.max(12, Math.round((h.count / maxHeat.value) * 100)) + '%'
      }
    }))
})

onMounted(async () => {
  window.addEventListener('keydown', onKeydown)
  await loadEpisodes()
  await loadTitle()
  await loadPlayInfo()
  loadHeat()
  connectWs()
})

onUnmounted(() => {
  destroyed = true
  window.removeEventListener('keydown', onKeydown)
  window.clearTimeout(hideTimer)
  window.clearInterval(progressTimer)
  window.clearTimeout(failTimer)
  window.clearTimeout(wsTimer)
  saveProgress(true)
  closeWs()
  destroyHls()
  if (videoEl.value) {
    videoEl.value.pause()
    videoEl.value.removeAttribute('src')
    videoEl.value.load()
  }
})

watch(epNum, async () => {
  resetPlayback()
  await loadPlayInfo()
  connectWs()
  loadHeat()
})

// ───────────────────────── 数据加载 ─────────────────────────

async function loadTitle(): Promise<void> {
  try {
    const data = await get<{ title?: string }>('/api/anime/' + encodeURIComponent(animeKey.value), undefined, { silent: true })
    animeTitle.value = data?.title || ''
  } catch {
    animeTitle.value = ''
  }
}

async function loadEpisodes(): Promise<void> {
  try {
    const rows = await get<EpisodeItem[]>(
      '/api/anime/' + encodeURIComponent(animeKey.value) + '/episodes',
      undefined,
      { silent: true }
    )
    episodes.value = (rows || []).filter(e => e && e.episodeNum != null).sort((a, b) => a.episodeNum - b.episodeNum)
  } catch {
    episodes.value = []
  }
}

/**
 * 解析播放地址：优先新接口（路径版），失败回退旧接口（query 版）。
 * hit=false 时后端 HTTP 仍 200，用 message 说明原因（如"所有资源源均未匹配"）。
 */
async function loadPlayInfo(ruleId?: number): Promise<void> {
  loading.value = true
  errorMsg.value = ''
  loadingText.value = '正在解析播放线路…'
  const params: Query = {}
  if (animeTitle.value) params.title = animeTitle.value
  if (ruleId) params.ruleId = ruleId

  let info: PlayInfo | null = null
  try {
    info = await get<PlayInfo>(
      '/api/source/play/' + encodeURIComponent(animeKey.value) + '/' + epNum.value,
      params,
      { silent: true }
    )
  } catch {
    // 旧接口兼容（网关白名单里的 /api/source/play）
    try {
      info = await get<PlayInfo>('/api/source/play', {
        animeKey: animeKey.value,
        ep: epNum.value,
        title: animeTitle.value
      }, { silent: true })
    } catch {
      info = null
    }
  }

  loading.value = false
  if (!info || (!info.url && !info.proxyUrl)) {
    errorMsg.value = info?.message || '未解析到可播放地址（所有资源源均未匹配到该番剧）'
    playInfo.value = info || null
    candidates.value = info?.candidates || []
    return
  }

  playInfo.value = info
  candidates.value = info.candidates || []
  currentRuleId.value = Number(info.ruleId || 0)
  setupVideo(info)
}

function setupVideo(info: PlayInfo): void {
  const video = videoEl.value
  if (!video) return
  destroyHls()
  window.clearTimeout(failTimer)

  const headers = info.headers || {}
  const hasHeaders = Object.keys(headers).length > 0
  // 有防盗链头时优先走后端签名代理：浏览器无法设置 Referer，直链必然 403
  const useProxy = Boolean(info.proxyUrl && hasHeaders)
  const src = useProxy ? String(info.proxyUrl) : String(info.url || info.proxyUrl)
  const isM3u8 = /.m3u8(\?|$)/i.test(src)

  if (isM3u8 && Hls.isSupported()) {
    hls = new Hls({
      enableWorker: true,
      lowLatencyMode: false,
      manifestLoadingTimeOut: 15000,
      fragLoadingTimeOut: 20000,
      xhrSetup: (xhr: XMLHttpRequest) => {
        // 直链模式尽量带上后端给的请求头（Referer 浏览器禁止设置，故有 headers 时走代理）
        if (!useProxy) {
          Object.keys(headers).forEach(k => {
            try { xhr.setRequestHeader(k, String(headers[k])) } catch { /* 浏览器禁止的头部忽略 */ }
          })
        }
      }
    })
    hls.loadSource(src)
    hls.attachMedia(video)
    hls.on(Hls.Events.ERROR, (_event, data) => {
      if (data && data.fatal) {
        // fatal: 网络/解码/清单错误 → 走换源逻辑
        onFatalError(String(data.type || 'HLS 错误'))
      }
    })
  } else if (isM3u8) {
    // Safari 原生支持 HLS
    video.src = src
  } else {
    video.src = src
  }

  // 8 秒还没拿到 metadata → 认为线路不可用，自动换源
  failTimer = window.setTimeout(() => {
    if (!destroyed && video.readyState < 1) onFatalError('播放超时（8s 未响应）')
  }, 8000)

  video.volume = volume.value
  video.playbackRate = rate.value
  const p = video.play()
  if (p && typeof p.catch === 'function') p.catch(() => { /* 自动播放被拦截，等用户点 */ })
  loadingText.value = '正在解析播放线路…'
}

function destroyHls(): void {
  if (hls) {
    try { hls.destroy() } catch { /* ignore */ }
    hls = null
  }
}

// ───────────────────────── 播放控制 & 换源 ─────────────────────────

async function onFatalError(reason: string): Promise<void> {
  if (destroyed) return
  window.clearTimeout(failTimer)
  buffering.value = false
  attempts += 1
  if (attempts > MAX_RETRY) {
    errorMsg.value = '所有线路均播放失败（' + reason + '），请手动选择线路'
    loading.value = false
    return
  }
  // 按候选顺序挑一个还没试过的线路
  const tried = new Set<number>([currentRuleId.value])
  const next = candidates.value.find(c => c.ruleId != null && !tried.has(Number(c.ruleId)))
  if (next && next.ruleId != null) {
    ElMessage.warning('线路异常，已切换至「' + (next.name || ('线路' + next.ruleId)) + '」')
    await loadPlayInfo(Number(next.ruleId))
    return
  }
  errorMsg.value = '播放失败：' + reason
  loading.value = false
}

function onVideoError(): void {
  const video = videoEl.value
  const code = video?.error?.code
  onFatalError('视频错误' + (code ? ' code=' + code : ''))
}

function onSelectSource(value: string): void {
  attempts = 0
  const ruleId = Number(value)
  loadPlayInfo(ruleId > 0 ? ruleId : undefined)
}

function reloadSource(): void {
  attempts = 0
  loadPlayInfo(currentRuleId.value > 0 ? currentRuleId.value : undefined)
}

function openSourcePanel(): void {
  // 简单用法：直接展示候选（复用错误面板里的列表）
  if (!candidates.value.length) ElMessage.info('暂无其他可用线路')
}

function switchEpisode(ep: number): void {
  router.replace('/play/' + encodeURIComponent(animeKey.value) + '/' + ep)
}

function goBack(): void {
  const pure = animeKey.value.replace(/^bgm:/, '')
  router.push(/^bgm:/.test(animeKey.value) ? '/anime/bgm:' + pure : '/anime/' + pure)
}

// ───────────────────────── 视频事件 ─────────────────────────

function togglePlay(): void {
  const video = videoEl.value
  if (!video) return
  if (video.paused) {
    const p = video.play()
    if (p && typeof p.catch === 'function') p.catch(() => { /* ignore */ })
  } else {
    video.pause()
    saveProgress()
  }
}

function onLoadedMetadata(): void {
  const video = videoEl.value
  if (!video) return
  duration.value = video.duration || 0
  window.clearTimeout(failTimer)
  attempts = 0
  loading.value = false
  loadHeat()
  resumePlayback()
}

function onTimeUpdate(): void {
  const video = videoEl.value
  if (!video) return
  const now = video.currentTime
  currentTime.value = now
  if (Math.abs(now - lastTime) > SEEK_THRESHOLD) {
    // 用户拖动进度条 / 快进 → 弹幕引擎同步 + 重新拉窗口
    onSeek(now)
  }
  lastTime = now
  danmakuTick(now)
}

function onSeek(sec: number): void {
  danmakuRef.value?.seekTo(sec)
  windowEnd = sec + WINDOW_SPAN
  loadDanmakuWindow(sec, windowEnd)
}

function onEnded(): void {
  isPlaying.value = false
  saveProgress(true)
}

function onBarClick(e: MouseEvent): void {
  const bar = barRef.value
  const video = videoEl.value
  if (!bar || !video || !duration.value) return
  const rect = bar.getBoundingClientRect()
  const ratio = Math.min(1, Math.max(0, (e.clientX - rect.left) / rect.width))
  video.currentTime = ratio * duration.value
  currentTime.value = video.currentTime
}

function onVolumeInput(e: Event): void {
  const value = Number((e.target as HTMLInputElement).value)
  volume.value = value
  muted.value = value === 0
  if (videoEl.value) {
    videoEl.value.volume = value
    videoEl.value.muted = value === 0
  }
}

function toggleMute(): void {
  const video = videoEl.value
  if (!video) return
  muted.value = !muted.value
  video.muted = muted.value
}

function onRateChange(value: number): void {
  rate.value = Number(value)
  if (videoEl.value) videoEl.value.playbackRate = rate.value
}

function toggleDanmaku(): void {
  settings.value = { ...settings.value, enabled: !settings.value.enabled }
}

async function toggleFullscreen(): Promise<void> {
  const wrap = wrapRef.value
  if (!wrap) return
  try {
    if (document.fullscreenElement) await document.exitFullscreen()
    else await wrap.requestFullscreen()
  } catch {
    ElMessage.warning('当前浏览器不支持全屏')
  }
}

function showControlsTemporarily(): void {
  controlsVisible.value = true
  window.clearTimeout(hideTimer)
  hideTimer = window.setTimeout(() => {
    if (isPlaying.value && !showSettings.value) controlsVisible.value = false
  }, 2600)
}

function onKeydown(e: KeyboardEvent): void {
  const target = e.target as HTMLElement | null
  if (target && (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA')) return
  const video = videoEl.value
  if (!video) return
  if (e.code === 'Space') {
    e.preventDefault()
    togglePlay()
  } else if (e.code === 'ArrowRight') {
    video.currentTime = Math.min(duration.value, video.currentTime + 5)
  } else if (e.code === 'ArrowLeft') {
    video.currentTime = Math.max(0, video.currentTime - 5)
  } else if (e.code === 'KeyF') {
    toggleFullscreen()
  } else if (e.code === 'KeyD') {
    toggleDanmaku()
  } else if (e.code === 'ArrowUp') {
    onVolumeInput({ target: { value: String(Math.min(1, volume.value + 0.1)) } } as unknown as Event)
  } else if (e.code === 'ArrowDown') {
    onVolumeInput({ target: { value: String(Math.max(0, volume.value - 0.1)) } } as unknown as Event)
  }
}

// ───────────────────────── 进度记忆 ─────────────────────────

function startProgressTimer(): void {
  window.clearInterval(progressTimer)
  progressTimer = window.setInterval(() => {
    if (videoEl.value && !videoEl.value.paused) saveProgress()
  }, 10000)
}

async function resumePlayback(): Promise<void> {
  const video = videoEl.value
  if (!video || !userStore.isLoggedIn()) {
    startProgressTimer()
    return
  }
  try {
    const data = await get<ProgressSyncResult>('/api/user/progress', { since: 0 }, { silent: true })
    const item = (data?.items || []).find(p => p.animeKey === animeKey.value && Number(p.episodeNum) === epNum.value)
    if (item && Number(item.positionSec) > 30) {
      const sec = Number(item.positionSec)
      try {
        await ElMessageBox.confirm('上次看到 ' + fmt(sec) + '，继续播放？', '继续观看', {
          confirmButtonText: '继续播放',
          cancelButtonText: '从头开始',
          type: 'info'
        })
        video.currentTime = sec
        danmakuRef.value?.seekTo(sec)
      } catch {
        video.currentTime = 0
      }
    }
  } catch {
    /* 后端未提供进度接口时静默降级 */
  }
  startProgressTimer()
}

/**
 * 上报进度。卸载时用 keepalive fetch（sendBeacon 无法带 Authorization 头）。
 */
function saveProgress(keepalive = false): void {
  const video = videoEl.value
  if (!video || !userStore.isLoggedIn()) return
  if (!video.duration || !Number.isFinite(video.duration)) return
  const body = {
    animeKey: animeKey.value,
    episodeNum: epNum.value,
    positionSec: Math.floor(video.currentTime || 0),
    durationSec: Math.floor(video.duration),
    finished: video.currentTime / video.duration > 0.95 ? 1 : 0,
    device: 'web'
  }
  if (keepalive) {
    const token = tokenStore.get()
    fetch('/api/user/progress', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + (token || '') },
      body: JSON.stringify(body),
      keepalive: true
    }).catch(() => { /* 页面卸载中，失败无所谓 */ })
    return
  }
  post('/api/user/progress', body, { silent: true }).catch(() => { /* 静默失败 */ })
}

// ───────────────────────── 弹幕：热度 / 时间窗 / WS ─────────────────────────

async function loadHeat(): Promise<void> {
  try {
    const data = await get<Array<{ startTime: number; count: number }>>(
      '/api/danmaku/heat',
      { animeKey: animeKey.value, ep: epNum.value },
      { silent: true }
    )
    heat.value = data || []
  } catch {
    heat.value = []
  }
}

// TODO(后端契约): /api/danmaku/window 与 /api/danmaku/heat 属于新增接口，上线后本文件的
// /api/danmaku/list 全量兜底与 loadHeat 前端聚合分支即可删除（当前保留以便后端未部署时仍能显示弹幕）。
/** 时间窗增量拉取；后端未就绪时整体降级为一次性拉全量 */
async function loadDanmakuWindow(start: number, end: number): Promise<void> {
  const s = Math.max(0, Math.floor(start))
  const e = Math.ceil(end)
  if (fetchedRanges.some(([fs, fe]) => fs <= s && fe >= e)) return
  try {
    const rows = await get<DanmakuItem[]>(
      '/api/danmaku/window',
      { animeKey: animeKey.value, ep: epNum.value, startTime: s, endTime: e, limit: 400 },
      { silent: true }
    )
    pushDanmaku(rows || [])
    fetchedRanges.push([s, e])
    windowEnd = e
  } catch {
    // 老接口兜底：一次拉全量，前端按时间窗口过滤交给引擎
    if (fetchedRanges.length) return
    try {
      const rows = await get<DanmakuItem[]>(
        '/api/danmaku/list',
        { animeKey: animeKey.value, ep: epNum.value, title: animeTitle.value },
        { silent: true }
      )
      pushDanmaku(rows || [])
      fetchedRanges.push([0, Number.MAX_SAFE_INTEGER])
    } catch {
      /* 弹幕服务不可用：不影响看视频 */
    }
  }
}

function toEngineItem(raw: DanmakuItem): DanmakuInput | null {
  const text = String(raw.text ?? '').trim()
  if (!text) return null
  const mode = raw.mode === 'TOP' || raw.mode === 'BOTTOM'
    ? raw.mode
    : raw.position === 1 ? 'TOP' : raw.position === 2 ? 'BOTTOM' : 'SCROLL'
  return {
    id: raw.id,
    text,
    time: Number(raw.timestamp ?? 0),
    mode,
    color: raw.color,
    fontSize: raw.fontSize
  }
}

function pushDanmaku(rows: DanmakuItem[]): void {
  const items: DanmakuInput[] = []
  rows.forEach(raw => {
    const item = toEngineItem(raw)
    if (item) items.push(item)
  })
  danmakuCount.value += items.length
  danmakuRef.value?.addItems(items)
}

/** 每 5 秒检查一次是否需要预取下一窗 */
function danmakuTick(now: number): void {
  const ts = Date.now()
  if (ts - lastWindowLoadAt < 5000) return
  lastWindowLoadAt = ts
  if (now > windowEnd - PREFETCH_AHEAD) {
    loadDanmakuWindow(windowEnd, windowEnd + WINDOW_SPAN)
  }
}

function connectWs(): void {
  closeWs()
  if (!animeKey.value) return
  const proto = window.location.protocol === 'https:' ? 'wss' : 'ws'
  const token = tokenStore.get()
  let url = proto + '://' + window.location.host + '/ws/danmaku/' +
    encodeURIComponent(animeKey.value) + '/' + epNum.value
  if (token) url += '?token=' + encodeURIComponent(token)
  wsStatus.value = 'connecting'
  try {
    ws = new WebSocket(url)
  } catch {
    wsStatus.value = 'closed'
    return
  }
  ws.onopen = () => {
    wsStatus.value = 'open'
    wsTries = 0
  }
  ws.onmessage = event => {
    try {
      const data = JSON.parse(String(event.data))
      if (data && data.type === 'error') {
        ElMessage.warning(String(data.message || '弹幕发送失败'))
        return
      }
      const item = toEngineItem(data as DanmakuItem)
      if (item) {
        danmakuCount.value += 1
        danmakuRef.value?.pushLive(item)
      }
    } catch {
      /* 非法帧忽略 */
    }
  }
  ws.onclose = () => {
    if (destroyed) return
    wsStatus.value = 'closed'
    // 指数退避重连：1s/2s/4s/8s/16s，最多 5 次
    if (wsTries >= 5) return
    const delay = 1000 * Math.pow(2, wsTries)
    wsTries += 1
    window.clearTimeout(wsTimer)
    wsTimer = window.setTimeout(() => {
      if (!destroyed) connectWs()
    }, delay)
  }
  ws.onerror = () => {
    wsStatus.value = 'closed'
  }
}

function closeWs(): void {
  window.clearTimeout(wsTimer)
  if (ws) {
    ws.onclose = null
    ws.onmessage = null
    ws.onerror = null
    try { ws.close() } catch { /* ignore */ }
    ws = null
  }
  wsStatus.value = 'idle'
}

function sendDanmaku(): void {
  if (!userStore.isLoggedIn()) {
    ElMessage.warning('登录后才能发送弹幕')
    return
  }
  const text = dmText.value.trim()
  if (!text) return
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    ElMessage.warning('弹幕连接已断开，正在重连…')
    connectWs()
    return
  }
  // 协议：只发业务字段，身份/时间由服务端按 JWT 与房间覆盖
  ws.send(JSON.stringify({
    text,
    timestamp: Math.floor(currentTime.value),
    color: dmColor.value || '#FFFFFF',
    position: dmPos.value,
    fontSize: settings.value.fontSize
  }))
  dmText.value = ''
}

// 弹幕开关变化时立刻作用于引擎
watch(() => settings.value.enabled, value => {
  if (!value) danmakuRef.value?.clear()
})

async function resetPlayback(): Promise<void> {
  saveProgress(true)
  window.clearInterval(progressTimer)
  window.clearTimeout(failTimer)
  destroyHls()
  fetchedRanges.length = 0
  windowEnd = 0
  lastTime = 0
  currentTime.value = 0
  duration.value = 0
  errorMsg.value = ''
  danmakuCount.value = 0
  danmakuRef.value?.clear()
  await nextTick()
}

function fmt(sec: number): string {
  if (!sec || !Number.isFinite(sec) || sec < 0) return '00:00'
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const s = Math.floor(sec % 60)
  const pad = (n: number) => String(n).padStart(2, '0')
  return h > 0 ? pad(h) + ':' + pad(m) + ':' + pad(s) : pad(m) + ':' + pad(s)
}
</script>

<style scoped>
.play-page {
  min-height: 100vh;
  background: #07080c;
  padding: 0 0 40px;
}
.topbar {
  display: flex; align-items: center; gap: 12px;
  padding: 10px 16px; height: 54px;
  background: rgba(12, 14, 20, .92); position: sticky; top: 0; z-index: 40;
  border-bottom: 1px solid rgba(255, 255, 255, .06);
}
.icon-btn {
  width: 34px; height: 34px; border-radius: 50%; border: none; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  background: rgba(255, 255, 255, .08); color: #e9ecf3; font-size: 16px;
}
.icon-btn:hover { background: rgba(255, 255, 255, .16); }
.top-title { flex: 1; min-width: 0; display: flex; align-items: baseline; gap: 8px; font-size: 15px; font-weight: 600; }
.top-ep { font-size: 12px; }
.top-actions { display: flex; align-items: center; gap: 8px; }
.src-select { width: 190px; }

.player-wrap {
  position: relative;
  width: 100%;
  max-width: 1400px;
  margin: 0 auto;
  aspect-ratio: 16 / 9;
  max-height: calc(100vh - 200px);
  background: #000;
  overflow: hidden;
}
.player-wrap:fullscreen { max-height: none; height: 100vh; aspect-ratio: auto; }
.video { width: 100%; height: 100%; display: block; background: #000; }

.overlay {
  position: absolute; inset: 0; z-index: 10;
  display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 12px;
  background: rgba(6, 8, 12, .82); color: #dfe4ee; text-align: center; padding: 20px;
}
.overlay.error .err-title { font-size: 17px; font-weight: 700; color: #ff8080; }
.overlay.error .err-msg { color: var(--c-text-dim); font-size: 13px; max-width: 560px; }
.err-actions { display: flex; gap: 10px; flex-wrap: wrap; justify-content: center; margin-top: 6px; }
.cand-list { margin-top: 10px; max-height: 180px; overflow: auto; width: min(560px, 92%); }
.cand-item {
  display: flex; justify-content: space-between; gap: 12px; font-size: 12.5px;
  padding: 7px 10px; border-radius: 8px; cursor: pointer; background: rgba(255, 255, 255, .05);
  margin-bottom: 6px;
}
.cand-item:hover { background: rgba(251, 114, 153, .18); }

.spinner {
  width: 34px; height: 34px; border-radius: 50%;
  border: 3px solid rgba(255, 255, 255, .18); border-top-color: var(--c-primary);
  animation: spin .8s linear infinite;
}
@keyframes spin { to { transform: rotate(360deg); } }

.big-play {
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  width: 68px; height: 68px; border-radius: 50%; border: none; cursor: pointer; z-index: 8;
  background: rgba(251, 114, 153, .9); color: #fff; font-size: 26px;
  display: flex; align-items: center; justify-content: center;
  box-shadow: 0 8px 26px rgba(251, 114, 153, .45);
}
.buffering {
  position: absolute; left: 50%; bottom: 88px; transform: translateX(-50%);
  background: rgba(0, 0, 0, .6); padding: 4px 12px; border-radius: 999px; font-size: 12px; z-index: 8;
}

.controls {
  position: absolute; left: 0; right: 0; bottom: 0; z-index: 12;
  padding: 6px 14px 10px;
  background: linear-gradient(180deg, transparent, rgba(0, 0, 0, .78));
  transition: opacity .2s, transform .2s;
}
.controls.hide { opacity: 0; transform: translateY(8px); pointer-events: none; }
.progress-row { padding: 6px 0 2px; }
.progress { position: relative; height: 14px; cursor: pointer; display: flex; align-items: flex-end; }
.bar-bg {
  position: absolute; left: 0; right: 0; bottom: 4px; height: 5px;
  border-radius: 3px; background: rgba(255, 255, 255, .22);
}
.bar-played {
  position: absolute; left: 0; bottom: 4px; height: 5px; border-radius: 3px;
  background: var(--c-primary); z-index: 2;
}
.bar-dot {
  position: absolute; bottom: 1px; width: 11px; height: 11px; border-radius: 50%;
  background: #fff; transform: translateX(-50%); z-index: 3; box-shadow: 0 0 6px rgba(0, 0, 0, .5);
}
.heat {
  position: absolute; bottom: 5px; width: 2px; margin-left: -1px; z-index: 1;
  background: rgba(255, 255, 255, .35); border-radius: 1px;
}
.ctrl-row { display: flex; align-items: center; gap: 10px; height: 34px; color: #e9ecf3; }
.time { font-size: 12px; color: #c8ceda; font-variant-numeric: tabular-nums; }
.spacer { flex: 1; }
.txt-btn {
  width: 30px; height: 30px; border-radius: 6px; border: 1px solid rgba(255, 255, 255, .25);
  background: transparent; color: #fff; cursor: pointer; font-size: 12px;
}
.txt-btn.off { opacity: .45; text-decoration: line-through; }
.rate-select { width: 82px; }
.volume { display: flex; align-items: center; gap: 4px; }
.vol-range { width: 64px; }

.dm-bar {
  display: flex; align-items: center; gap: 8px;
  padding: 10px 16px; max-width: 1400px; margin: 0 auto;
  background: rgba(14, 17, 24, .96);
  border-top: 1px solid rgba(255, 255, 255, .06);
  flex-wrap: wrap;
}
.dm-status { font-size: 11.5px; color: #8b93a4; white-space: nowrap; }
.dm-status.open { color: #4ade80; }
.dm-status.connecting { color: #fbbf24; }
.dm-status.closed { color: #f87171; }
.dm-pos { width: 78px; }
.dm-input { flex: 1; min-width: 160px; }
.dm-online { font-size: 11.5px; white-space: nowrap; }

.ep-section { max-width: 1400px; margin: 18px auto 0; padding: 0 16px; }
.ep-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 10px; }
.ep-title { font-size: 15px; font-weight: 700; }
.ep-strip { display: flex; gap: 8px; flex-wrap: wrap; max-height: 148px; overflow-y: auto; }
.ep-chip {
  min-width: 46px; padding: 6px 10px; border-radius: 8px; cursor: pointer;
  border: 1px solid rgba(255, 255, 255, .12); background: rgba(255, 255, 255, .05); color: #cdd3df;
  font-size: 12.5px;
}
.ep-chip:hover { border-color: var(--c-primary); color: #fff; }
.ep-chip.active { background: var(--c-primary); border-color: var(--c-primary); color: #fff; font-weight: 700; }
.play-foot {
  max-width: 1400px; margin: 14px auto 0; padding: 0 16px;
  display: flex; gap: 18px; flex-wrap: wrap; font-size: 11.5px;
}

@media (max-width: 768px) {
  .topbar { padding: 8px 10px; }
  .src-select { width: 120px; }
  .player-wrap { aspect-ratio: 16 / 9; max-height: none; }
  .controls { padding: 4px 8px 8px; }
  .rate-select { width: 66px; }
  .volume .vol-range { display: none; }
  .dm-bar { padding: 8px 10px; }
  .dm-pos { width: 66px; }
  .dm-online { display: none; }
  .ep-strip { max-height: 120px; }
}
</style>
