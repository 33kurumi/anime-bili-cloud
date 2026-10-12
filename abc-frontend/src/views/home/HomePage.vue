<template>
  <div class="home-page">
    <!-- Header / Nav -->
    <header class="header">
      <div class="header-inner">
        <router-link to="/" class="logo">
          <span class="logo-icon">🎬</span>
          <span class="logo-text">ABC Anime</span>
        </router-link>
        <div class="nav-links">
          <router-link to="/">首页</router-link>
          <div class="search-wrap" style="position:relative;">
            <el-input
                v-model="searchKeyword"
                placeholder="搜索番剧..."
                class="search-input"
                @keyup.enter="goSearch"
                @focus="handleFocus"
                @blur="handleBlur"
            >
              <template #append>
                <el-button @click="goSearch">
                  <el-icon><Search /></el-icon>
                </el-button>
              </template>
            </el-input>

            <!-- 搜索历史下拉面板 -->
            <div
                v-show="showHistory && searchStore.history.length && !searchKeyword"
                class="search-history"
            >
              <div class="history-header">
                <span>搜索历史</span>
                <el-button text size="small" @click="clearHistory">清空</el-button>
              </div>
              <div
                  v-for="item in searchStore.history"
                  :key="item"
                  class="history-item"
              >
                <span @click="selectHistory(item)">{{ item }}</span>
                <el-icon class="delete-icon" @click.stop="deleteHistory(item)">
                  <Delete />
                </el-icon>
              </div>
            </div>
          </div>
        </div>
        <div class="user-area">
          <template v-if="!userStore.isLoggedIn()">
            <el-button type="primary" size="small" @click="goLogin">登录</el-button>
            <el-button size="small" @click="goRegister">注册</el-button>
          </template>
        </div>
      </div>
    </header>

    <!-- Banner -->
    <section class="banner">
      <div class="banner-content">
        <h1>Anime Bilibili Cloud</h1>
        <p>追番、弹幕、评论 — 发现属于你的番剧世界</p>
      </div>
    </section>

    <!-- Hot Anime -->
    <section class="section">
      <div class="section-header">
        <h2>🔥 热门番剧</h2>
        <router-link to="/search" class="more">查看更多 →</router-link>
      </div>
      <div class="anime-grid" v-loading="loading">
        <div
          class="anime-card"
          v-for="anime in hotAnime"
          :key="anime.id"
          @click="goToDetail(anime.id)"
        >
          <div class="cover-wrap">
            <img :src="anime.coverUrl || fallbackCover" :alt="anime.title" />
            <span class="ep-tag">
              <span v-if="anime.episodesCount > 0">{{ anime.episodesCount }}集</span>
              <span v-else>连载中</span>
            </span>
            <span class="score-tag" v-if="anime.ratingAvg">⭐ {{ anime.ratingAvg.toFixed(1) }}</span>
          </div>
          <div class="card-info">
            <h3 class="anime-title">{{ anime.title }}</h3>
            <p class="anime-meta">{{ anime.type }} · {{ anime.year }}</p>
          </div>
        </div>
      </div>
    </section>

    <!-- Categories -->
    <section class="section">
      <div class="section-header">
        <h2>📂 分类浏览</h2>
      </div>
      <div class="category-tabs">
        <el-tag
            v-for="tag in tags"
            :key="tag.id"
            class="category-tag"
            :type="tag.type === 'category' ? '' : 'info'"
            @click="jumpTagSearch(tag.name)"
        >
          {{ tag.name }}
        </el-tag>
      </div>
    </section>

    <footer class="footer">
      <p>© 2026 Anime Bilibili Cloud — Syのweb</p>
    </footer>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { useSearchStore } from '@/stores/searchHistory'
import api from '@/api'
import { Search, Delete } from '@element-plus/icons-vue'

const router = useRouter()
const userStore = useUserStore()
const searchStore = useSearchStore()
const searchKeyword = ref('')
const hotAnime = ref<any[]>([])
const tags = ref<any[]>([])
const loading = ref(false)
const fallbackCover = 'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="200" height="280"><rect fill="%23e3e5e7" width="200" height="280"/><text x="100" y="140" text-anchor="middle" fill="%23999">No Image</text></svg>'

const jumpTagSearch = (name: string) => {
  router.push('/search?keyword=' + encodeURIComponent(name))
}
// 跳转登录页
const goLogin = () => {
  router.push('/login')
}
// 跳转注册页
const goRegister = () => {
  router.push('/register')
}
// 输入框聚焦显示历史
const showHistory = ref(false)
// 输入框聚焦显示历史
const handleFocus = () => {
  if (searchStore.history.length) showHistory.value = true
}
// 失焦延迟隐藏
const handleBlur = () => {
  setTimeout(() => {
    showHistory.value = false
  }, 150)
}
// 点击词条搜索
const selectHistory = (word: string) => {
  searchKeyword.value = word
  showHistory.value = false
  goSearch()
}
// 删除单条
const deleteHistory = (word: string) => {
  searchStore.delItem(word)
}
// 清空全部
const clearHistory = () => {
  searchStore.clearAll()
}

