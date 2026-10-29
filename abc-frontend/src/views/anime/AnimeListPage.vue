<template>
  <div class="page list-page">
    <div class="section-head">
      <h2 class="section-title">番剧库</h2>
      <span class="weak">共 {{ total }} 部</span>
    </div>

    <!-- 筛选条：类型 / 年份 / 排序，全部走后端分页接口，前端不做全量拉取 -->
    <div class="filters">
      <el-select v-model="query.type" placeholder="全部类型" clearable size="small" class="f-item" @change="reload">
        <el-option v-for="t in TYPES" :key="t" :label="t" :value="t" />
      </el-select>
      <el-select v-model="query.year" placeholder="全部年份" clearable size="small" class="f-item" @change="reload">
        <el-option v-for="y in YEARS" :key="y" :label="String(y)" :value="y" />
      </el-select>
      <el-select v-model="query.orderBy" placeholder="默认排序" size="small" class="f-item" @change="reload">
        <el-option label="默认" value="" />
        <el-option label="评分最高" value="ratingAvg" />
        <el-option label="最新入库" value="id" />
        <el-option label="最多观看" value="viewCount" />
      </el-select>
    </div>

    <div v-if="loading" class="card-grid">
      <div v-for="i in 12" :key="i" class="sk-card">
        <div class="skeleton sk-cover"></div>
        <div class="skeleton sk-line"></div>
      </div>
    </div>
    <div v-else-if="list.length" class="card-grid">
      <AnimeCard v-for="anime in list" :key="String(anime.id ?? anime._id ?? anime.title)" :anime="anime" />
    </div>
    <div v-else class="empty-block">没有符合条件的番剧</div>

    <div v-if="total > query.size" class="pager">
      <el-pagination
        background
        layout="prev, pager, next, total"
        :total="total"
        :page-size="query.size"
        :current-page="query.page"
        @current-change="onPageChange"
      />
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 番剧库页（旧版前端没有这个页面，首页也只是"搜索页无关键词时显示日历"）。
 * 后端 GET /api/anime/list 本来就支持 page/size/type/year/tag/orderBy 且返回 Page 对象，
 * 旧前端只用了 size=100 一把梭 + 前端不分页，这里改成真正的服务端分页。
 */
import { onMounted, reactive, ref } from 'vue'
import AnimeCard from '@/components/AnimeCard.vue'
import { get, type Query } from '@/api'
import type { AnimeItem, PageResult } from '@/types'

const TYPES = ['TV', '剧场版', 'OVA', 'WEB']
const YEARS = Array.from({ length: 12 }, (_, i) => new Date().getFullYear() - i)

const list = ref<AnimeItem[]>([])
const total = ref(0)
const loading = ref(true)

const query = reactive({
  page: 1,
  size: 24,
  type: '',
  year: '' as number | '',
  orderBy: ''
})

onMounted(load)

function reload(): void {
  query.page = 1
  load()
}

function onPageChange(page: number): void {
  query.page = page
  load()
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const params: Query = { page: query.page, size: query.size }
    if (query.type) params.type = query.type
    if (query.year !== '') params.year = query.year
    if (query.orderBy) params.orderBy = query.orderBy
    const data = await get<PageResult<AnimeItem> | AnimeItem[]>('/api/anime/list', params, { silent: true })
    // 兼容两种返回：MyBatis-Plus 的 Page 对象，或裸数组
    if (Array.isArray(data)) {
      list.value = data
      total.value = data.length
    } else {
      list.value = data?.records || []
      total.value = Number(data?.total || 0)
    }
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.filters { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 18px; }
.f-item { width: 130px; }
.sk-card { background: var(--c-card); border-radius: var(--radius); overflow: hidden; padding-bottom: 12px; }
.sk-cover { aspect-ratio: 3 / 4; border-radius: 0; }
.sk-line { height: 12px; margin: 10px 10px 0; }
.pager { display: flex; justify-content: center; margin: 30px 0 10px; }
@media (max-width: 768px) {
  .f-item { width: calc(50% - 5px); }
}
</style>
