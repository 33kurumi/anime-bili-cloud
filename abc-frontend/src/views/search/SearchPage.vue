<template>
  <div class="search-page">
    <div class="search-header">
      <div class="search-header-inner">
        <a class="back-link" @click="goBack">← 返回</a>
        <div class="search-wrap" style="position:relative;flex:1;">
          <el-input
              v-model="keyword"
              placeholder="搜索番剧..."
              size="large"
              class="search-bar"
              @keyup.enter="doSearch"
              @focus="handleFocus"
              @blur="handleBlur"
              :prefix-icon="Search"
          />
          <!-- 搜索历史下拉 -->
          <div
              v-show="showHistory && searchStore.history.length && !keyword"
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
        <el-button type="primary" size="large" @click="doSearch">搜索</el-button>
      </div>
    </div>

    <!-- 无搜索词时展示本周每日放送 -->
    <div v-if="!searched" class="calendar-section">
      <h3 class="calendar-title">📅 本季每日放送</h3>
      <div class="calendar-grid" v-loading="loading">
        <!-- 循环7天，每天一列 -->
        <div
            v-for="dayItem in calendarList"
            :key="dayItem.day"
            class="day-column"
        >
          <div class="day-header">{{ dayItem.day }}</div>
          <div class="anime-list">
            <!-- 当天的番剧卡片 -->
            <div
                v-for="anime in dayItem.animeList"
                :key="anime.id"
                class="anime-card"
                @click="goToDetail(anime.id)"
            >
              <div class="cover-wrap">
                <img :src="anime.coverUrl || fallbackCover" :alt="anime.title" />
                <span class="score" v-if="anime.ratingAvg">⭐ {{ Number(anime.ratingAvg).toFixed(1) }}</span>
              </div>
              <div class="card-info">
                <h4 class="card-title">{{ anime.title }}</h4>
                <p class="card-meta">
                  {{ anime.type }} · {{ anime.episodesCount > 0 ? anime.episodesCount + '集' : '连载中' }}
                </p>
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- Search Results -->
    <div class="results" v-else>
      <h3>搜索结果: "{{ searchKeyword }}"</h3>
      <div class="anime-grid" v-loading="loading">
        <div
          class="anime-card"
          v-for="anime in results"
          :key="anime._id"
          @click="goToDetail(anime._id)"
        >
          <div class="cover-wrap">
            <img :src="anime.coverUrl || fallbackCover" :alt="anime.title" />
            <span class="score" v-if="anime.rating_avg">⭐ {{ typeof anime.rating_avg === 'number' ? anime.rating_avg.toFixed(1) : anime.rating_avg }}</span>
          </div>
          <div class="card-info">
            <h4 v-html="anime.title || '未知番剧'"></h4>
            <p>{{ anime.type }} · {{ anime.year }}</p>
          </div>
        </div>
      </div>
      <el-empty v-if="!loading && results.length === 0" description="未找到相关番剧" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useSearchStore } from '@/stores/searchHistory'
import api from '@/api'
import { Search, Delete } from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const searchStore = useSearchStore()
const keyword = ref('')

const goBack = () => {
  if (window.history.length > 1) router.back()
  else router.push('/')
}
const searchKeyword = ref('')
const searched = ref(false)
const results = ref<any[]>([])
const calendarList = ref<any[]>([])
const loading = ref(false)
// const currentPage = ref(1)
// const pageSize = ref(40)
// const total = ref(0)
// const totalPages = ref(0)
const fallbackCover = 'data:image/svg+xml,' +
  '<svg xmlns="http://www.w3.org/2000/svg" width="200" height="280">' +
  '<rect fill="%23e3e5e7" width="200" height="280"/>' +
  '</svg>'

const doSearch = async () => {
  const word = keyword.value.trim()
  if (!word) return
  // pinia统一处理历史存储
  searchStore.addHistory(word)

  searchKeyword.value = word
  searched.value = true
  loading.value = true
  try {
    const res = await api.get('/api/search/anime', { params: { keyword: word } })
    results.value = res.data.data || []
  } catch { results.value = [] }
  loading.value = false
}

const showHistory = ref(false)
const handleFocus = () => {
  if (searchStore.history.length) showHistory.value = true
}
const handleBlur = () => {
  setTimeout(() => {
    showHistory.value = false
  }, 150)
}
const selectHistory = (word: string) => {
  keyword.value = word
  showHistory.value = false
  doSearch()
}
const deleteHistory = (word: string) => {
  searchStore.delItem(word)
}
const clearHistory = () => {
  searchStore.clearAll()
}

const goToDetail = (id: string | number) => {
  let cleanId = String(id)
  // 循环移除所有重复的 bgm: 前缀
  while (cleanId.startsWith('bgm:')) {
    cleanId = cleanId.substring(4)
  }
  // 统一拼成标准格式 bgm:数字
  router.push('/anime/bgm:' + cleanId)
}

