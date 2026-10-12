<template>
  <div class="anime-detail-page" v-loading="loading">
    <header class="detail-header">
      <div class="detail-header-inner">
        <a @click="goBack" class="back-link">← 返回</a>
      </div>
    </header>

    <div class="detail-content" v-if="anime.id || anime.title">
      <!-- Hero -->
      <div class="hero">
        <img :src="anime.coverUrl || fallbackCover" class="hero-cover" />
        <div class="hero-info">
          <h1>{{ anime.title }}</h1>
          <div class="meta-tags">
            <el-tag>{{ anime.type }}</el-tag>
            <el-tag type="success">{{ anime.status || '连载中' }}</el-tag>
            <el-tag type="info">{{ anime.year }}</el-tag>
            <span class="rating-display">⭐ {{ anime.ratingAvg || (anime.score ? anime.score.toFixed(1) : '暂无评分') }}</span>
          </div>
          <p class="desc">{{ anime.description || anime.summary || '暂无简介' }}</p>
          <div class="actions">
            <el-rate v-model="myRating" :max="10" @change="handleRate" show-score />
          </div>
          <!-- 本站播放：直接进入播放页，由后端 source-service 多源择优解析直链 -->
          <div class="watch-links">
            <button class="source-btn play-main" @click="goPlayFirst">
              ▶ 立即播放
            </button>
          </div>
        </div>
      </div>

      <!-- Episodes -->
      <div class="episodes-section">
        <h2>📺 剧集列表 ({{ episodes.length }}集)</h2>
        <div class="episode-grid" v-if="episodes.length">
          <div
            class="ep-item"
            v-for="ep in episodes"
            :key="ep.episodeNum"
            @click="goPlay(ep)"
          >
            <span class="ep-num">第{{ ep.episodeNum }}集</span>
            <span class="ep-title">{{ ep.title || ('第' + ep.episodeNum + '集') }}</span>
          </div>
        </div>
        <el-empty v-else description="暂无剧集" />
      </div>

      <!-- Bangumi 吐槽 -->
      <div class="comments-section">
        <h2>💬 Bangumi 吐槽 ({{ bgmComments.length }}{{ bgmHasMore ? '+' : '' }})</h2>
        <el-empty v-if="!bgmComments.length" description="暂无吐槽" />
        <div class="bgm-comment-item" v-for="c in bgmComments" :key="c.id">
          <img :src="c.avatar" class="bgm-avatar" v-if="c.avatar" alt="" />
          <div class="bgm-comment-body">
            <div class="bgm-comment-user">
              {{ c.username }}
              <span class="bgm-rate" v-if="c.rate">⭐ {{ c.rate }}</span>
            </div>
            <div class="bgm-comment-text">{{ c.comment }}</div>
          </div>
        </div>
        <div class="load-more" v-if="bgmHasMore">
          <el-button size="small" @click="loadMoreBgmComments" :loading="bgmLoading">加载更多</el-button>
        </div>
      </div>

      <!-- Comments -->
      <div class="comments-section">
        <h2>💬 本站评论 ({{ comments.length }})</h2>
        <div class="comment-form">
          <el-input v-model="newComment" type="textarea" :rows="3" placeholder="写下你的评论..." />
          <el-button type="primary" @click="postComment" :loading="posting" style="margin-top: 10px;">发表评论</el-button>
        </div>
        <div class="comment-list" v-if="comments.length">
          <div class="comment-item" v-for="c in comments" :key="c.id">
            <div class="comment-avatar">{{ c.username?.charAt(0) }}</div>
            <div class="comment-body">
              <div class="comment-user">{{ c.username }} <span class="time">{{ formatTime(c.createdAt) }}</span></div>
              <div class="comment-text">{{ c.content }}</div>
              <div class="comment-actions">
                <el-button text size="small" @click="likeComment(c.id)">👍 {{ c.likeCount || 0 }}</el-button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import api from '@/api'
import { ElMessage } from 'element-plus'

const route = useRoute()
const router = useRouter()
const anime = ref<any>({})
const animeKey = ref<string>('')   // 统一番剧标识：远程 "bgm:123" / 本地 "123"
const episodes = ref<any[]>([])
const comments = ref<any[]>([])
const bgmComments = ref<any[]>([])
const loading = ref(false)
const myRating = ref(0)
const newComment = ref('')
const posting = ref(false)
const fallbackCover = 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="300" height="420"><rect fill="%23e3e5e7" width="300" height="420"/></svg>'

const formatTime = (t: string) => {
  if (!t) return ''
  return new Date(t).toLocaleDateString('zh-CN')
}

// 统一跳转到播放页：用 animeKey + 集号（两者都稳定），不再混用 id/episodeNum
const goPlay = (ep: any) => {
  const epNum = ep.episodeNum ?? (episodes.value.indexOf(ep) + 1)
  router.push({
    path: `/play/${encodeURIComponent(animeKey.value)}/${epNum}`,
    query: { title: anime.value.title || '' }
  })
}

// 立即播放：跳到第 1 集（或第一集）
const goPlayFirst = () => {
  const first = episodes.value[0]
  if (first) goPlay(first)
  else goPlay({ episodeNum: 1 })
}

// 真·返回上一页（有历史则回退，否则回首页）
const goBack = () => {
  if (window.history.length > 1) router.back()
  else router.push('/')
}

const handleRate = async (val: number) => {
  try {
    await api.post('/api/rating', { animeId: anime.value.id, userId: 1, score: val })
    ElMessage.success('评分成功')
  } catch { /* ignore */ }
}

