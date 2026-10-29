<template>
  <RouterLink :to="detailPath" class="anime-card abc-card" :title="anime.title">
    <div class="cover-wrap">
      <img v-if="cover && !imgFailed" :src="cover" :alt="anime.title" loading="lazy" class="cover" @error="imgFailed = true" />
      <!-- 图片缺失/加载失败时的渐变占位，避免出现浏览器破碎图标 -->
      <div v-else class="cover placeholder">{{ titleChar }}</div>
      <span v-if="scoreText" class="score">★ {{ scoreText }}</span>
      <span v-if="anime._source === 'bangumi'" class="badge-src">远程</span>
    </div>
    <div class="meta">
      <div class="title clamp-2">{{ anime.title }}</div>
      <div class="sub">
        <span v-if="anime.type">{{ anime.type }}</span>
        <span v-if="anime.year" class="weak">{{ anime.year }}</span>
        <span v-if="anime.episodesCount" class="weak">{{ anime.episodesCount }} 话</span>
      </div>
    </div>
  </RouterLink>
</template>

<script setup lang="ts">
/**
 * 番剧卡片（首页/搜索/番剧库/追番共用）。
 * 【关键点】详情页跳转不依赖后端 _id 前缀，而是按 _source 判定：
 * 本地数据 → /anime/{纯数字}；远程 Bangumi → /anime/bgm:{纯数字}。
 * 旧代码（SearchPage.vue goToDetail）一律剥掉前缀再补 bgm:，导致本地命中项 404（已在审计报告中记录）。
 */
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'
import type { AnimeItem } from '@/types'

const props = defineProps<{ anime: AnimeItem }>()

const imgFailed = ref(false)

const cover = computed(() => props.anime.coverUrl || '')
const titleChar = computed(() => (props.anime.title ? Array.from(props.anime.title)[0] : '番'))

const scoreText = computed(() => {
  const raw = props.anime.ratingAvg ?? props.anime.rating_avg
  if (raw === null || raw === undefined || Number(raw) <= 0) return ''
  return Number(raw).toFixed(1)
})

const detailPath = computed(() => {
  const raw = String(props.anime._id ?? props.anime.id ?? '')
  const pure = raw.replace(/^bgm:/, '')
  const source = String(props.anime._source || '')
  if (source === 'bangumi') return '/anime/bgm:' + pure
  // 本地或来源未知但 id 是纯数字 → 本地详情
  if (/^\d+$/.test(pure)) return '/anime/' + pure
  // 兜底：带前缀的远程数据
  return '/anime/bgm:' + pure
})
</script>

<style scoped>
.anime-card { display: block; overflow: hidden; border-radius: var(--radius); }
.cover-wrap { position: relative; aspect-ratio: 3 / 4; overflow: hidden; background: #202634; }
.cover { width: 100%; height: 100%; object-fit: cover; transition: transform .3s ease; }
.anime-card:hover .cover { transform: scale(1.06); }
.cover.placeholder {
  display: flex; align-items: center; justify-content: center;
  font-size: 34px; font-weight: 800; color: #fff;
  background: var(--c-gradient-soft);
}
.score {
  position: absolute; right: 8px; bottom: 8px;
  padding: 1px 8px; border-radius: 999px;
  background: rgba(0, 0, 0, .62);
  color: #ffd76e; font-size: 12px; font-weight: 700;
  backdrop-filter: blur(4px);
}
.badge-src {
  position: absolute; left: 8px; top: 8px;
  padding: 1px 7px; border-radius: 6px;
  background: rgba(79, 172, 254, .22);
  border: 1px solid rgba(79, 172, 254, .5);
  color: #a8e0ff; font-size: 11px;
}
.meta { padding: 10px 11px 12px; }
.title { font-size: 13.5px; font-weight: 600; line-height: 1.45; min-height: 39px; }
.sub { display: flex; gap: 8px; margin-top: 6px; font-size: 11.5px; color: var(--c-text-dim); }
</style>
