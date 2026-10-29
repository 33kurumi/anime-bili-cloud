<template>
  <div class="page detail-page">
    <el-button text class="back" @click="goBack">
      <el-icon><ArrowLeft /></el-icon> 返回
    </el-button>

    <!-- 骨架屏：详情接口慢（远程 Bangumi 可能 2-5s），旧版只有一个 v-loading 白屏 -->
    <div v-if="loading" class="skeleton-block">
      <div class="skeleton sk-cover"></div>
      <div class="sk-right">
        <div class="skeleton sk-title"></div>
        <div class="skeleton sk-tag"></div>
        <div class="skeleton sk-desc"></div>
      </div>
    </div>

    <template v-else-if="detail">
      <!-- ================= 信息区 ================= -->
      <section class="hero">
        <div class="hero-cover">
          <img v-if="detail.coverUrl" :src="detail.coverUrl" :alt="detail.title" />
          <div v-else class="cover-ph">番</div>
        </div>
        <div class="hero-info">
          <h1 class="title">{{ detail.title }}</h1>
          <div class="tags">
            <el-tag v-if="detail.type" size="small" effect="dark">{{ detail.type }}</el-tag>
            <el-tag v-if="detail.year" size="small" type="info">{{ detail.year }}</el-tag>
            <el-tag v-if="detail.season" size="small" type="info">{{ detail.season }}</el-tag>
            <el-tag v-if="detail.status" size="small" type="success">{{ detail.status }}</el-tag>
            <el-tag v-if="isRemote" size="small" type="warning" effect="plain">Bangumi 远程数据</el-tag>
          </div>
          <div class="stats">
            <div class="stat">
              <span class="stat-num">{{ displayScore }}</span>
              <span class="weak">Bangumi 评分</span>
            </div>
            <div class="stat">
              <span class="stat-num">{{ ratingStat?.count ?? 0 }}</span>
              <span class="weak">本站评分人数</span>
            </div>
            <div class="stat">
              <span class="stat-num">{{ episodeList.length }}</span>
              <span class="weak">剧集</span>
            </div>
          </div>
          <p class="desc clamp-2" :title="detail.description">{{ detail.description || '暂无简介' }}</p>

          <div class="actions">
            <el-button type="primary" size="large" round :disabled="!episodeList.length" @click="play(episodeList[0]?.episodeNum ?? 1)">
              <el-icon><VideoPlay /></el-icon> 立即播放
            </el-button>
            <el-button size="large" round :loading="favLoading" @click="toggleFavorite">
              <el-icon><Star /></el-icon>
              {{ favorited ? '已追番' : '追番' }}
            </el-button>
            <el-button size="large" round @click="share">
              <el-icon><Share /></el-icon> 分享
            </el-button>
          </div>
        </div>
      </section>

      <!-- ================= 剧集网格 ================= -->
      <section class="section">
        <div class="section-head">
          <h2 class="section-title">选集</h2>
          <span class="weak">共 {{ episodeList.length }} 话</span>
        </div>
        <div v-if="episodeList.length" class="ep-grid">
          <button
            v-for="ep in episodeList"
            :key="ep.episodeNum"
            class="ep-btn"
            :title="ep.title || ('第 ' + ep.episodeNum + ' 话')"
            @click="play(ep.episodeNum)"
          >
            <span class="ep-num">{{ String(ep.episodeNum).padStart(2, '0') }}</span>
            <span class="ep-title ellipsis">{{ ep.title || ('第 ' + ep.episodeNum + ' 话') }}</span>
          </button>
        </div>
        <div v-else class="empty-block">暂无剧集数据（该番剧可能还没有可播放线路）</div>
      </section>

      <!-- ================= 评分 ================= -->
      <section class="section">
        <div class="section-head">
          <h2 class="section-title">评分</h2>
        </div>
        <div class="rate-box">
          <div class="rate-left">
            <span class="rate-big">{{ siteScore }}</span>
            <span class="weak">本站平均分（{{ ratingStat?.count ?? 0 }} 人）</span>
            <el-rate v-model="myScore" :max="10" :disabled="!userStore.isLoggedIn() || ratingLoading" @change="submitRating" />
            <span v-if="!userStore.isLoggedIn()" class="weak">
              登录后可评分，<el-link type="primary" @click="toLogin">去登录</el-link>
            </span>
          </div>
          <div v-if="distribution.length" class="rate-right">
            <div v-for="d in distribution" :key="d.score" class="dist-row">
              <span class="dist-score">{{ d.score }}</span>
              <div class="dist-bar"><div class="dist-fill" :style="{ width: distPercent(d.cnt) + '%' }"></div></div>
              <span class="weak dist-cnt">{{ d.cnt }}</span>
            </div>
          </div>
        </div>
      </section>

      <!-- ================= 本站评论 ================= -->
      <section class="section">
        <div class="section-head">
          <h2 class="section-title">本站评论</h2>
          <span class="weak">{{ comments.length }} 条</span>
        </div>

        <div class="comment-editor">
          <el-input
            v-model="commentText"
            type="textarea"
            :rows="3"
            maxlength="500"
            show-word-limit
            :placeholder="userStore.isLoggedIn() ? '说点什么……' : '登录后参与评论'"
            :disabled="!userStore.isLoggedIn()"
          />
          <div class="editor-foot">
            <el-button v-if="!userStore.isLoggedIn()" text type="primary" @click="toLogin">登录</el-button>
            <el-button type="primary" size="small" :loading="posting" :disabled="!commentText.trim() || !userStore.isLoggedIn()" @click="postComment">
              发表评论
            </el-button>
          </div>
        </div>

        <div v-if="comments.length" class="comment-list">
          <div v-for="c in comments" :key="c.id" class="comment-item">
            <div class="avatar">{{ (c.username || '?').slice(0, 1) }}</div>
            <div class="comment-main">
              <div class="comment-head">
                <span class="comment-user">{{ c.username || '匿名' }}</span>
                <span class="weak comment-time">{{ formatTime(c.createdAt) }}</span>
              </div>
              <div class="comment-text">{{ c.content }}</div>
              <div class="comment-ops">
                <el-button text size="small" @click="likeComment(c)">
                  <el-icon><Pointer /></el-icon> {{ c.likeCount || 0 }}
                </el-button>
              </div>
            </div>
          </div>
          <div class="comment-more">
            <el-button v-if="hasMoreComments" size="small" round :loading="loadingMoreComments" @click="loadComments(true)">加载更多</el-button>
          </div>
        </div>
        <el-empty v-else description="还没有评论，来抢沙发" description-size="small" />
      </section>

      <!-- ================= Bangumi 吐槽 ================= -->
      <section class="section">
        <div class="section-head">
          <h2 class="section-title">Bangumi 吐槽</h2>
          <span class="weak">来自 bgm.tv 的真实评论</span>
        </div>
        <div v-if="bgmComments.length" class="bgm-list">
          <div v-for="(b, i) in bgmComments" :key="String(b.id ?? i)" class="bgm-item">
            <img v-if="b.avatar" :src="b.avatar" class="bgm-avatar" alt="" loading="lazy" />
            <div class="bgm-main">
              <div class="comment-head">
                <span class="comment-user">{{ b.username }}</span>
                <span v-if="b.rate" class="weak">评分 {{ b.rate }}</span>
                <span class="weak comment-time">{{ formatTime(b.updatedAt) }}</span>
              </div>
              <div class="comment-text">{{ b.content }}</div>
            </div>
          </div>
          <div class="comment-more">
            <el-button v-if="bgmHasMore" size="small" round :loading="loadingMoreBgm" @click="loadBgmComments(false)">加载更多</el-button>
          </div>
        </div>
        <el-empty v-else description="暂无 Bangumi 吐槽" description-size="small" />
      </section>
    </template>

    <el-empty v-else description="番剧不存在或数据源不可用">
      <el-button type="primary" @click="router.push('/bangumi')">去番剧库</el-button>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