const postComment = async () => {
  if (!newComment.value.trim()) return
  posting.value = true
  try {
    await api.post('/api/comment', {
      animeId: anime.value.id,
      userId: 1,
      username: '当前用户',
      content: newComment.value
    })
    newComment.value = ''
    ElMessage.success('评论发表成功')
    loadComments()
  } catch { /* ignore */ }
  posting.value = false
}

const likeComment = async (id: number) => {
  try { await api.post('/api/comment/' + id + '/like'); loadComments() } catch { /* ignore */ }
}

const loadComments = async () => {
  const res = await api.get('/api/comment/anime/' + route.params.id)
  comments.value = res.data.data || []
}

// Bangumi 吐槽（从 next.bangumi.vip 镜像拉，支持分页）
const bgmLoading = ref(false)
const bgmOffset = ref(0)
const bgmHasMore = ref(true)
const BGM_PAGE_SIZE = 20

const loadBgmComments = async () => {
  bgmLoading.value = true
  try {
    const id = String(anime.value.id || route.params.id)
    const res = await api.get('/api/anime/bgm-comments', { params: { subjectId: id, limit: BGM_PAGE_SIZE, offset: 0 } })
    const list = res.data.data || []
    bgmComments.value = list
    bgmOffset.value = list.length
    bgmHasMore.value = list.length >= BGM_PAGE_SIZE
  } catch { bgmComments.value = [] }
  bgmLoading.value = false
}

const loadMoreBgmComments = async () => {
  bgmLoading.value = true
  try {
    const id = String(anime.value.id || route.params.id)
    const res = await api.get('/api/anime/bgm-comments', {
      params: { subjectId: id, limit: BGM_PAGE_SIZE, offset: bgmOffset.value }
    })
    const list = res.data.data || []
    bgmComments.value = bgmComments.value.concat(list)
    bgmOffset.value += list.length
    bgmHasMore.value = list.length >= BGM_PAGE_SIZE
  } catch { /* ignore */ }
  bgmLoading.value = false
}

onMounted(async () => {
  loading.value = true
  const id = route.params.id
  try {
    const animeRes = await api.get('/api/anime/' + id)
    const data = animeRes.data.data || {}
    anime.value = data
    // 统一番剧标识：后端对远程返回 "bgm:123"，本地返回 "123"
    animeKey.value = String(data.id || id)

    // Handle episodes — local or remote
    if (data.remoteEpisodes) {
      episodes.value = data.remoteEpisodes
    } else {
      try {
        const epRes = await api.get('/api/anime/' + id + '/episodes')
        episodes.value = epRes.data.data || []
      } catch { episodes.value = [] }
    }
  } catch { /* ignore */ }
  await loadComments()
  await loadBgmComments()
  loading.value = false
})
</script>

<style scoped>
.detail-header { background: white; box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.detail-header-inner { max-width: 1100px; margin: 0 auto; padding: 14px 20px; }
.detail-header-inner a, .back-link { color: var(--primary); cursor: pointer; }
.back-link:hover { text-decoration: underline; }
.play-main { font-size: 15px; padding: 10px 28px; border: none; cursor: pointer; }
.detail-content { max-width: 1100px; margin: 0 auto; padding: 30px 20px; }
.hero { display: flex; gap: 30px; margin-bottom: 40px; }
.hero-cover { width: 280px; height: 390px; object-fit: cover; border-radius: 12px; box-shadow: 0 8px 24px rgba(0,0,0,0.15); }
.hero-info { flex: 1; }
.hero-info h1 { font-size: 26px; margin-bottom: 12px; }
.meta-tags { display: flex; align-items: center; gap: 10px; margin-bottom: 16px; }
.rating-display { font-size: 14px; color: var(--primary); font-weight: 600; }
.desc { line-height: 1.8; color: #555; margin-bottom: 20px; }
.episodes-section h2, .comments-section h2 { margin-bottom: 16px; }
.episode-grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
.ep-item { cursor: pointer; padding: 12px; background: white; border-radius: 8px; border: 1px solid var(--border); transition: all 0.2s; }
.ep-item:hover { border-color: var(--primary); color: var(--primary); }
.ep-num { font-weight: 600; margin-right: 8px; }
.ep-title { font-size: 13px; color: var(--text-secondary); }
.comment-form { margin-bottom: 24px; }
.comment-item { display: flex; gap: 12px; padding: 16px 0; border-bottom: 1px solid var(--border); }
.bgm-comment-item { display: flex; gap: 12px; padding: 12px 0; border-bottom: 1px solid var(--border); }
.bgm-avatar { width: 36px; height: 36px; border-radius: 50%; object-fit: cover; flex-shrink: 0; background: #eee; }
.bgm-comment-body { flex: 1; }
.bgm-comment-user { font-weight: 500; font-size: 14px; }
.bgm-rate { color: var(--primary); font-size: 13px; margin-left: 8px; }
.bgm-comment-text { margin-top: 4px; line-height: 1.6; color: #444; }
.load-more { text-align: center; margin-top: 16px; }
.comment-avatar { width: 36px; height: 36px; border-radius: 50%; background: var(--primary); color: white; display: flex; align-items: center; justify-content: center; font-weight: 600; flex-shrink: 0; }
.comment-body { flex: 1; }
.comment-user { font-weight: 500; }
.time { font-size: 12px; color: var(--text-secondary); margin-left: 8px; }
.comment-text { margin: 6px 0; line-height: 1.6; }
.watch-links { margin-top: 20px; }
.source-buttons { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.source-btn { display: inline-block; padding: 6px 14px; background: var(--primary); color: white; border-radius: 6px; font-size: 13px; text-decoration: none; }
.source-btn:hover { background: var(--primary-hover); }
</style>
