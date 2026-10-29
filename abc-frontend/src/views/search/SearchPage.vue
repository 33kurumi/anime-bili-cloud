<template>
  <div class="page search-page">
    <!-- 搜索条：输入即取联想（防抖 200ms），回车/点击搜索 -->
    <div class="search-bar">
      <el-input
        v-model="keyword"
        size="large"
        placeholder="搜索番剧名、别名、关键词"
        clearable
        @keyup.enter="doSearch(keyword)"
        @focus="showSuggest = true"
        @input="onInput"
      >
        <template #prefix><el-icon><Search /></el-icon></template>
        <template #append><el-button @click="doSearch(keyword)">搜索</el-button></template>
      </el-input>

      <!-- 联想下拉：后端 /api/search/suggest -->
      <div v-if="showSuggest && suggestions.length" class="suggest" @click.stop>
        <div v-for="s in suggestions" :key="s" class="suggest-item" @click="doSearch(s)">
          <el-icon><Search /></el-icon><span class="ellipsis">{{ s }}</span>
        </div>
      </div>

      <!-- 搜索历史：Pinia + localStorage 持久化 -->
      <div v-if="searchStore.history.length" class="hist-row">
        <span class="weak hist-label">搜索历史</span>
        <el-tag
          v-for="h in searchStore.history"
          :key="h"
          closable
          size="small"
          class="hist-tag"
          @click="doSearch(h)"
          @close="searchStore.delItem(h)"
        >{{ h }}</el-tag>
        <el-button text size="small" @click="searchStore.clearAll()">清空</el-button>
      </div>
    </div>

    <!-- 无关键词：热搜榜（后端 Redis ZINCRBY 统计） -->
    <section v-if="!searched" class="section">
      <div class="section-head">
        <h2 class="section-title">热门搜索</h2>
      </div>
      <div v-if="hotKeywords.length" class="hot-list">
        <div v-for="(h, i) in hotKeywords" :key="h.keyword" class="hot-item" @click="doSearch(h.keyword)">
          <span class="hot-rank" :class="{ top: i < 3 }">{{ i + 1 }}</span>
          <span class="ellipsis hot-word">{{ h.keyword }}</span>
          <span class="weak hot-count">{{ h.count || 0 }}</span>
        </div>
      </div>
      <div v-else class="empty-block">热搜数据为空（搜索几次就会产生热度统计）</div>
    </section>

    <!-- 有结果 -->
    <section v-else class="section">
      <div class="section-head">
        <h2 class="section-title">“{{ activeKeyword }}” 的搜索结果</h2>
        <span class="weak">已加载 {{ list.length }} 部</span>
      </div>
      <div v-if="loading" class="card-grid">
        <div v-for="i in 10" :key="i" class="sk-card">
          <div class="skeleton sk-cover"></div>
          <div class="skeleton sk-line"></div>
        </div>
      </div>
      <template v-else-if="list.length">
        <div class="card-grid">
          <AnimeCard v-for="anime in list" :key="String(anime._id ?? anime.id ?? anime.title)" :anime="anime" />
        </div>
        <div class="load-more">
          <el-button v-if="hasMore" :loading="loadingMore" round @click="loadMore">加载更多</el-button>
          <span v-else class="weak">没有更多了</span>
        </div>
      </template>
      <el-empty v-else description="没有找到相关番剧，换个关键词试试" />
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 搜索页。
 *
 * 相比旧版新增：输入联想（/api/search/suggest）、热搜榜（/api/search/hot）、搜索历史持久化（Pinia）、
 * 服务端分页（加载更多）、结果卡片按 _source 正确跳转（旧版把所有结果都当远程番剧拼 bgm:，
 * 本地命中项点进去必然 404，这是审计报告里点名的 bug）。
 */
import { onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import AnimeCard from '@/components/AnimeCard.vue'
import { get } from '@/api'
import { useSearchStore } from '@/stores/searchHistory'
import type { AnimeItem } from '@/types'

const route = useRoute()
const router = useRouter()
const searchStore = useSearchStore()

const PAGE_SIZE = 18

const keyword = ref('')
const activeKeyword = ref('')
const suggestions = ref<string[]>([])
const hotKeywords = ref<Array<{ keyword: string; count?: number }>>([])
const list = ref<AnimeItem[]>([])
const page = ref(1)
const hasMore = ref(false)
const loading = ref(false)
const loadingMore = ref(false)
const searched = ref(false)
const showSuggest = ref(false)

let suggestTimer: number | undefined

onMounted(() => {
  loadHot()
  const incoming = String(route.query.keyword || '')
  if (incoming) {
    keyword.value = incoming
    doSearch(incoming)
  }
})

watch(
  () => route.query.keyword,
  value => {
    const next = String(value || '')
    if (next && next !== activeKeyword.value) {
      keyword.value = next
      doSearch(next)
    }
  }
)

function onInput(value: string): void {
  showSuggest.value = true
  window.clearTimeout(suggestTimer)
  const word = value.trim()
  if (!word) {
    suggestions.value = []
    return
  }
  // 200ms 防抖，避免每个字符打一次后端
  suggestTimer = window.setTimeout(async () => {
    try {
      const data = await get<string[]>('/api/search/suggest', { keyword: word }, { silent: true })
      suggestions.value = (data || []).slice(0, 8)
    } catch {
      suggestions.value = []
    }
  }, 200)
}

async function loadHot(): Promise<void> {
  try {
    const data = await get<Array<{ keyword: string; count?: number }>>('/api/search/hot', undefined, { silent: true })
    hotKeywords.value = data || []
  } catch {
    hotKeywords.value = []
  }
}

function doSearch(word: string): void {
  const w = (word || '').trim()
  showSuggest.value = false
  suggestions.value = []
  if (!w) return
  keyword.value = w
  searchStore.addHistory(w)
  if (String(route.query.keyword || '') !== w) {
    router.replace({ path: '/search', query: { keyword: w } })
  }
  activeKeyword.value = w
  searched.value = true
  page.value = 1
  list.value = []
  load(false)
}

async function loadMore(): Promise<void> {
  if (loadingMore.value || !hasMore.value) return
  page.value += 1
  await load(true)
}

async function load(append: boolean): Promise<void> {
  if (append) loadingMore.value = true
  else loading.value = true
  try {
    const data = await get<AnimeItem[]>('/api/search/anime', { keyword: activeKeyword.value, page: page.value, size: PAGE_SIZE }, { silent: true })
    const rows = data || []
    list.value = append ? [...list.value, ...rows] : rows
    hasMore.value = rows.length >= PAGE_SIZE
  } catch {
    if (!append) list.value = []
    hasMore.value = false
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}
</script>

<style scoped>
.search-page { position: relative; }
.search-bar { position: relative; max-width: 720px; margin: 8px auto 30px; }
.search-bar :deep(.el-input-group__append) { background: var(--c-primary); color: #fff; border-color: var(--c-primary); }
.suggest {
  position: absolute; z-index: 30; left: 0; right: 0; top: 52px;
  background: var(--c-card); border: 1px solid var(--c-border);
  border-radius: var(--radius); box-shadow: var(--shadow-hover); overflow: hidden;
}
.suggest-item { display: flex; align-items: center; gap: 8px; padding: 9px 14px; font-size: 13.5px; cursor: pointer; }
.suggest-item:hover { background: rgba(255, 255, 255, .06); }
.hist-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
.hist-label { font-size: 12.5px; }
.hist-tag { cursor: pointer; }

.hot-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px 24px; max-width: 760px; }
.hot-item { display: flex; align-items: center; gap: 10px; padding: 9px 12px; border-radius: 10px; cursor: pointer; }
.hot-item:hover { background: rgba(255, 255, 255, .05); }
.hot-rank { width: 22px; text-align: center; font-weight: 800; color: var(--c-text-dim); }
.hot-rank.top { color: var(--c-primary); }
.hot-word { flex: 1; min-width: 0; font-size: 13.5px; }
.hot-count { font-size: 12px; }

.sk-card { background: var(--c-card); border-radius: var(--radius); overflow: hidden; padding-bottom: 12px; }
.sk-cover { aspect-ratio: 3 / 4; border-radius: 0; }
.sk-line { height: 12px; margin: 10px 10px 0; }
.load-more { display: flex; justify-content: center; margin: 26px 0 8px; }

@media (max-width: 768px) {
  .hot-list { grid-template-columns: 1fr; }
  .search-bar { margin-bottom: 20px; }
}
</style>