const loadAllAnime = async () => {
  loading.value = true
  try {
    const res = await api.get('/api/anime/calendar')
    // 直接保留7天的分组结构，不再压平
    calendarList.value = res.data.data || []
  } catch {
    calendarList.value = []
  }
  loading.value = false
}

onMounted(async () => {

  const q = route.query.keyword as string
  if (q) {
    keyword.value = q
    doSearch()
  } else {
    loadAllAnime()
  }
})
</script>

<style scoped>
.search-header { background: var(--bg-white); box-shadow: 0 2px 8px rgba(0,0,0,0.06); }
.search-header-inner {
  max-width: 900px;
  margin: 0 auto;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
}
.search-wrap {
  position: relative;
  flex: 1;
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

.search-bar { flex: 1; }
.back-link { color: var(--primary); white-space: nowrap; }

.results { max-width: 900px; margin: 0 auto; padding: 30px 20px; }

/* 日历整体容器 */
.calendar-section {
  max-width: 1400px;
  margin: 0 auto;
  padding: 20px;
}
.calendar-title {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 16px;
  color: #333;
}

/* 7列网格布局 */
.calendar-grid {
  display: grid;
  grid-template-columns: repeat(7, 1fr);
  gap: 12px;
  align-items: start;
}

/* 每一天的列 */
.day-column {
  background: #fff;
  border-radius: 8px;
  overflow: hidden;
  box-shadow: 0 2px 8px rgba(0,0,0,0.06);
}
.day-column:nth-child(1) .day-header { background: linear-gradient(135deg, #ff4d4f, #ff7875); } /* 周日 */
.day-column:nth-child(2) .day-header { background: linear-gradient(135deg, #ff7a45, #ff9c6e); } /* 周一 */
.day-column:nth-child(3) .day-header { background: linear-gradient(135deg, #ffad14, #ffc53d); } /* 周二 */
.day-column:nth-child(4) .day-header { background: linear-gradient(135deg, #52c41a, #73d13d); } /* 周三 */
.day-column:nth-child(5) .day-header { background: linear-gradient(135deg, #13c2c2, #36cfc9); } /* 周四 */
.day-column:nth-child(6) .day-header { background: linear-gradient(135deg, #1890ff, #40a9ff); } /* 周五 */
.day-column:nth-child(7) .day-header { background: linear-gradient(135deg, #722ed1, #9254de); } /* 周六 */
.day-header {
  padding: 10px;
  text-align: center;
  font-weight: 600;
  font-size: 14px;
  color: #fff;
}
.anime-list {
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

/* 番剧卡片（适配窄列） */
.anime-card {
  cursor: pointer;
  border-radius: 6px;
  overflow: hidden;
  background: #fafafa;
  transition: transform 0.2s, box-shadow 0.2s;
}
.anime-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0,0,0,0.1);
}
.cover-wrap {
  position: relative;
  aspect-ratio: 3 / 4;
  overflow: hidden;
}
.cover-wrap img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.score {
  position: absolute;
  top: 4px;
  right: 4px;
  background: rgba(0,0,0,0.7);
  color: #ffd700;
  font-size: 11px;
  padding: 2px 5px;
  border-radius: 3px;
}
.card-info {
  padding: 6px 8px;
}
.card-title {
  font-size: 12px;
  font-weight: 500;
  margin: 0 0 3px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.card-meta {
  font-size: 11px;
  color: #999;
  margin: 0;
}

/* 响应式适配：屏幕变窄自动减少列数 */
@media (max-width: 1200px) {
  .calendar-grid {
    grid-template-columns: repeat(4, 1fr);
  }
}
@media (max-width: 768px) {
  .calendar-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

.anime-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 16px; margin-top: 16px; }
@media (max-width: 768px) { .anime-grid { grid-template-columns: repeat(2, 1fr); } }
.anime-card { cursor: pointer; border-radius: 8px; overflow: hidden; background: var(--bg-white); transition: transform 0.2s; }
.anime-card:hover { transform: translateY(-4px); box-shadow: 0 8px 24px rgba(0,0,0,0.12); }
.cover-wrap { position: relative; aspect-ratio: 3/4; overflow: hidden; }
.cover-wrap img { width: 100%; height: 100%; object-fit: cover; }
.score { position: absolute; top: 6px; right: 6px; background: rgba(0,0,0,0.7); color: #ffd700; font-size: 12px; padding: 2px 6px; border-radius: 4px; }
.card-info { padding: 8px; }
.card-info h4 { font-size: 14px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.card-info p { font-size: 12px; color: var(--text-secondary); }
</style>
