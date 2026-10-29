<template>
  <div class="uc-page page">
    <!-- 资料卡 -->
    <section class="profile abc-card">
      <div class="avatar">
        <img v-if="profile?.avatar" :src="profile.avatar" alt="头像" @error="onAvatarError" />
        <span v-else>{{ initial }}</span>
      </div>
      <div class="info">
        <h2 class="name">{{ profile?.username || userStore.displayName }}</h2>
        <p class="weak bio">{{ profile?.bio || '这个人很懒，还没有写简介' }}</p>
        <p v-if="profile?.email" class="weak email">{{ profile.email }}</p>
        <div class="stats">
          <span class="stat"><b>{{ followingCount }}</b> 关注</span>
          <span class="stat"><b>{{ followerCount }}</b> 粉丝</span>
          <span class="stat"><b>{{ history.length }}</b> 观看</span>
          <span class="stat"><b>{{ favorites.length }}</b> 追番</span>
        </div>
      </div>
      <div class="profile-actions">
        <el-button :icon="Edit" @click="openEditProfile">编辑资料</el-button>
        <el-button :icon="Star" @click="router.push('/favorites')">我的追番</el-button>
        <el-button v-if="userStore.isAdmin()" :icon="Setting" @click="router.push('/admin')">管理后台</el-button>
      </div>
    </section>

    <el-tabs v-model="tab" class="uc-tabs">
      <!-- 观看历史：支持续播 -->
      <el-tab-pane label="观看历史" name="history">
        <div v-if="!history.length" class="empty-block">还没有观看记录，去<router-link to="/">首页</router-link>逛逛吧</div>
        <div v-else class="hist-list">
          <div v-for="h in history" :key="String(h.id || h.animeKey + '-' + h.episodeNum)" class="hist-item" @click="playHistory(h)">
            <img v-if="h.coverUrl" :src="h.coverUrl" class="hist-cover" loading="lazy" @error="onAvatarError" />
            <div v-else class="hist-cover cover-ph"></div>
            <div class="hist-info">
              <p class="hist-title ellipsis">{{ h.title || h.animeKey }}</p>
              <p class="weak">第 {{ h.episodeNum }} 话 · 看到 {{ fmt(Number(h.progress || 0)) }}</p>
              <el-progress :percentage="progressPercent(h)" :stroke-width="4" :show-text="false" />
            </div>
            <span class="weak hist-time">{{ shortTime(h.watchedAt) }}</span>
          </div>
        </div>
      </el-tab-pane>

      <!-- 我的收藏 / 追番 -->
      <el-tab-pane label="我的追番" name="favorites">
        <div v-if="!favorites.length" class="empty-block">还没有追番，去<router-link to="/bangumi">番剧库</router-link>收藏吧</div>
        <div v-else class="fav-grid">
          <AnimeCard v-for="f in favorites" :key="f.animeKey" :anime="toAnime(f)" />
        </div>
      </el-tab-pane>

      <!-- 我的弹幕 -->
      <el-tab-pane label="我的弹幕" name="danmaku">
        <div v-if="!myDanmaku.length" class="empty-block">还没有发过弹幕</div>
        <div v-else class="dm-list">
          <div v-for="d in myDanmaku" :key="String(d.id || d.text + d.timestamp)" class="dm-item">
            <span class="dm-time weak">{{ fmt(Number(d.timestamp)) }}</span>
            <span class="dm-text" :style="{ color: d.color || '#fff' }">{{ d.text }}</span>
            <span class="weak">{{ d.animeKey || '' }} {{ d.episodeNum ? '第' + d.episodeNum + '话' : '' }}</span>
          </div>
        </div>
      </el-tab-pane>

      <!-- 我的评分 -->
      <el-tab-pane label="我的评分" name="ratings">
        <div v-if="!myRatings.length" class="empty-block">还没有评分</div>
        <div v-else class="rating-list">
          <div v-for="r in myRatings" :key="String(r.id || r.animeId)" class="rating-item">
            <span class="weak">番剧 {{ r.animeId }}</span>
            <el-rate :model-value="Number(r.score) / 2" disabled allow-half />
            <span class="score-num">{{ r.score }} 分</span>
          </div>
        </div>
      </el-tab-pane>

      <!-- 设置：AI 助手 / Bangumi 镜像 -->
      <el-tab-pane label="设置" name="settings">
        <div class="set-block">
          <h3 class="set-title">AI 助手设置</h3>
          <el-form label-width="110px" style="max-width: 560px">
            <el-form-item label="当前模型">
              <el-tag>{{ aiModel || '未配置' }}</el-tag>
            </el-form-item>
            <el-form-item label="模型名称">
              <el-input v-model="aiSettings.model" placeholder="如 deepseek-chat" />
            </el-form-item>
            <el-form-item label="接口地址">
              <el-input v-model="aiSettings.apiUrl" placeholder="https://api.deepseek.com/v1" />
            </el-form-item>
            <el-form-item label="系统提示词">
              <el-input v-model="aiSettings.systemPrompt" type="textarea" :rows="3" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="aiSaving" @click="saveAiSettings">保存</el-button>
            </el-form-item>
          </el-form>
        </div>

        <div class="set-block">
          <h3 class="set-title">Bangumi 镜像（运维能力）</h3>
          <p class="weak">当官方 API 被墙时，可切换镜像代理获取番剧数据。</p>
          <div class="mirror-row">
            <el-tag :type="mirror.ok ? 'success' : 'info'">{{ mirror.ok ? '镜像可用' : '镜像未启用' }}</el-tag>
            <span class="weak">{{ mirror.detail || '点击测试查看当前镜像状态' }}</span>
            <el-button size="small" :loading="mirrorTesting" @click="testMirror">测试镜像</el-button>
            <el-button size="small" :disabled="!mirror.publicUrl" @click="copyText(mirror.publicUrl)">复制镜像地址</el-button>
          </div>
          <pre v-if="mirror.port" class="mirror-json">{{ mirror.port }}</pre>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 编辑资料 -->
    <el-dialog v-model="editVisible" title="编辑资料" width="480px">
      <el-form :model="editForm" label-width="80px">
        <el-form-item label="昵称"><el-input v-model="editForm.username" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="editForm.email" /></el-form-item>
        <el-form-item label="头像URL"><el-input v-model="editForm.avatar" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="editForm.bio" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editSaving" @click="saveProfile">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 个人中心。
 * 旧版：只有数字形式的关注/粉丝、历史、我的弹幕，外加运维性质的镜像与 AI 设置，
 * 且写死的 userId/username 满天飞；没有追番入口、没有编辑资料、没有我的评分。
 * 新版：资料卡（可编辑）+ 历史续播 + 追番 + 我的弹幕 + 我的评分 + 设置。
 * 后端：GET/PUT /api/user/profile、GET /api/user/following|followers、GET /api/user/history、
 *       GET /api/user/favorite、GET /api/danmaku/my、GET /api/rating/user。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Edit, Setting, Star } from '@element-plus/icons-vue'