const goSearch = () => {
  const word = searchKeyword.value.trim()
  if (!word) return
  // 交给pinia统一处理去重、存储、条数限制
  searchStore.addHistory(word)
  showHistory.value = false
  router.push('/search?keyword=' + encodeURIComponent(word))
}

const goToDetail = (id: string | number) => {
  let cleanId = String(id)
  while (cleanId.startsWith('bgm:')) {
    cleanId = cleanId.substring(4)
  }
  router.push('/anime/bgm:' + cleanId)
}

onMounted(async () => {

  loading.value = true
  try {
    const [hotRes, tagRes] = await Promise.all([
      api.get('/api/anime/hot?limit=12'),
      api.get('/api/anime/tags')
    ])
    hotAnime.value = hotRes.data.data || []
    tags.value = tagRes.data.data || []
  } catch { /* ignore */ }
  loading.value = false
})
</script>

<style scoped>
.header {
  background: var(--bg-white);
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
  position: sticky;
  top: 0;
  z-index: 100;
}
.header-inner {
  max-width: 1280px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  padding: 12px 20px;
  gap: 20px;
}
/* 搜索历史下拉样式 */
.search-wrap {
  position: relative;
}
.search-history {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  margin-top: 4px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  box-shadow: 0 2px 12px rgba(0,0,0,0.08);
  z-index: 999;
  padding: 8px;
}
.history-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 4px 6px 8px;
  font-size: 13px;
  color: #666;
}
.history-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 6px 8px;
  border-radius: 4px;
  cursor: pointer;
  font-size: 14px;
}
.history-item:hover {
  background: #f5f7fa;
}
.delete-icon {
  font-size: 16px;
  color: #c0c4cc;
}
.delete-icon:hover {
  color: #f56c6c;
}

.logo { display: flex; align-items: center; gap: 8px; font-size: 20px; font-weight: 700; color: var(--primary); }
.logo-icon { font-size: 28px; }
.nav-links { display: flex; align-items: center; gap: 16px; flex: 1; }
.nav-links a { font-size: 14px; color: var(--text-primary); padding: 4px 8px; }
.nav-links a:hover, .nav-links a.router-link-active { color: var(--primary); }
.search-input { width: 280px; }
.user-area { display: flex; align-items: center; gap: 10px; white-space: nowrap; }

.banner {
  background: linear-gradient(135deg, #fb7299 0%, #ffb6c1 50%, #ffd1d9 100%);
  padding: 80px 20px;
  text-align: center;
  color: white;
}
.banner h1 { font-size: 36px; margin-bottom: 12px; }
.banner p { font-size: 16px; opacity: 0.9; }

.section { max-width: 1280px; margin: 0 auto; padding: 32px 20px; }
.section-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 20px; }
.section-header h2 { font-size: 20px; }
.more { color: var(--primary); font-size: 14px; }

.anime-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 16px; }
@media (max-width: 1200px) { .anime-grid { grid-template-columns: repeat(4, 1fr); } }
@media (max-width: 768px) { .anime-grid { grid-template-columns: repeat(2, 1fr); } }

.anime-card { cursor: pointer; border-radius: 8px; overflow: hidden; background: var(--bg-white); transition: transform 0.2s, box-shadow 0.2s; }
.anime-card:hover { transform: translateY(-4px); box-shadow: 0 8px 24px rgba(0,0,0,0.12); }
.cover-wrap { position: relative; aspect-ratio: 3/4; overflow: hidden; }
.cover-wrap img { width: 100%; height: 100%; object-fit: cover; }
.ep-tag { position: absolute; bottom: 6px; left: 6px; background: rgba(0,0,0,0.7); color: white; font-size: 12px; padding: 2px 6px; border-radius: 4px; }
.score-tag { position: absolute; top: 6px; right: 6px; background: rgba(0,0,0,0.7); color: #ffd700; font-size: 12px; padding: 2px 6px; border-radius: 4px; }
.card-info { padding: 10px 8px; }
.anime-title { font-size: 14px; font-weight: 500; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.anime-meta { font-size: 12px; color: var(--text-secondary); margin-top: 4px; }

.category-tabs { display: flex; flex-wrap: wrap; gap: 10px; }
.category-tag { cursor: pointer; }

.footer { text-align: center; padding: 40px 20px; color: var(--text-secondary); font-size: 13px; }
</style>
