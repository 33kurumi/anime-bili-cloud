<template>
  <div class="play-page">
    <header class="play-header">
      <a @click="goHome" class="nav-btn">🏠 首页</a>
      <a @click="goBack" class="nav-btn">← 返回</a>
      <span class="ep-title">{{ animeTitle }} · 第{{ episodeNum }}集</span>
      <div class="source-switch" v-if="sources.length">
        <span class="label">线路：</span>
        <el-select v-model="currentRuleId" size="small" style="width: 230px"
                   @change="onSwitchSource" placeholder="自动择优">
          <el-option label="自动择优" :value="null" />
          <el-option v-for="s in sources" :key="s.id" :label="s.name" :value="s.id">
            <span class="dot" :class="healthClass(s.id)"></span>{{ s.name }}
          </el-option>
        </el-select>
        <span class="cur-src" v-if="curSourceName">{{ curSourceName }}</span>
      </div>
    </header>

    <div class="player-container">
      <div class="player-wrap">
        <div ref="playerRef" class="dplayer-wrapper"></div>
        <!-- 加载圈：解析中/切换中覆盖在播放器上 -->
        <div class="player-loading" v-if="loading">
          <el-icon class="is-loading spinner"><Loading /></el-icon>
          <span>{{ loadingText }}</span>
        </div>
      </div>
      <div class="play-msg" v-if="playMsg">{{ playMsg }}</div>
      <div class="play-debug" v-if="debugInfo">🔧 {{ debugInfo }}</div>
    </div>

    <!-- 选集条 -->
    <div class="episode-strip" v-if="episodes.length">
      <div class="strip-head">
        <span>选集 ({{ episodes.length }})</span>
      </div>
      <div class="ep-list">
        <span v-for="e in episodes" :key="e.episodeNum"
              :class="['ep-chip', { active: e.episodeNum === episodeNum }]"
              @click="goEpisode(e.episodeNum)"
              :title="e.title">
          {{ e.episodeNum }}
        </span>
      </div>
    </div>

    <!-- Danmaku Input -->
    <div class="danmaku-bar" v-if="userStore.isLoggedIn()">
      <el-input v-model="danmakuText" placeholder="发条弹幕吧~" @keyup.enter="sendDanmaku" maxlength="100" show-word-limit>
        <template #prepend>
          <el-color-picker v-model="danmakuColor" size="small" />
        </template>
        <template #append>
          <el-select v-model="danmakuPosition" style="width: 100px">
            <el-option label="滚动" :value="0" />
            <el-option label="顶部" :value="1" />
            <el-option label="底部" :value="2" />
          </el-select>
        </template>
      </el-input>
      <el-button type="primary" @click="sendDanmaku" style="margin-left: 8px;">发送</el-button>
    </div>
    <div class="danmaku-bar" v-else>
      <el-alert title="登录后才能发送弹幕" type="info" :closable="false" center />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '@/api'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'
import { Loading } from '@element-plus/icons-vue'
import DPlayer from 'dplayer'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const playerRef = ref<HTMLElement>()
const epTitle = ref('加载中...')
const danmakuText = ref('')
const danmakuColor = ref('#FFFFFF')
const danmakuPosition = ref(0)
const playMsg = ref('')
const debugInfo = ref('')

const sources = ref<any[]>([])
const currentRuleId = ref<number | null>(null)
const curSourceName = ref('')
const episodes = ref<any[]>([])

const animeKey = computed(() => String(route.params.animeKey || ''))
const episodeNum = computed(() => Number(route.params.episodeNum || 1))
const animeTitle = computed(() => String(route.query.title || '动画'))
const roomKey = computed(() => `${animeKey.value}:${episodeNum.value}`)

let dp: any = null
let ws: WebSocket | null = null

const goHome = () => router.push('/')
const goBack = () => { if (window.history.length > 1) router.back(); else router.push('/') }
// 切集：改路由参数（同路由仅参数变化，下方 watch 会重新加载）
const goEpisode = (n: number) => {
  if (n === episodeNum.value) return
  router.push({ path: `/play/${encodeURIComponent(animeKey.value)}/${n}`, query: { title: animeTitle.value } })
}

const connectWebSocket = () => {
  if (ws) { try { ws.close() } catch { /* ignore */ } }
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  ws = new WebSocket(`${protocol}//localhost:8085/ws/danmaku/${animeKey.value}/${episodeNum.value}`)
  ws.onmessage = (event) => {
    const danmaku = JSON.parse(event.data)
    if (dp) {
      dp.danmaku.draw({ text: danmaku.text, color: danmaku.color || '#FFFFFF', type: danmaku.position || 0, time: danmaku.timestamp ?? dp.video.currentTime })
    }
  }
  ws.onerror = () => console.warn('WebSocket connection failed, danmaku disabled')
}

