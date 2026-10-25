<template>
  <div class="user-center">
    <header class="user-header">
      <a @click="goBack" class="back-link">← 返回</a>
      <span class="title">个人中心</span>
    </header>

    <div class="user-content" v-if="profile">
      <div class="user-card">
        <div class="avatar-lg">{{ profile.username?.charAt(0) }}</div>
        <h2>{{ profile.username }}</h2>
        <p class="bio">{{ profile.bio || '这个人很懒，什么都没写...' }}</p>
        <p class="email">{{ profile.email }}</p>
        <div class="follow-stats">
          <span>关注 {{ following.length }}</span>
          <span>粉丝 {{ followers.length }}</span>
        </div>
      </div>

      <!-- Watch History -->
      <div class="section">
        <h3>📺 观看历史</h3>
        <el-empty v-if="!history.length" description="暂无观看记录" />
        <div class="history-list" v-else>
          <div class="history-item" v-for="h in history" :key="h.id" @click="playHistory(h)">
            <div class="h-title">{{ h.title || h.animeKey }}</div>
            <div class="h-sub">第{{ h.episodeNum }}集</div>
            <span class="time">{{ formatTime(h.watchedAt) }}</span>
          </div>
        </div>
      </div>

      <!-- My Danmaku -->
      <div class="section">
        <h3>💬 我的弹幕</h3>
        <el-empty v-if="!myDanmaku.length" description="还没有发过弹幕" />
        <div class="dm-list" v-else>
          <div class="dm-item" v-for="d in myDanmaku" :key="d.id">
            <span class="dm-text">{{ d.text }}</span>
            <span class="dm-meta">{{ d.animeKey }} · 第{{ d.episodeNum }}集 · {{ formatTime(d.sendTime) }}</span>
          </div>
        </div>
      </div>
      <!-- Bangumi 镜像 / 魔法兜底 -->
      <div class="section">
        <h3>🔧 Bangumi 镜像 / 魔法兜底</h3>
        <div class="mirror-controls">
          <el-button size="small" @click="testMirrors" :loading="testing">测试镜像可用性</el-button>
          <div class="magic-switch">
            <span>魔法开关（走本地代理直连官方）</span>
            <el-switch v-model="magicOn" @change="toggleMagic" />
          </div>
        </div>
        <div class="mirror-list" v-if="mirrorResults.length">
          <div class="mirror-row" v-for="m in mirrorResults" :key="m.url">
            <span class="dot" :class="m.ok ? 'green' : 'red'"></span>
            <span class="m-url">{{ m.url }}</span>
            <span class="m-type">{{ m.type }}</span>
            <span class="m-ms">{{ m.ok ? m.ms + 'ms' : '不可用' }}</span>
          </div>
        </div>
      </div>

      <!-- AI 助手设置 -->
      <div class="section">
        <h3>🐾 桌宠 AI 设置</h3>
        <div class="ai-form">
          <div class="ai-row">
            <label>当前模型</label>
            <span class="ai-model">{{ aiForm.model || '-' }}</span>
          </div>
          <div class="ai-row">
            <label>我的 API Key</label>
            <el-input v-model="aiForm.apiKey" size="small" :placeholder="aiForm.hasKey ? '已设置（留空不改）' : '留空则用站点默认/免费兜底'" show-password />
          </div>
          <div class="ai-row">
            <label>我的桌宠人设</label>
            <el-input v-model="aiForm.persona" size="small" type="textarea" :rows="3" placeholder="留空用默认（爱弥斯·飞行雪绒）" />
          </div>
          <div class="ai-row">
            <label>代理端口</label>
            <el-input v-model.number="aiForm.proxyPort" size="small" style="width:140px" placeholder="如 7897" />
            <el-button size="small" @click="saveProxyPort">保存端口</el-button>
          </div>
          <el-button type="primary" size="small" @click="saveAiSettings">保存 AI 设置</el-button>
        </div>
      </div>
    </div>
    <el-empty v-else description="请先登录" />
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import api from '@/api'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()
const profile = ref<any>(null)
const following = ref<any[]>([])
const followers = ref<any[]>([])
const history = ref<any[]>([])
const myDanmaku = ref<any[]>([])

// Bangumi 镜像 / 魔法
const mirrorResults = ref<any[]>([])
const mirrorStatus = ref<any>({})
const magicOn = ref(false)
const testing = ref(false)

// AI 设置
const aiForm = ref<any>({ model: '', apiKey: '', persona: '', proxyPort: null, hasKey: false })
const loadAiSettings = async () => {
  try {
    const [sRes, mRes] = await Promise.all([
      api.get('/api/user/ai-settings'),
      api.get('/api/ai/model')
    ])
    const s = sRes.data.data || {}
    aiForm.value = { ...aiForm.value, persona: s.persona || '', proxyPort: s.proxyPort || null, hasKey: !!s.hasKey, model: mRes.data.data?.model || '' }
  } catch { /* ignore */ }
}
const saveAiSettings = async () => {
  try {
    await api.put('/api/user/ai-settings', {
      apiKey: aiForm.value.apiKey || null,
      persona: aiForm.value.persona || null,
      proxyPort: aiForm.value.proxyPort || null
    })
    aiForm.value.apiKey = ''
    ElMessage.success('已保存')
    loadAiSettings()
  } catch { ElMessage.error('保存失败') }
}
const saveProxyPort = async () => {
  if (!aiForm.value.proxyPort) { ElMessage.warning('请填端口号'); return }
  try {
    await api.post(`/api/datasource/mirror/proxy-port?port=${aiForm.value.proxyPort}`)
    ElMessage.success('代理端口已更新为 ' + aiForm.value.proxyPort)
  } catch { ElMessage.error('更新失败') }
}