/**
 * 番剧详情页 —— 两段式布局（信息区 + 剧集网格 + 评分 + 评论 + Bangumi 吐槽）。
 *
 * 【相对旧版的修复】
 *  1. 评分/评论不再伪造身份：旧版写死 userId:1 / username:'当前用户'，
 *     现在请求体只带业务字段，身份由网关 JWT 注入 X-User-Id（后端已改），前端不再传 userId。
 *  2. 评分统计真实读取 /api/rating/anime/{id}（旧版没有"我的评分"和平均分展示）。
 *  3. 新增追番按钮（旧版详情页连入口都没有），走 /api/user/favorite。
 *  4. 匿名可浏览：未登录只读，点写操作才引导登录（旧版整页被 401 直接踢到登录页）。
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { ArrowLeft, Pointer, Share, Star, VideoPlay } from '@element-plus/icons-vue'
import { get, post, del } from '@/api'
import { useUserStore } from '@/stores/user'
import type { AnimeItem, BgmCommentItem, CommentItem, EpisodeItem, FavoriteItem, RatingStat } from '@/types'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const detail = ref<AnimeItem | null>(null)
const episodeList = ref<EpisodeItem[]>([])
const ratingStat = ref<RatingStat | null>(null)
const comments = ref<CommentItem[]>([])
const bgmComments = ref<BgmCommentItem[]>([])
const favorited = ref(false)
const myScore = ref(0)

const loading = ref(true)
const favLoading = ref(false)
const ratingLoading = ref(false)
const posting = ref(false)
const loadingMoreComments = ref(false)
const loadingMoreBgm = ref(false)
const hasMoreComments = ref(false)
const bgmHasMore = ref(false)
const commentText = ref('')

const commentPage = ref(1)
const bgmOffset = ref(0)

/** 路由里的番剧标识，如 "12" 或 "bgm:400602"（可能被 encodeURIComponent 编码过） */
const animeKey = computed(() => {
  const raw = String(route.params.id ?? '')
  return decodeURIComponent(raw)
})
const isRemote = computed(() => String(detail.value?._source) === 'bangumi' || /^bgm:/.test(animeKey.value))
const displayScore = computed(() => {
  const v = Number(detail.value?.ratingAvg ?? detail.value?.rating_avg ?? 0)
  return v > 0 ? v.toFixed(1) : '暂无'
})
const siteScore = computed(() => {
  const s = ratingStat.value
  if (!s) return '暂无'
  if (s.average) return s.average
  if (typeof s.avg === 'number' && s.avg > 0) return s.avg.toFixed(1)
  return '暂无'
})
const distribution = computed(() => [...(ratingStat.value?.distribution || [])].sort((a, b) => b.score - a.score))
const maxDist = computed(() => Math.max(1, ...distribution.value.map(d => d.cnt)))

