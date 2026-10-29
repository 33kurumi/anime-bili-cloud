<template>
  <div class="page home">
    <!-- ============ 1. 主视觉 Banner：取热门第一条做背景 ============ -->
    <section class="hero" :style="heroStyle">
      <div class="hero-mask"></div>
      <div class="hero-body">
        <div class="hero-tag">今日推荐</div>
        <h1 class="hero-title">{{ hero?.title || 'ABC Anime · 番剧聚合与弹幕播放' }}</h1>
        <p class="hero-desc clamp-2">
          {{ hero?.description || '聚合 Bangumi 番剧资料与多源播放线路，支持自研 canvas 弹幕引擎、播放进度记忆与自动换源。' }}
        </p>
        <div class="hero-actions">
          <el-button type="primary" size="large" round :disabled="!hero" @click="goHero">
            {{ hero ? '立即观看' : '浏览番剧库' }}
          </el-button>
          <el-button size="large" round @click="router.push('/bangumi')">番剧库</el-button>
        </div>
      </div>
    </section>

    <!-- ============ 2. 继续观看（登录后可见） ============ -->
    <section v-if="userStore.isLoggedIn() && continuing.length" class="section">
      <div class="section-head">
        <h2 class="section-title">继续观看</h2>
        <el-button text size="small" @click="goUserCenter">全部记录</el-button>
      </div>
      <div class="h-scroll">
        <div v-for="item in continuing" :key="item.animeKey + '-' + item.episodeNum" class="cont-card abc-card"
          @click="continueWatch(item)">
          <div class="cont-cover">
            <img v-if="item.coverUrl" :src="item.coverUrl" :alt="item.title" loading="lazy" />
            <div v-else class="cont-cover-ph">番</div>
            <span class="cont-ep">第 {{ item.episodeNum }} 话</span>
          </div>
          <div class="cont-meta">
            <div class="ellipsis cont-title">{{ item.title || item.animeKey }}</div>
            <el-progress :percentage="progressPercent(item)" :show-text="false" :stroke-width="4" />
            <div class="weak cont-time">{{ formatSec(item.positionSec) }} / {{ formatSec(item.durationSec || 0) }}</div>
          </div>
        </div>
      </div>
    </section>

    <!-- ============ 3. 热门推荐 ============ -->
    <section class="section">
      <div class="section-head">
        <h2 class="section-title">热门推荐</h2>
        <el-button text size="small" @click="router.push('/bangumi')">查看更多</el-button>
      </div>
      <div v-if="loadingHot" class="card-grid">
        <div v-for="i in 6" :key="i" class="sk-card">
          <div class="skeleton sk-cover"></div>
          <div class="skeleton sk-line"></div>
        </div>
      </div>
      <div v-else-if="hot.length" class="card-grid">
        <AnimeCard v-for="anime in hot" :key="String(anime.id ?? anime._id ?? anime.title)" :anime="anime" />
      </div>
      <div v-else class="empty-block">暂无热门数据（请确认 anime-service 已启动并完成数据同步）</div>
    </section>

    <!-- ============ 4. 新番时间表 ============ -->
    <section class="section">
      <div class="section-head">
        <h2 class="section-title">新番时间表</h2>
        <span class="weak">数据来源：Bangumi 每日放送</span>
      </div>
      <div v-if="loadingCalendar" class="skeleton cal-skeleton"></div>
      <div v-else-if="calendar.length" class="calendar">
        <div v-for="day in calendar" :key="String(day.day)" class="cal-col">
          <div class="cal-day">{{ day.day }}</div>
          <div class="cal-list">
            <div v-for="anime in day.animeList || []" :key="String(anime.id ?? anime._id ?? anime.title)"
              class="cal-item" @click="openDetail(anime)">
              <img v-if="anime.coverUrl" :src="anime.coverUrl" :alt="anime.title" loading="lazy" class="cal-cover" />
              <span class="ellipsis cal-title">{{ anime.title }}</span>
            </div>
          </div>
        </div>
      </div>
      <div v-else class="empty-block">暂无时间表数据</div>
    </section>
  </div>