const testMirrors = async () => {
  testing.value = true
  try {
    const res = await api.get('/api/datasource/mirror/test', { timeout: 60000 })
    mirrorResults.value = res.data.data || []
  } catch { mirrorResults.value = [] }
  testing.value = false
}
const toggleMagic = async () => {
  try {
    await api.post(`/api/datasource/mirror/magic?enabled=${magicOn.value}`)
    ElMessage.success(magicOn.value ? '魔法已开启（走本地代理）' : '魔法已关闭（走镜像）')
  } catch { ElMessage.error('切换失败') }
}
const loadMirrorStatus = async () => {
  try {
    const res = await api.get('/api/datasource/mirror/status')
    mirrorStatus.value = res.data.data || {}
    magicOn.value = !!mirrorStatus.value.proxyEnabled
  } catch { /* ignore */ }
}

const formatTime = (t: any) => {
  if (!t) return ''
  // sendTime 可能是数组 [y,m,d,h,mi,s] 或 ISO 字符串
  if (Array.isArray(t)) {
    const d = new Date(t[0], t[1] - 1, t[2], t[3] || 0, t[4] || 0, t[5] || 0)
    return d.toLocaleString('zh-CN')
  }
  return new Date(t).toLocaleString('zh-CN')
}

const goBack = () => { if (window.history.length > 1) router.back(); else router.push('/') }

const playHistory = (h: any) => {
  if (!h.animeKey || !h.episodeNum) return
  router.push({ path: `/play/${encodeURIComponent(h.animeKey)}/${h.episodeNum}`, query: { title: h.title || '' } })
}

onMounted(async () => {
  loadMirrorStatus()   // 镜像/魔法状态（无需登录）
  loadAiSettings()     // AI 设置
  if (!userStore.isLoggedIn()) return
  try {
    const [pRes, hRes] = await Promise.all([
      api.get('/api/user/profile'),
      api.get('/api/user/history?limit=30')
    ])
    profile.value = pRes.data.data
    history.value = hRes.data.data || []
  } catch { /* ignore */ }
  // 关注/粉丝（失败不影响）
  try {
    const [fRes, frRes] = await Promise.all([
      api.get('/api/user/following'),
      api.get('/api/user/followers')
    ])
    following.value = fRes.data.data || []
    followers.value = frRes.data.data || []
  } catch { /* ignore */ }
  // 我的弹幕
  try {
    const dmRes = await api.get('/api/danmaku/my', { params: { username: userStore.user.username } })
    myDanmaku.value = dmRes.data.data || []
  } catch { /* ignore */ }
})
</script>

<style scoped>
.user-header { background: white; padding: 14px 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.06); display: flex; align-items: center; gap: 14px; }
.back-link { color: var(--primary); cursor: pointer; }
.back-link:hover { text-decoration: underline; }
.title { font-weight: 600; }

.user-content { max-width: 800px; margin: 0 auto; padding: 30px 20px; }
.user-card { text-align: center; margin-bottom: 30px; }
.avatar-lg { width: 80px; height: 80px; border-radius: 50%; background: var(--primary); color: white; font-size: 32px; display: flex; align-items: center; justify-content: center; margin: 0 auto 16px; }
.bio { color: var(--text-secondary); }
.email { font-size: 13px; color: var(--text-secondary); margin-top: 4px; }
.follow-stats { margin-top: 12px; display: flex; justify-content: center; gap: 24px; font-size: 14px; color: var(--text-secondary); }

.section { margin-top: 30px; }
.section h3 { margin-bottom: 12px; }
.history-item { display: flex; align-items: center; gap: 12px; padding: 12px; background: white; border-radius: 8px; margin-bottom: 8px; cursor: pointer; transition: box-shadow .15s; }
.history-item:hover { box-shadow: 0 4px 12px rgba(0,0,0,.1); }
.h-title { font-weight: 500; }
.h-sub { color: var(--text-secondary); font-size: 13px; margin-left: auto; }
.time { font-size: 12px; color: var(--text-secondary); }
.dm-item { padding: 10px 12px; background: white; border-radius: 8px; margin-bottom: 8px; display: flex; justify-content: space-between; gap: 12px; }
.dm-text { font-weight: 500; }
.dm-meta { font-size: 12px; color: var(--text-secondary); white-space: nowrap; }

.mirror-controls { display: flex; align-items: center; gap: 20px; margin-bottom: 12px; flex-wrap: wrap; }
.magic-switch { display: flex; align-items: center; gap: 8px; font-size: 14px; }
.ai-form { background: #fff; border-radius: 8px; padding: 14px; }
.ai-row { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.ai-row label { width: 90px; font-size: 14px; color: #555; flex-shrink: 0; }
.ai-model { font-size: 13px; color: var(--primary); font-weight: 600; }
.magic-switch .hint { font-size: 12px; color: #e6a23c; }
.mirror-list { background: white; border-radius: 8px; padding: 8px 12px; }
.mirror-row { display: flex; align-items: center; gap: 10px; padding: 6px 0; font-size: 13px; border-bottom: 1px solid var(--border); }
.mirror-row:last-child { border-bottom: none; }
.mirror-row .m-url { flex: 1; }
.mirror-row .m-type { color: var(--text-secondary); font-size: 12px; }
.mirror-row .m-ms { color: var(--text-secondary); font-size: 12px; min-width: 60px; text-align: right; }
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; }
.dot.green { background: #67c23a; }
.dot.red { background: #f56c6c; }
</style>