onMounted(loadAll)
watch(animeKey, () => loadAll())

async function loadAll(): Promise<void> {
  loading.value = true
  comments.value = []
  bgmComments.value = []
  myScore.value = 0
  commentPage.value = 1
  bgmOffset.value = 0
  try {
    detail.value = await get<AnimeItem>('/api/anime/' + encodeURIComponent(animeKey.value), undefined, { silent: true })
  } catch {
    detail.value = null
  }
  loading.value = false
  if (!detail.value) return
  loadEpisodes()
  loadRatingStat()
  loadComments(false)
  loadBgmComments(true)
  checkFavorite()
}

async function loadEpisodes(): Promise<void> {
  try {
    const rows = await get<EpisodeItem[]>('/api/anime/' + encodeURIComponent(animeKey.value) + '/episodes', undefined, { silent: true })
    episodeList.value = (rows || []).filter(e => e && e.episodeNum != null).sort((a, b) => a.episodeNum - b.episodeNum)
  } catch {
    episodeList.value = []
  }
  // 兜底：详情接口已带回 Bangumi 远程剧集
  if (!episodeList.value.length && detail.value?.remoteEpisodes?.length) {
    episodeList.value = detail.value.remoteEpisodes
      .map(e => ({ episodeNum: Number(e.episodeNum ?? 0), title: e.title }))
      .filter(e => e.episodeNum > 0)
  }
}

async function loadRatingStat(): Promise<void> {
  try {
    ratingStat.value = await get<RatingStat>('/api/rating/anime/' + encodeURIComponent(animeKey.value), undefined, { silent: true })
  } catch {
    ratingStat.value = null
  }
}

async function checkFavorite(): Promise<void> {
  if (!userStore.isLoggedIn()) {
    favorited.value = false
    return
  }
  try {
    // 后端返回 { favorite: true/false }
    const data = await get<{ favorite?: boolean }>(
      '/api/user/favorite/' + encodeURIComponent(animeKey.value) + '/check',
      undefined,
      { silent: true }
    )
    favorited.value = data?.favorite === true
  } catch {
    favorited.value = false
  }
}