import { get, post, put } from '@/api'
import { useUserStore } from '@/stores/user'
import AnimeCard from '@/components/AnimeCard.vue'
import type { AnimeItem, DanmakuItem, FavoriteItem, WatchHistoryItem } from '@/types'

interface ProfileShape {
  id?: number
  username?: string
  nickname?: string
  email?: string
  avatar?: string
  bio?: string
  role?: string
}
interface RatingRow { id?: number; animeId?: number | string; score?: number; createdAt?: string }

const router = useRouter()
const userStore = useUserStore()

const tab = ref('history')
const profile = ref<ProfileShape | null>(null)
const followingCount = ref(0)
const followerCount = ref(0)
const history = ref<WatchHistoryItem[]>([])
const favorites = ref<FavoriteItem[]>([])
const myDanmaku = ref<Array<DanmakuItem & { animeKey?: string; episodeNum?: number }>>([])
const myRatings = ref<RatingRow[]>([])

const editVisible = ref(false)
const editSaving = ref(false)
const editForm = reactive({ username: '', email: '', avatar: '', bio: '' })

// AI 设置
const aiModel = ref('')
const aiSaving = ref(false)
const aiSettings = reactive({ model: '', apiUrl: '', systemPrompt: '' })

// Bangumi 镜像
const mirror = reactive({ ok: false, detail: '', publicUrl: '', port: '' })
const mirrorTesting = ref(false)

const initial = computed(() => {
  const name = profile.value?.username || profile.value?.nickname || userStore.displayName || '?'
  return String(name).charAt(0).toUpperCase()
})

