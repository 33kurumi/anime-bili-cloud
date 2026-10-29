<template>
  <div class="page fav-page">
    <div class="section-head">
      <h2 class="section-title">我的追番</h2>
      <span class="weak">{{ list.length }} 部</span>
    </div>

    <el-tabs :model-value="String(status)" @tab-change="onTabChange">
      <el-tab-pane label="想看" name="1" />
      <el-tab-pane label="在看" name="2" />
      <el-tab-pane label="看过" name="3" />
      <el-tab-pane label="弃番" name="4" />
    </el-tabs>

    <div v-if="loading" class="card-grid">
      <div v-for="i in 6" :key="i" class="skeleton sk-cover-only"></div>
    </div>
    <div v-else-if="list.length" class="card-grid">
      <div v-for="fav in list" :key="fav.animeKey" class="fav-card abc-card">
        <div class="fav-cover" @click="openDetail(fav)">
          <img v-if="fav.coverUrl" :src="fav.coverUrl" :alt="fav.title" loading="lazy" />
          <div v-else class="fav-ph">番</div>
        </div>
        <div class="fav-body">
          <div class="ellipsis fav-title" @click="openDetail(fav)">{{ fav.title || fav.animeKey }}</div>
          <div class="weak fav-sub">看到第 {{ fav.lastEpisode || 1 }} 话</div>
          <div class="fav-actions">
            <el-button size="small" type="primary" @click="goPlay(fav)">继续观看</el-button>
            <el-button size="small" text @click="remove(fav)">取消追番</el-button>
          </div>
        </div>
      </div>
    </div>
    <el-empty v-else description="还没有追番，去番剧库看看吧">
      <el-button type="primary" @click="router.push('/bangumi')">去番剧库</el-button>
    </el-empty>
  </div>
</template>

<script setup lang="ts">
/**
 * 追番页（旧版完全没有：详情页连"追番"按钮都没有）。
 * 契约：GET /api/user/favorite?status= → FavoriteItem[]；DELETE /api/user/favorite?animeKey=
 * 后端若尚未提供，接口报错时展示空态而不是崩页（任务要求保留 fallback）。
 */
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { del, get } from '@/api'
import type { FavoriteItem } from '@/types'

const router = useRouter()
const list = ref<FavoriteItem[]>([])
const loading = ref(true)
/** 1 想看 / 2 在看 / 3 看过 / 4 弃番（与 user_favorite.status 对齐） */
const status = ref(2)

onMounted(load)

function onTabChange(name: string | number): void {
  status.value = Number(name)
  load()
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const data = await get<FavoriteItem[]>('/api/user/favorite', { status: status.value }, { silent: true })
    list.value = data || []
  } catch {
    list.value = []
  } finally {
    loading.value = false
  }
}

function openDetail(fav: FavoriteItem): void {
  const pure = String(fav.animeKey || '').replace(/^bgm:/, '')
  if (!pure) return
  // animeKey 数字 → 本地番剧；带 bgm: 或非数字 → 远程番剧
  const remote = /^bgm:/.test(String(fav.animeKey)) || !/^\d+$/.test(pure)
  router.push((remote ? '/anime/bgm:' : '/anime/') + pure)
}

function goPlay(fav: FavoriteItem): void {
  router.push(`/play/${encodeURIComponent(fav.animeKey)}/${fav.lastEpisode || 1}`)
}

async function remove(fav: FavoriteItem): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定取消追番《${fav.title || fav.animeKey}》？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  try {
    // 后端是路径参数：DELETE /api/user/favorite/{animeKey}
    await del('/api/user/favorite/' + encodeURIComponent(fav.animeKey))
    ElMessage.success('已取消追番')
    load()
  } catch {
    /* 错误提示由请求层统一处理 */
  }
}
</script>

<style scoped>
.sk-cover-only { aspect-ratio: 3 / 4; border-radius: var(--radius); }
.fav-card { display: flex; gap: 10px; padding: 10px; }
.fav-cover { width: 92px; flex: 0 0 auto; aspect-ratio: 3 / 4; border-radius: 8px; overflow: hidden; cursor: pointer; background: #222a38; }
.fav-cover img { width: 100%; height: 100%; object-fit: cover; }
.fav-ph { display: flex; align-items: center; justify-content: center; height: 100%; font-weight: 700; }
.fav-body { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.fav-title { font-size: 14px; font-weight: 600; cursor: pointer; }
.fav-sub { font-size: 12px; margin: 4px 0 auto; }
.fav-actions { display: flex; align-items: center; margin-top: 8px; }
@media (max-width: 768px) {
  .fav-card { flex-direction: column; }
  .fav-cover { width: 100%; }
}
</style>