async function toggleFavorite(): Promise<void> {
  if (!userStore.isLoggedIn()) return toLogin()
  if (!detail.value) return
  favLoading.value = true
  try {
    if (favorited.value) {
      await del('/api/user/favorite/' + encodeURIComponent(animeKey.value))
      favorited.value = false
      ElMessage.success('已取消追番')
    } else {
      const body: FavoriteItem = {
        animeKey: animeKey.value,
        title: detail.value.title,
        coverUrl: detail.value.coverUrl,
        lastEpisode: 1,
        status: 2,
        isFollow: 1
      }
      await post('/api/user/favorite', body)
      favorited.value = true
      ElMessage.success('已加入追番')
    }
  } catch {
    /* 错误提示由请求层统一处理 */
  } finally {
    favLoading.value = false
  }
}

async function submitRating(score: number): Promise<void> {
  if (!userStore.isLoggedIn()) return toLogin()
  ratingLoading.value = true
  try {
    // 只传业务字段：userId 由网关 JWT 注入（旧版写死 userId:1 属伪造身份）
    await post('/api/rating', { animeId: animeKey.value, score })
    ElMessage.success('评分成功')
    loadRatingStat()
  } catch {
    myScore.value = 0
  } finally {
    ratingLoading.value = false
  }
}

async function loadComments(append: boolean): Promise<void> {
  if (append) loadingMoreComments.value = true
  try {
    const rows = await get<CommentItem[]>(
      '/api/comment/anime/' + encodeURIComponent(animeKey.value),
      { page: commentPage.value, size: 20 },
      { silent: true }
    )
    const list = rows || []
    comments.value = append ? [...comments.value, ...list] : list
    hasMoreComments.value = list.length >= 20
  } catch {
    if (!append) comments.value = []
    hasMoreComments.value = false
  } finally {
    loadingMoreComments.value = false
  }
}

async function postComment(): Promise<void> {
  if (!userStore.isLoggedIn()) return toLogin()
  const content = commentText.value.trim()
  if (!content) return
  posting.value = true
  try {
    await post('/api/comment', { animeId: animeKey.value, content })
    commentText.value = ''
    commentPage.value = 1
    await loadComments(false)
    ElMessage.success('评论成功')
  } catch {
    /* 远程番剧未入库时后端返回"该番剧暂无本地数据，暂不支持评论"，由请求层提示 */
  } finally {
    posting.value = false
  }
}

async function likeComment(c: CommentItem): Promise<void> {
  if (!userStore.isLoggedIn()) return toLogin()
  if (!c.id) return
  try {
    await post('/api/comment/' + c.id + '/like')
    c.likeCount = (c.likeCount || 0) + 1
  } catch {
    /* ignore */
  }
}

async function loadBgmComments(reset: boolean): Promise<void> {
  if (reset) {
    bgmOffset.value = 0
    loadingMoreBgm.value = true
  } else {
    loadingMoreBgm.value = true
  }
  try {
    const rows = await get<BgmCommentItem[]>(
      '/api/anime/bgm-comments',
      { subjectId: animeKey.value, limit: 20, offset: bgmOffset.value },
      { silent: true }
    )
    const list = rows || []
    bgmComments.value = reset ? list : [...bgmComments.value, ...list]
    bgmHasMore.value = list.length >= 20
    bgmOffset.value += list.length
  } catch {
    if (reset) bgmComments.value = []
    bgmHasMore.value = false
  } finally {
    loadingMoreBgm.value = false
  }
}

function play(ep: number): void {
  router.push(`/play/${encodeURIComponent(animeKey.value)}/${ep}`)
}

function goBack(): void {
  if (window.history.length > 1) router.back()
  else router.push('/')
}

function toLogin(): void {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}

async function share(): Promise<void> {
  const url = window.location.href
  try {
    await navigator.clipboard.writeText(url)
    ElMessage.success('链接已复制')
  } catch {
    ElMessage.info(url)
  }
}