</template>

<script setup lang="ts">
/**
 * 首页。
 *
 * 【分区逻辑（对齐青空次元的首页信息架构）】
 *   Banner（今日推荐）→ 继续观看 → 热门推荐 → 新番时间表
 * 每块都有独立的加载态与空态：后端某个服务挂了不会整页白屏（旧版首页是一个 fetch 失败整页空）。
 * 匿名可看：未登录时不请求 /api/user/**，只展示公开数据。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AnimeCard from '@/components/AnimeCard.vue'
import { get } from '@/api'
import { useUserStore } from '@/stores/user'
import type { AnimeItem, ProgressItem, ProgressSyncResult, WatchHistoryItem } from '@/types'

const router = useRouter()
const userStore = useUserStore()

const hot = ref<AnimeItem[]>([])
const calendar = ref<AnimeItem[]>([])
const continuing = ref<ProgressItem[]>([])
const loadingHot = ref(true)
const loadingCalendar = ref(true)

const hero = computed<AnimeItem | null>(() => hot.value[0] ?? null)

const heroStyle = computed(() => {
  const cover = hero.value?.coverUrl
  return cover
    ? { backgroundImage: `linear-gradient(90deg, rgba(15,17,23,.95) 0%, rgba(15,17,23,.55) 55%, rgba(15,17,23,.2) 100%), url(${cover})` }
    : {}
})

onMounted(() => {
  loadHot()
  loadCalendar()
  if (userStore.isLoggedIn()) loadContinuing()
})

async function loadHot(): Promise<void> {
  loadingHot.value = true
  try {
    hot.value = (await get<AnimeItem[]>('/api/anime/hot', { limit: 12 }, { silent: true })) || []
  } catch {
    hot.value = []
  } finally {
    loadingHot.value = false
  }
}

async function loadCalendar(): Promise<void> {
  loadingCalendar.value = true
  try {
    calendar.value = (await get<AnimeItem[]>('/api/anime/calendar', undefined, { silent: true })) || []
  } catch {
    calendar.value = []
  } finally {
    loadingCalendar.value = false
  }
}

/** 继续观看：优先新契约 /api/user/progress，失败回退旧接口 /api/user/history */
async function loadContinuing(): Promise<void> {
  try {
    const data = await get<ProgressSyncResult>('/api/user/progress', { since: 0 }, { silent: true })
    const list = data?.items || []
    continuing.value = list.filter(p => !p.finished).slice(0, 10)
    return
  } catch {
    // TODO(后端契约): /api/user/progress 未就绪或游客访问（401/空）时降级到 /api/user/history
    /* 后端未实现 /progress 时走下面的历史接口兜底 */
  }
  try {
    const history = (await get<WatchHistoryItem[]>('/api/user/history', { limit: 10 }, { silent: true })) || []
    continuing.value = history.map(h => ({
      animeKey: h.animeKey,
      episodeNum: h.episodeNum,
      positionSec: h.progress || 0,
      durationSec: 0,
      title: h.title,
      coverUrl: h.coverUrl
    }))
  } catch {
    continuing.value = []
  }
}

function progressPercent(item: ProgressItem): number {
  if (!item.durationSec) return 0
  return Math.min(100, Math.round((item.positionSec / item.durationSec) * 100))
}

function formatSec(sec: number): string {
  if (!sec || sec < 0) return '00:00'
  const m = Math.floor(sec / 60)
  const s = Math.floor(sec % 60)
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
}

function goHero(): void {
  if (hero.value) openDetail(hero.value)
  else router.push('/bangumi')
}

function continueWatch(item: ProgressItem): void {
  router.push(`/play/${encodeURIComponent(item.animeKey)}/${item.episodeNum}`)
}

function goUserCenter(): void {
  router.push('/user/' + (userStore.userId ?? ''))
}