const sendDanmaku = async () => {
  if (!danmakuText.value.trim()) return
  const data = {
    userId: userStore.user.userId ?? 1,
    username: userStore.user.username || '匿名',
    text: danmakuText.value.trim(),
    color: danmakuColor.value,
    position: danmakuPosition.value,
    fontSize: 18,
    timestamp: dp?.video?.currentTime || 0
  }
  try {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(data))
    ElMessage.success('弹幕发送成功')
    danmakuText.value = ''
  } catch {
    ElMessage.error('弹幕发送失败')
  }
}

const loadSources = async () => {
  try { sources.value = (await api.get('/api/source/sources')).data.data || [] } catch { sources.value = [] }
}

// 源健康：深度检测（搜索+分集）→ 红/橙/绿点
const health = ref<Record<number, string>>({})
const healthLoaded = ref(false)
const healthClass = (id: number) => {
  if (!healthLoaded.value) return 'gray'
  const s = health.value[id]
  if (s === 'ok') return 'green'
  if (s === 'search_only') return 'orange'
  if (s === 'dead') return 'red'
  return 'gray'
}
const loadHealth = async () => {
  if (!animeTitle.value) return
  try {
    const res = await api.get('/api/source/health', { params: { title: animeTitle.value }, timeout: 120000 })
    const map: Record<number, string> = {}
    ;(res.data.data || []).forEach((x: any) => { if (x.ruleId != null) map[x.ruleId] = x.status || (x.ok ? 'ok' : 'dead') })
    health.value = map
    healthLoaded.value = true
  } catch { /* ignore */ }
}

const loadEpisodes = async () => {
  try {
    const res = await api.get(`/api/anime/${encodeURIComponent(animeKey.value)}/episodes`)
    episodes.value = res.data.data || []
  } catch { episodes.value = [] }
}

// 请求序号：新请求发起时旧请求的响应自动作废，切换永不阻塞
let playSeq = 0
const loading = ref(false)
const loadingText = ref('解析播放源…')

const loadVideo = async (ruleId: number | null, isSwitch = false) => {
  const seq = ++playSeq
  loading.value = true
  loadingText.value = isSwitch ? '切换线路中…' : '解析播放源…'
  playMsg.value = ''
  try {
    const res = await api.get('/api/source/play', {
      params: { animeKey: animeKey.value, ep: episodeNum.value, title: animeTitle.value, ruleId: ruleId ?? undefined },
      timeout: 90000
    })
    if (seq !== playSeq) return          // 已被更新的请求取代，丢弃本次结果
    const info = res.data.data || {}
    console.log('[play] /api/source/play 返回：', info)
    debugInfo.value = `源=${info.sourceName || '-'} | 方式=${info.resolvedBy || '-'} | 格式=${info.format || '-'} | 匹配=${info.matchedTitle || '-'}(${info.matchScore != null ? Number(info.matchScore).toFixed(2) : '-'})`
    const videoUrl = info.url || info.proxyUrl || ''
    const videoType = info.format === 'm3u8' ? 'hls' : 'auto'
    curSourceName.value = info.sourceName || ''
    if (!videoUrl) { playMsg.value = info.message || '未找到可播放的资源'; return }
    console.log('[play] 播放地址：', videoUrl, '类型：', videoType, '| window.Hls?', !!(window as any).Hls)
    if (dp) {
      dp.switchVideo({ url: videoUrl, type: videoType })
    } else {
      dp = new DPlayer({
        container: playerRef.value!,
        autoplay: false, theme: '#fb7299', lang: 'zh-cn', screenshot: true, hotkey: true, preload: 'auto',
        video: { url: videoUrl, type: videoType },
        danmaku: { id: roomKey.value, api: '', addition: [] }
      })
      dp.on('error', (e: any) => {
        console.warn('[play] DPlayer error', e)
        // 不立即判死刑：HLS 首次加载有瞬时报错、随后自愈，交给 playing 事件清除
        const detail = e?.message || e?.type || (e?.error && (e.error.message || e.error.type)) || JSON.stringify(e)
        playMsg.value = '播放器错误：' + detail
      })
      // 真正播起来 → 清除任何瞬时报错
      dp.on('playing', () => { playMsg.value = '' })
      dp.on('canplay', () => { playMsg.value = '' })
    }
    // 记住本次成功的源，切集时直接复用（像 Animeko 的"记住浏览"），避免每次重搜全部源
    if (info.ruleId) {
      try { localStorage.setItem('abc_src_' + animeKey.value, String(info.ruleId)) } catch { /* ignore */ }
    }
  } catch (e: any) {
    if (seq !== playSeq) return
    playMsg.value = '解析播放地址失败：' + (e?.message || '')
    console.error('[play] 请求失败', e)
  } finally {
    if (seq === playSeq) loading.value = false   // 只有最新请求才清除加载态
  }
}