onMounted(async () => {
  await Promise.all([loadProfile(), loadHistory(), loadFavorites(), loadMyDanmaku(), loadMyRatings(), loadAiSettings()])
  loadMirror()
})

async function loadProfile(): Promise<void> {
  try {
    profile.value = await get<ProfileShape>('/api/user/profile', undefined, { silent: true })
    syncEditForm()
  } catch {
    profile.value = null
  }
  // 关注/粉丝：接口返回列表，用长度计数（旧版只有硬编码数字）
  try {
    const [following, followers] = await Promise.all([
      get<unknown[]>('/api/user/following', undefined, { silent: true }),
      get<unknown[]>('/api/user/followers', undefined, { silent: true })
    ])
    followingCount.value = (following || []).length
    followerCount.value = (followers || []).length
  } catch {
    followingCount.value = 0
    followerCount.value = 0
  }
}

function syncEditForm(): void {
  editForm.username = profile.value?.username || profile.value?.nickname || ''
  editForm.email = profile.value?.email || ''
  editForm.avatar = profile.value?.avatar || ''
  editForm.bio = profile.value?.bio || ''
}

async function loadHistory(): Promise<void> {
  try {
    history.value = await get<WatchHistoryItem[]>('/api/user/history', { limit: 30 }, { silent: true })
  } catch {
    history.value = []
  }
}

async function loadFavorites(): Promise<void> {
  try {
    favorites.value = await get<FavoriteItem[]>('/api/user/favorite', undefined, { silent: true })
  } catch {
    favorites.value = []
  }
}

async function loadMyDanmaku(): Promise<void> {
  const username = profile.value?.username || userStore.displayName
  if (!username) return
  try {
    myDanmaku.value = await get('/api/danmaku/my', { username }, { silent: true })
  } catch {
    myDanmaku.value = []
  }
}

async function loadMyRatings(): Promise<void> {
  try {
    myRatings.value = await get<RatingRow[]>('/api/rating/user', undefined, { silent: true })
  } catch {
    myRatings.value = []
  }
}

function openEditProfile(): void {
  syncEditForm()
  editVisible.value = true
}

async function saveProfile(): Promise<void> {
  editSaving.value = true
  try {
    await put('/api/user/profile', { ...editForm })
    ElMessage.success('资料已更新')
    editVisible.value = false
    await loadProfile()
  } catch {
    /* 拦截器已提示 */
  }
  editSaving.value = false
}

function onAvatarError(e: Event): void {
  const img = e.target as HTMLImageElement
  img.style.visibility = 'hidden'
}

function playHistory(h: WatchHistoryItem): void {
  const key = String(h.animeKey || '')
  const path = /^\d+$/.test(key) ? '/anime/' + key : '/anime/bgm:' + key.replace(/^bgm:/, '')
  router.push(path)
}

function toAnime(f: FavoriteItem): AnimeItem {
  return { title: f.title || f.animeKey, coverUrl: f.coverUrl, id: f.animeKey }
}

function progressPercent(h: WatchHistoryItem): number {
  const total = Number(h.durationSec || 0)
  const cur = Number(h.progress || 0)
  if (!total) return 0
  return Math.min(100, Math.round((cur / total) * 100))
}

function fmt(sec: number): string {
  if (!sec || !Number.isFinite(sec)) return '00:00'
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return String(m).padStart(2, '0') + ':' + String(s).padStart(2, '0')
}

function shortTime(value?: string): string {
  if (!value) return ''
  return String(value).replace('T', ' ').slice(5, 16)
}

async function loadAiSettings(): Promise<void> {
  try {
    const [settings, model] = await Promise.all([
      get<{ model?: string; apiUrl?: string; systemPrompt?: string }>('/api/user/ai-settings', undefined, { silent: true }),
      get<{ model?: string }>('/api/ai/model', undefined, { silent: true })
    ])
    aiSettings.model = settings?.model || ''
    aiSettings.apiUrl = settings?.apiUrl || ''
    aiSettings.systemPrompt = settings?.systemPrompt || ''
    aiModel.value = model?.model || settings?.model || ''
  } catch {
    aiModel.value = ''
  }
}

async function saveAiSettings(): Promise<void> {
  aiSaving.value = true
  try {
    await put('/api/user/ai-settings', { ...aiSettings })
    ElMessage.success('AI 设置已保存')
    aiModel.value = aiSettings.model
  } catch {
    /* 拦截器已提示 */
  }
  aiSaving.value = false
}

