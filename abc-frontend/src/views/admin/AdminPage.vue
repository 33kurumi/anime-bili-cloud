<template>
  <div class="admin-page">
    <header class="admin-header">
      <router-link to="/">← 返回首页</router-link>
      <h2>管理后台</h2>
    </header>

    <div class="admin-content">
      <el-tabs>
        <el-tab-pane label="番剧管理">
          <div style="margin-bottom: 16px;">
            <el-button type="primary" @click="showAddAnime = true">添加番剧</el-button>
          </div>
          <el-table :data="animeList" border stripe>
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="title" label="番剧名称" />
            <el-table-column prop="type" label="类型" width="80" />
            <el-table-column prop="year" label="年份" width="60" />
            <el-table-column prop="status" label="状态" width="80" />
            <el-table-column prop="ratingAvg" label="评分" width="60">
              <template #default="{ row }">{{ row.ratingAvg?.toFixed(1) || '-' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="120">
              <template #default="{ row }">
                <el-button text size="small" type="danger" @click="handleDelete(row.id)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="剧集管理">
          <el-select v-model="selectedAnimeId" placeholder="选择番剧" @change="loadEpisodes" style="width: 300px; margin-bottom: 16px;">
            <el-option v-for="a in animeList" :key="a.id" :label="a.title" :value="a.id" />
          </el-select>
          <el-table :data="episodes" border stripe v-if="selectedAnimeId">
            <el-table-column prop="id" label="ID" width="60" />
            <el-table-column prop="episodeNum" label="集数" width="80" />
            <el-table-column prop="title" label="标题" />
            <el-table-column prop="playCount" label="播放量" width="100" />
          </el-table>
        </el-tab-pane>
      </el-tabs>

      <!-- Add Anime Dialog -->
      <el-dialog v-model="showAddAnime" title="添加番剧" width="500px">
        <el-form label-position="top">
          <el-form-item label="番剧名称">
            <el-input v-model="newAnime.title" />
          </el-form-item>
          <el-form-item label="类型">
            <el-select v-model="newAnime.type">
              <el-option label="TV动画" value="TV" />
              <el-option label="剧场版" value="MOVIE" />
              <el-option label="OVA" value="OVA" />
            </el-select>
          </el-form-item>
          <el-form-item label="简介">
            <el-input v-model="newAnime.description" type="textarea" :rows="3" />
          </el-form-item>
          <el-form-item label="封面URL">
            <el-input v-model="newAnime.coverUrl" />
          </el-form-item>
          <el-form-item label="年份">
            <el-input-number v-model="newAnime.year" :min="1900" :max="2030" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="showAddAnime = false">取消</el-button>
          <el-button type="primary" @click="handleAddAnime">确定</el-button>
        </template>
      </el-dialog>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import api from '@/api'
import { ElMessage } from 'element-plus'

const animeList = ref<any[]>([])
const episodes = ref<any[]>([])
const selectedAnimeId = ref<number | null>(null)
const showAddAnime = ref(false)
const newAnime = ref({ title: '', type: 'TV', description: '', coverUrl: '', year: 2025 })

const loadAnime = async () => {
  const res = await api.get('/api/anime/list?size=100')
  animeList.value = res.data.data?.records || []
}

const loadEpisodes = async () => {
  if (!selectedAnimeId.value) return
  const res = await api.get('/api/anime/' + selectedAnimeId.value + '/episodes')
  episodes.value = res.data.data || []
}

const handleAddAnime = async () => {
  try {
    await api.post('/api/anime', newAnime.value)
    ElMessage.success('添加成功')
    showAddAnime.value = false
    loadAnime()
  } catch { /* ignore */ }
}

const handleDelete = async (id: number) => {
  try {
    await api.delete('/api/anime/' + id)
    ElMessage.success('删除成功')
    loadAnime()
  } catch { /* ignore */ }
}

onMounted(() => loadAnime())
</script>

<style scoped>
.admin-header { background: white; padding: 14px 20px; box-shadow: 0 2px 8px rgba(0,0,0,0.06); display: flex; align-items: center; gap: 20px; }
.admin-header a { color: var(--primary); }
.admin-content { max-width: 1100px; margin: 0 auto; padding: 30px 20px; }
</style>