const onSwitchSource = () => {
  // 不阻塞 UI：发起切换后立刻可再次切换（旧响应由请求序号丢弃）
  loadVideo(currentRuleId.value, true)
}

const loadDanmaku = async () => {
  try {
    const dmRes = await api.get('/api/danmaku/list', {
      params: { animeKey: animeKey.value, ep: episodeNum.value, title: animeTitle.value }
    })
    const list = (dmRes.data.data || []).map((d: any) => ({ text: d.text, color: d.color, type: d.position, time: d.timestamp }))
    dp?.danmaku?.clear()
    list.forEach((d: any) => dp?.danmaku?.draw(d))
  } catch { /* ignore */ }
}

// 记录观看历史（登录用户）
const recordHistory = async () => {
  if (!userStore.isLoggedIn()) return
  try {
    await api.post('/api/user/history', {
      animeKey: animeKey.value,
      episodeNum: episodeNum.value,
      title: animeTitle.value,
      progress: Math.floor(dp?.video?.currentTime || 0)
    })
  } catch { /* ignore */ }
}

// 重新加载当前集（切集或首次）
const loadCurrent = async () => {
  if (dp) { try { dp.destroy() } catch { /* ignore */ } dp = null }
  epTitle.value = `第${episodeNum.value}集`
  await nextTick()
  await loadVideo(currentRuleId.value)   // 切集时保留当前源选择
  await loadDanmaku()
  connectWebSocket()
  recordHistory()                        // 记录观看历史
}

onMounted(async () => {
  await loadSources()
  await loadEpisodes()
  // 首次进入也用记住的源（快）
  const remembered = localStorage.getItem('abc_src_' + animeKey.value)
  if (remembered) currentRuleId.value = Number(remembered)
  await loadCurrent()
  loadHealth()   // 后台检测各源（红绿点）
})

// 路由参数变化（切集）→ 重新加载
watch(() => route.params.episodeNum, async (n, o) => {
  if (n !== o) {
    // 切集：优先用上次记住的源（快，单源解析；像 Animeko 的"记住浏览"），没有则自动择优
    const remembered = localStorage.getItem('abc_src_' + animeKey.value)
    currentRuleId.value = remembered ? Number(remembered) : null
    await loadCurrent()
  }
})

onUnmounted(() => {
  if (ws) ws.close()
  if (dp) dp.destroy()
})
</script>

<style scoped>
.play-header { background: white; padding: 14px 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.06); display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.nav-btn { color: var(--primary); cursor: pointer; white-space: nowrap; }
.nav-btn:hover { text-decoration: underline; }
.ep-title { font-weight: 600; }
.source-switch { display: flex; align-items: center; gap: 8px; margin-left: auto; }
.source-switch .label { font-size: 13px; color: var(--text-secondary); }
.cur-src { font-size: 12px; color: var(--text-secondary); }
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; margin-right: 6px; vertical-align: middle; }
.dot.green { background: #67c23a; }
.dot.orange { background: #e6a23c; }
.dot.red { background: #f56c6c; }
.dot.gray { background: #c0c4cc; }

.player-container { max-width: 1100px; margin: 30px auto 0; padding: 0 20px; }
.player-wrap { position: relative; }
.dplayer-wrapper { aspect-ratio: 16/9; background: #000; border-radius: 12px; overflow: hidden; }
.player-loading { position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 10px; background: rgba(0,0,0,0.55); color: #fff; border-radius: 12px; z-index: 5; }
.player-loading .spinner { font-size: 34px; }
.play-msg { margin-top: 12px; color: #f56c6c; font-size: 14px; text-align: center; }
.play-debug { margin-top: 8px; color: #999; font-size: 12px; text-align: center; }

.episode-strip { max-width: 1100px; margin: 20px auto 0; padding: 0 20px; }
.strip-head { font-weight: 600; margin-bottom: 10px; }
.ep-list { display: flex; flex-wrap: wrap; gap: 8px; max-height: 160px; overflow-y: auto; }
.ep-chip { display: inline-flex; align-items: center; justify-content: center; min-width: 40px; height: 32px; padding: 0 10px; border: 1px solid var(--border); border-radius: 6px; cursor: pointer; font-size: 13px; background: #fff; transition: all .15s; }
.ep-chip:hover { border-color: var(--primary); color: var(--primary); }
.ep-chip.active { background: var(--primary); color: #fff; border-color: var(--primary); }

.danmaku-bar { max-width: 1100px; margin: 20px auto; padding: 0 20px; display: flex; align-items: center; }
</style>