/** 详情跳转：按 _source 判前缀，本地数字 id 不能加 bgm: 前缀 */
function openDetail(anime: AnimeItem): void {
  const raw = String(anime._id ?? anime.id ?? '')
  if (!raw) return
  const pure = raw.replace(/^bgm:/, '')
  if (String(anime._source) === 'bangumi' || !/^\d+$/.test(pure)) {
    router.push('/anime/bgm:' + pure)
  } else {
    router.push('/anime/' + pure)
  }
}
</script>

<style scoped>
.hero {
  position: relative;
  min-height: 320px;
  border-radius: var(--radius-lg);
  overflow: hidden;
  background-color: #1a2030;
  background-size: cover;
  background-position: center;
  margin-bottom: 34px;
  display: flex;
  align-items: center;
}
.hero-mask { position: absolute; inset: 0; background: linear-gradient(180deg, transparent, rgba(15, 17, 23, .9)); }
.hero-body { position: relative; padding: 40px 44px; max-width: 620px; }
.hero-tag {
  display: inline-block; padding: 2px 12px; border-radius: 999px;
  background: var(--c-gradient); color: #fff; font-size: 12px; font-weight: 600; margin-bottom: 14px;
}
.hero-title { font-size: 32px; line-height: 1.25; font-weight: 800; margin-bottom: 12px; }
.hero-desc { color: var(--c-text-dim); font-size: 14px; margin-bottom: 22px; }
.hero-actions { display: flex; gap: 12px; }

.cont-card { width: 240px; padding: 8px; cursor: pointer; display: flex; gap: 10px; }
.cont-cover { position: relative; width: 96px; height: 60px; border-radius: 8px; overflow: hidden; background: #222a38; flex: 0 0 auto; }
.cont-cover img { width: 100%; height: 100%; object-fit: cover; }
.cont-cover-ph { display: flex; align-items: center; justify-content: center; height: 100%; color: #fff; font-weight: 700; }
.cont-ep { position: absolute; left: 4px; bottom: 4px; padding: 0 5px; border-radius: 4px; background: rgba(0,0,0,.65); font-size: 11px; }
.cont-meta { flex: 1; min-width: 0; display: flex; flex-direction: column; justify-content: center; gap: 6px; }
.cont-title { font-size: 13px; font-weight: 600; }
.cont-time { font-size: 11.5px; }

.sk-card { background: var(--c-card); border-radius: var(--radius); overflow: hidden; padding-bottom: 12px; }
.sk-cover { aspect-ratio: 3 / 4; border-radius: 0; }
.sk-line { height: 12px; margin: 10px 10px 0; }
.cal-skeleton { height: 180px; }

.calendar { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); gap: 12px; }
.cal-col { background: var(--c-card); border: 1px solid var(--c-border); border-radius: var(--radius); padding: 10px; min-width: 0; }
.cal-day { font-size: 12.5px; font-weight: 700; color: var(--c-primary); margin-bottom: 8px; text-align: center; }
.cal-list { display: flex; flex-direction: column; gap: 8px; }
.cal-item { display: flex; gap: 7px; align-items: center; cursor: pointer; border-radius: 8px; padding: 3px; }
.cal-item:hover { background: rgba(255, 255, 255, .05); }
.cal-cover { width: 30px; height: 40px; border-radius: 5px; object-fit: cover; flex: 0 0 auto; }
.cal-title { font-size: 12px; }

@media (max-width: 1024px) {
  .hero { min-height: 250px; }
  .hero-body { padding: 26px 22px; }
  .hero-title { font-size: 24px; }
  .calendar { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}
@media (max-width: 768px) {
  .hero { min-height: 210px; border-radius: var(--radius); margin-bottom: 22px; }
  .hero-title { font-size: 20px; }
  .hero-desc { font-size: 13px; margin-bottom: 14px; }
  .calendar { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .cont-card { width: 210px; }
}
</style>