function formatTime(value?: string | number): string {
  if (!value) return ''
  const date = typeof value === 'number' ? new Date(value * 1000) : new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

function distPercent(cnt: number): number {
  return Math.round((cnt / maxDist.value) * 100)
}
</script>

<style scoped>
.back { margin-bottom: 8px; }
.skeleton-block { display: flex; gap: 24px; }
.sk-cover { width: 220px; aspect-ratio: 3 / 4; border-radius: var(--radius); flex: 0 0 auto; }
.sk-right { flex: 1; display: flex; flex-direction: column; gap: 14px; }
.sk-title { height: 30px; width: 60%; }
.sk-tag { height: 20px; width: 40%; }
.sk-desc { height: 80px; }

.hero { display: flex; gap: 28px; margin-bottom: 30px; }
.hero-cover { width: 224px; flex: 0 0 auto; aspect-ratio: 3 / 4; border-radius: var(--radius-lg); overflow: hidden; background: #222a38; box-shadow: var(--shadow-card); }
.hero-cover img { width: 100%; height: 100%; object-fit: cover; }
.cover-ph { display: flex; align-items: center; justify-content: center; height: 100%; font-size: 42px; font-weight: 800; color: #4a5568; }
.hero-info { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.title { font-size: 28px; font-weight: 800; line-height: 1.25; margin-bottom: 12px; }
.tags { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
.stats { display: flex; gap: 34px; margin-bottom: 14px; }
.stat { display: flex; flex-direction: column; }
.stat-num { font-size: 22px; font-weight: 800; color: var(--c-primary); }
.desc { color: var(--c-text-dim); font-size: 14px; line-height: 1.7; margin-bottom: 20px; }
.actions { display: flex; gap: 12px; flex-wrap: wrap; margin-top: auto; }

.ep-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(120px, 1fr)); gap: 10px; }
.ep-btn {
  display: flex; flex-direction: column; gap: 2px; align-items: flex-start;
  padding: 9px 11px; border-radius: 10px; cursor: pointer; text-align: left;
  background: var(--c-card); border: 1px solid var(--c-border); color: var(--c-text);
  transition: all .18s;
}
.ep-btn:hover { border-color: var(--c-primary); transform: translateY(-2px); }
.ep-num { font-weight: 800; color: var(--c-primary); font-size: 15px; }
.ep-title { font-size: 11.5px; color: var(--c-text-dim); width: 100%; }

.rate-box { display: flex; gap: 40px; flex-wrap: wrap; }
.rate-left { display: flex; flex-direction: column; gap: 6px; }
.rate-big { font-size: 40px; font-weight: 800; color: var(--c-primary); line-height: 1; }
.rate-right { flex: 1; min-width: 220px; max-width: 420px; display: flex; flex-direction: column; gap: 5px; }
.dist-row { display: flex; align-items: center; gap: 8px; font-size: 12px; }
.dist-score { width: 18px; text-align: right; color: var(--c-text-dim); }
.dist-bar { flex: 1; height: 7px; border-radius: 4px; background: rgba(255, 255, 255, .07); overflow: hidden; }
.dist-fill { height: 100%; border-radius: 4px; background: var(--c-gradient); }
.dist-cnt { width: 34px; }

.comment-editor { margin-bottom: 18px; }
.editor-foot { display: flex; justify-content: flex-end; align-items: center; gap: 10px; margin-top: 8px; }
.comment-list { display: flex; flex-direction: column; gap: 14px; }
.comment-item { display: flex; gap: 12px; }
.avatar {
  width: 38px; height: 38px; border-radius: 50%; flex: 0 0 auto;
  display: flex; align-items: center; justify-content: center; font-weight: 700; color: #fff;
  background: var(--c-gradient);
}
.comment-main { flex: 1; min-width: 0; }
.comment-head { display: flex; align-items: center; gap: 10px; margin-bottom: 4px; }
.comment-user { font-size: 13.5px; font-weight: 600; }
.comment-time { font-size: 11.5px; }
.comment-text { font-size: 13.5px; line-height: 1.65; color: var(--c-text); word-break: break-word; }
.comment-ops { margin-top: 2px; }
.comment-more { display: flex; justify-content: center; margin-top: 12px; }

.bgm-list { display: flex; flex-direction: column; gap: 14px; }
.bgm-item { display: flex; gap: 12px; }
.bgm-avatar { width: 36px; height: 36px; border-radius: 50%; object-fit: cover; flex: 0 0 auto; background: #222a38; }
.bgm-main { flex: 1; min-width: 0; }

@media (max-width: 768px) {
  .hero { flex-direction: column; align-items: center; text-align: center; }
  .hero-cover { width: 160px; }
  .hero-info { width: 100%; align-items: center; }
  .tags, .stats, .actions { justify-content: center; }
  .title { font-size: 21px; }
  .stats { gap: 22px; }
  .ep-grid { grid-template-columns: repeat(auto-fill, minmax(96px, 1fr)); }
  .sk-cover { width: 120px; }
}
</style>