async function loadMirror(): Promise<void> {
  try {
    const data = await get<Record<string, unknown>>('/api/datasource/mirror/status', undefined, { silent: true })
    const obj = data || {}
    mirror.ok = Boolean(obj.enabled ?? obj.ok ?? obj.mirrorEnabled)
    mirror.detail = String(obj.message || obj.current || obj.activeMirror || '')
    mirror.publicUrl = String(obj.publicUrl || obj.url || '')
  } catch {
    mirror.ok = false
    mirror.detail = '镜像状态接口不可用'
  }
}

async function testMirror(): Promise<void> {
  mirrorTesting.value = true
  try {
    const data = await post<Record<string, unknown>>('/api/datasource/mirror/test', {}, { silent: true })
    mirror.ok = Boolean(data?.ok ?? data?.success)
    mirror.detail = String(data?.message || data?.detail || JSON.stringify(data))
    ElMessage.success(mirror.ok ? '镜像可用' : '镜像不可用')
  } catch {
    ElMessage.error('镜像测试失败')
  }
  mirrorTesting.value = false
}

async function copyText(text: string): Promise<void> {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    ElMessage.warning('复制失败，请手动选择')
  }
}
</script>

<style scoped>
.uc-page { padding-top: 20px; }
.profile { display: flex; gap: 20px; align-items: center; padding: 22px; }
.avatar {
  width: 82px; height: 82px; border-radius: 50%; overflow: hidden; flex: none;
  background: var(--c-gradient); color: #fff; font-size: 32px; font-weight: 700;
  display: flex; align-items: center; justify-content: center;
}
.avatar img { width: 100%; height: 100%; object-fit: cover; }
.info { flex: 1; min-width: 0; }
.name { font-size: 20px; margin-bottom: 4px; }
.bio { font-size: 13px; margin-bottom: 2px; }
.email { font-size: 12px; }
.stats { display: flex; gap: 18px; margin-top: 10px; font-size: 12.5px; color: var(--c-text-dim); }
.stat b { color: var(--c-text); font-size: 15px; margin-right: 3px; }
.profile-actions { display: flex; flex-direction: column; gap: 8px; }
.uc-tabs { margin-top: 16px; }

.hist-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; }
.hist-item {
  display: flex; gap: 10px; padding: 10px; border-radius: var(--radius); cursor: pointer;
  background: var(--c-card); border: 1px solid var(--c-border);
}
.hist-item:hover { border-color: var(--c-primary); }
.hist-cover { width: 56px; height: 76px; object-fit: cover; border-radius: 6px; flex: none; }
.cover-ph { background: var(--c-bg-soft); }
.hist-info { flex: 1; min-width: 0; }
.hist-title { font-size: 13.5px; font-weight: 600; margin-bottom: 4px; }
.hist-info :deep(.el-progress__text) { display: none; }
.hist-time { font-size: 11px; align-self: flex-start; }

.fav-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(178px, 1fr)); gap: 16px; }
.dm-list { display: flex; flex-direction: column; gap: 8px; }
.dm-item {
  display: flex; gap: 12px; align-items: center; padding: 8px 12px;
  background: var(--c-card); border-radius: 8px; font-size: 13px;
}
.dm-time { font-variant-numeric: tabular-nums; }
.rating-list { display: flex; flex-direction: column; gap: 10px; }
.rating-item {
  display: flex; gap: 14px; align-items: center; padding: 10px 14px;
  background: var(--c-card); border-radius: 8px; font-size: 13px;
}
.score-num { color: var(--c-primary); font-weight: 700; }

.set-block { margin-bottom: 26px; }
.set-title { font-size: 15px; font-weight: 700; margin-bottom: 10px; }
.mirror-row { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-top: 10px; }
.mirror-json { margin-top: 10px; font-size: 12px; background: var(--c-bg-soft); padding: 10px; border-radius: 8px; }

@media (max-width: 768px) {
  .profile { flex-wrap: wrap; gap: 12px; padding: 16px; }
  .profile-actions { flex-direction: row; width: 100%; }
  .stats { gap: 12px; flex-wrap: wrap; }
  .hist-list { grid-template-columns: 1fr; }
}
</style>
