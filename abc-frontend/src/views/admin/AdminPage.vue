<template>
  <div class="admin-page page">
    <div class="section-head">
      <h2 class="section-title">管理后台</h2>
      <span class="weak">番剧 / 剧集 / 数据源规则 / 规则在线测试 / 弹幕统计</span>
    </div>

    <el-tabs v-model="tab" class="admin-tabs">
      <!-- ─────────── 番剧管理：真实 CRUD ─────────── -->
      <el-tab-pane label="番剧管理" name="anime">
        <div class="toolbar">
          <el-input v-model="keyword" placeholder="按标题搜索" clearable style="width: 220px" @keyup.enter="loadAnime(1)" />
          <el-button type="primary" :icon="Search" @click="loadAnime(1)">搜索</el-button>
          <el-button :icon="Plus" @click="openCreate">新增番剧</el-button>
          <span class="weak">共 {{ animeTotal }} 条</span>
        </div>
        <el-table :data="animeRows" v-loading="animeLoading" stripe style="width: 100%">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column label="封面" width="86">
            <template #default="{ row }">
              <img v-if="row.coverUrl" :src="row.coverUrl" class="cover" loading="lazy" @error="onImgError" />
              <span v-else class="cover cover-ph">无</span>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="180" show-overflow-tooltip />
          <el-table-column prop="type" label="类型" width="86" />
          <el-table-column prop="year" label="年份" width="70" />
          <el-table-column prop="season" label="季度" width="70" />
          <el-table-column prop="episodesCount" label="集数" width="70" />
          <el-table-column prop="ratingAvg" label="评分" width="70" />
          <el-table-column label="操作" width="150" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
              <el-button link type="danger" @click="removeAnime(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-pagination
          v-if="animeTotal > 0"
          class="pager"
          layout="prev, pager, next, total"
          :total="animeTotal"
          :page-size="animeSize"
          :current-page="animePage"
          @current-change="loadAnime"
        />
      </el-tab-pane>

      <!-- ─────────── 剧集管理：后端暂无增删改接口，只读 ─────────── -->
      <el-tab-pane label="剧集管理" name="episodes">
        <div class="toolbar">
          <el-select v-model="epAnimeId" filterable placeholder="选择番剧" style="width: 320px" @change="loadEpisodes">
            <el-option v-for="a in animeOptions" :key="a.id" :label="a.title" :value="a.id" />
          </el-select>
          <el-button :icon="Refresh" @click="loadEpisodes">刷新</el-button>
          <span class="weak">剧集接口为只读（后端未提供剧集增删改），如需编辑请在番剧服务补充接口</span>
        </div>
        <el-table :data="episodeRows" v-loading="epLoading" stripe>
          <el-table-column prop="episodeNum" label="集数" width="90" />
          <el-table-column prop="title" label="标题" min-width="200" />
          <el-table-column prop="duration" label="时长(秒)" width="120" />
        </el-table>
        <div v-if="!episodeRows.length && !epLoading" class="empty-block">该番剧暂无剧集数据</div>
      </el-tab-pane>

      <!-- ─────────── 数据源管理：启停 / 权重 ─────────── -->
      <el-tab-pane label="数据源管理" name="sources">
        <div class="toolbar">
          <el-button :icon="Refresh" @click="loadRules">刷新规则</el-button>
          <span class="weak">共 {{ rules.length }} 条规则，启用 {{ rules.filter(r => Number(r.enabled) === 1).length }} 条</span>
        </div>
        <el-table :data="rules" v-loading="ruleLoading" stripe>
          <el-table-column prop="id" label="ID" width="64" />
          <el-table-column prop="name" label="名称" min-width="150" show-overflow-tooltip />
          <el-table-column prop="baseUrl" label="站点" min-width="170" show-overflow-tooltip />
          <el-table-column prop="searchMode" label="搜索模式" width="110" />
          <el-table-column label="健康" width="100">
            <template #default="{ row }">
              <el-tag :type="healthType(row.healthStatus)" size="small">{{ row.healthStatus || 'UNKNOWN' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="成功率" width="90">
            <template #default="{ row }">{{ row.successRate != null ? Math.round(Number(row.successRate) * 100) + '%' : '-' }}</template>
          </el-table-column>
          <el-table-column label="权重" width="130">
            <template #default="{ row }">
              <el-input-number v-model="row.weight" :min="0" :max="100" size="small" controls-position="right" style="width: 100px" />
            </template>
          </el-table-column>
          <el-table-column label="启用" width="80">
            <template #default="{ row }">
              <el-switch :model-value="Number(row.enabled) === 1" @change="(v: boolean) => toggleRule(row, v)" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="saveRule(row)">保存权重</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!rules.length && !ruleLoading" class="empty-block">暂无采集规则（可在后端 source_rule 表或导入 animeko/kazumi 规则）</div>
      </el-tab-pane>

      <!-- ─────────── 规则在线测试 ─────────── -->
      <el-tab-pane label="规则测试" name="ruletest">
        <div class="test-form">
          <el-select v-model="testRuleId" filterable placeholder="选择规则" style="width: 280px">
            <el-option v-for="r in rules" :key="r.id" :label="r.name" :value="r.id" />
          </el-select>
          <el-input v-model="testKeyword" placeholder="搜索关键词，如：孤独摇滚" style="width: 240px" @keyup.enter="runRuleTest" />
          <el-button type="primary" :loading="testing" @click="runRuleTest">开始测试</el-button>
        </div>
        <div v-if="testResult" class="test-result">
          <div class="test-summary">
            <el-tag :type="testResult.matched ? 'success' : 'danger'">{{ testResult.matched ? '匹配成功' : '未匹配' }}</el-tag>
            <span class="weak">耗时 {{ testResult.costMs ?? '-' }} ms · 命中 {{ (testResult.items || []).length }} 条</span>
          </div>
          <el-table :data="testResult.items || []" stripe size="small">
            <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
            <el-table-column prop="link" label="链接" min-width="260" show-overflow-tooltip />
            <el-table-column prop="episode" label="剧集" width="90" />
          </el-table>
          <div v-if="(testResult.errors || []).length" class="test-errors">
            <p class="weak">错误信息：</p>
            <pre>{{ (testResult.errors || []).join('\n') }}</pre>
          </div>
        </div>
      </el-tab-pane>

      <!-- ─────────── 弹幕统计（无 echarts，用内联 SVG 柱状图） ─────────── -->
      <el-tab-pane label="弹幕统计" name="danmaku">
        <div class="toolbar">
          <el-input v-model="dmAnimeKey" placeholder="animeKey（如 45678 或 bgm:45678）" style="width: 260px" />
          <el-input-number v-model="dmEp" :min="1" :max="999" size="default" />
          <el-button :icon="Refresh" @click="loadHeat">加载弹幕热度</el-button>
          <el-button @click="loadDanmakuStats">全站统计</el-button>
        </div>
        <div v-if="heat.length" class="heat-chart">
          <svg :viewBox="'0 0 ' + heat.length * 10 + ' 100'" preserveAspectRatio="none" class="heat-svg">
            <rect
              v-for="(h, i) in heat"
              :key="i"
              :x="i * 10 + 1"
              :y="100 - h.percent"
              width="8"
              :height="h.percent"
              fill="url(#heatGrad)"
            />
            <defs>
              <linearGradient id="heatGrad" x1="0" y1="1" x2="0" y2="0">
                <stop offset="0%" stop-color="#fb7299" />
                <stop offset="100%" stop-color="#b465da" />
              </linearGradient>
            </defs>
          </svg>
          <p class="weak">共 {{ heat.length }} 个 10 秒分桶，峰值 {{ maxHeatCount }} 条</p>
        </div>
        <div v-else class="empty-block">暂无热度数据（输入 animeKey 与集数后点击加载）</div>
        <pre v-if="statsText" class="stats-json">{{ statsText }}</pre>
      </el-tab-pane>
    </el-tabs>

    <!-- 新增/编辑番剧 -->
    <el-dialog v-model="dialogVisible" :title="editingId ? '编辑番剧' : '新增番剧'" width="520px">
      <el-form :model="form" label-width="86px">
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="类型">
          <el-select v-model="form.type" style="width: 100%">
            <el-option v-for="t in TYPES" :key="t" :label="t" :value="t" />
          </el-select>
        </el-form-item>
        <el-form-item label="年份"><el-input-number v-model="form.year" :min="1990" :max="2100" /></el-form-item>
        <el-form-item label="季度">
          <el-select v-model="form.season" style="width: 100%">
            <el-option v-for="s in SEASONS" :key="s" :label="s" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="集数"><el-input-number v-model="form.episodesCount" :min="0" :max="9999" /></el-form-item>
        <el-form-item label="封面URL"><el-input v-model="form.coverUrl" /></el-form-item>
        <el-form-item label="简介"><el-input v-model="form.description" type="textarea" :rows="4" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
/**
 * 管理后台。
 * 旧版只有「番剧管理」「剧集管理」两个 tab，规则/数据源/测试全无（README 宣称有）。
 * 本次补齐：数据源规则启停与权重、规则在线测试、弹幕热度可视化（内联 SVG，不引入 echarts）。
 * 后端对应接口：
 *  GET/POST/PUT/DELETE /api/anime          番剧 CRUD
 *  GET /api/anime/{id}/episodes            剧集（只读）
 *  GET /api/source/rules、PUT /api/source/rule/{id}   规则
 *  POST /api/source/rule/test（fallback GET /api/source/test/rule/{id}?keyword=）
 *  GET /api/danmaku/heat、GET /api/danmaku/stats
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Refresh, Search } from '@element-plus/icons-vue'
import { del, get, post, put } from '@/api'
import type { AnimeItem, EpisodeItem, SourceRule } from '@/types'

const TYPES = ['TV', '剧场版', 'OVA', 'WEB']
const SEASONS = ['冬', '春', '夏', '秋']

const tab = ref('anime')

// 番剧
const keyword = ref('')
const animeRows = ref<AnimeItem[]>([])
const animeOptions = ref<AnimeItem[]>([])
const animeTotal = ref(0)
const animePage = ref(1)
const animeSize = 10
const animeLoading = ref(false)
const dialogVisible = ref(false)
const editingId = ref<number | null>(null)
const saving = ref(false)
const form = reactive({
  title: '', type: 'TV', year: new Date().getFullYear(), season: '春', episodesCount: 12, coverUrl: '', description: ''
})

// 剧集
const epAnimeId = ref<number | null>(null)
const episodeRows = ref<EpisodeItem[]>([])
const epLoading = ref(false)

// 规则
const rules = ref<SourceRule[]>([])
const ruleLoading = ref(false)

// 规则测试
const testRuleId = ref<number | null>(null)
const testKeyword = ref('')
const testing = ref(false)
const testResult = ref<{ matched?: boolean; items?: Array<{ title?: string; link?: string; episode?: number | string }>; costMs?: number; errors?: string[] } | null>(null)

// 弹幕统计
const dmAnimeKey = ref('')
const dmEp = ref(1)
const heatRaw = ref<Array<{ startTime: number; count: number }>>([])
const statsText = ref('')
const maxHeatCount = computed(() => Math.max(1, ...heatRaw.value.map(h => h.count)))
const heat = computed(() => {
  const max = maxHeatCount.value
  return heatRaw.value.map(h => ({ percent: Math.max(4, Math.round((h.count / max) * 100)) }))
})

onMounted(async () => {
  await Promise.all([loadAnime(1), loadOptions(), loadRules()])
})

function healthType(status?: string): 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'UP') return 'success'
  if (status === 'DEGRADED') return 'warning'
  if (status === 'DOWN') return 'danger'
  return 'info'
}

function onImgError(e: Event): void {
  const img = e.target as HTMLImageElement
  img.style.visibility = 'hidden'
}

async function loadAnime(page = 1): Promise<void> {
  animePage.value = page
  animeLoading.value = true
  try {
    const data = await get<unknown>('/api/anime/list', {
      page, size: animeSize, keyword: keyword.value || undefined
    }, { silent: true })
    const list = extractRows(data)
    animeRows.value = list as AnimeItem[]
    animeTotal.value = extractTotal(data, list.length)
  } catch {
    animeRows.value = []
    ElMessage.error('番剧列表加载失败（需要管理员权限）')
  }
  animeLoading.value = false
}

async function loadOptions(): Promise<void> {
  try {
    const data = await get<unknown>('/api/anime/list', { page: 1, size: 200 }, { silent: true })
    animeOptions.value = extractRows(data) as AnimeItem[]
  } catch {
    animeOptions.value = []
  }
}

/** 后端返回 Page 对象或裸数组都兼容 */
function extractRows(data: unknown): unknown[] {
  if (Array.isArray(data)) return data
  const obj = data as { records?: unknown[]; list?: unknown[]; content?: unknown[] } | null
  return obj?.records || obj?.list || obj?.content || []
}

function extractTotal(data: unknown, fallback: number): number {
  const obj = data as { total?: number } | null
  return typeof obj?.total === 'number' ? obj.total : fallback
}

function openCreate(): void {
  editingId.value = null
  Object.assign(form, { title: '', type: 'TV', year: new Date().getFullYear(), season: '春', episodesCount: 12, coverUrl: '', description: '' })
  dialogVisible.value = true
}

function openEdit(row: AnimeItem): void {
  editingId.value = Number(row.id)
  Object.assign(form, {
    title: row.title || '',
    type: row.type || 'TV',
    year: Number(row.year || new Date().getFullYear()),
    season: row.season || '春',
    episodesCount: Number(row.episodesCount || 0),
    coverUrl: row.coverUrl || '',
    description: row.description || ''
  })
  dialogVisible.value = true
}

async function submitForm(): Promise<void> {
  if (!form.title.trim()) {
    ElMessage.warning('标题不能为空')
    return
  }
  saving.value = true
  try {
    if (editingId.value) {
      await put('/api/anime/' + editingId.value, { ...form })
    } else {
      await post('/api/anime', { ...form })
    }
    ElMessage.success(editingId.value ? '已更新' : '已新增')
    dialogVisible.value = false
    await Promise.all([loadAnime(animePage.value), loadOptions()])
  } catch {
    /* 拦截器已提示 */
  }
  saving.value = false
}

async function removeAnime(row: AnimeItem): Promise<void> {
  try {
    await ElMessageBox.confirm('确认删除「' + row.title + '」？该操作不可恢复。', '删除确认', {
      type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  try {
    await del('/api/anime/' + row.id)
    ElMessage.success('已删除')
    await loadAnime(animePage.value)
  } catch {
    /* 拦截器已提示 */
  }
}

async function loadEpisodes(): Promise<void> {
  if (!epAnimeId.value) return
  epLoading.value = true
  try {
    episodeRows.value = await get<EpisodeItem[]>('/api/anime/' + epAnimeId.value + '/episodes', undefined, { silent: true })
  } catch {
    episodeRows.value = []
  }
  epLoading.value = false
}

async function loadRules(): Promise<void> {
  ruleLoading.value = true
  try {
    rules.value = await get<SourceRule[]>('/api/source/rules', undefined, { silent: true })
  } catch {
    rules.value = []
  }
  ruleLoading.value = false
}

async function toggleRule(row: SourceRule, value: boolean): Promise<void> {
  const next = { ...row, enabled: value ? 1 : 0 }
  try {
    await put('/api/source/rule/' + row.id, next)
    row.enabled = next.enabled
    ElMessage.success((next.enabled ? '已启用：' : '已禁用：') + row.name)
  } catch {
    /* 拦截器已提示 */
  }
}

async function saveRule(row: SourceRule): Promise<void> {
  try {
    await put('/api/source/rule/' + row.id, { ...row })
    ElMessage.success('已保存权重：' + row.name)
  } catch {
    /* 拦截器已提示 */
  }
}

async function runRuleTest(): Promise<void> {
  if (!testRuleId.value) {
    ElMessage.warning('请先选择规则')
    return
  }
  if (!testKeyword.value.trim()) {
    ElMessage.warning('请输入搜索关键词')
    return
  }
  testing.value = true
  testResult.value = null
  try {
    // TODO(后端契约): POST /api/source/rule/test 为新增接口，未上线时自动回退下面的旧接口
    // GET /api/source/test/rule/{id}?keyword=，两套返回结构都按 matched/items/costMs/errors 归一化展示。
    // 新接口：POST /api/source/rule/test
    testResult.value = await post('/api/source/rule/test', {
      ruleId: testRuleId.value,
      keyword: testKeyword.value.trim()
    }, { silent: true })
  } catch {
    // 兼容旧接口：GET /api/source/test/rule/{id}?keyword=
    try {
      const data = await get<{
        matched?: boolean
        items?: Array<{ title?: string; link?: string; episode?: number | string }>
        costMs?: number
        errors?: string[]
      }>(
        '/api/source/test/rule/' + testRuleId.value,
        { keyword: testKeyword.value.trim() },
        { silent: true }
      )
      testResult.value = data
    } catch {
      ElMessage.error('规则测试接口不可用')
    }
  }
  testing.value = false
}

async function loadHeat(): Promise<void> {
  if (!dmAnimeKey.value.trim()) {
    ElMessage.warning('请输入 animeKey')
    return
  }
  try {
    const rows = await get<Array<{ startTime: number; count: number }>>(
      '/api/danmaku/heat',
      { animeKey: dmAnimeKey.value.trim(), ep: dmEp.value },
      { silent: true }
    )
    heatRaw.value = (rows || []).slice().sort((a, b) => a.startTime - b.startTime)
  } catch {
    heatRaw.value = []
    ElMessage.error('弹幕热度加载失败')
  }
}

async function loadDanmakuStats(): Promise<void> {
  try {
    const data = await get<unknown>('/api/danmaku/stats', undefined, { silent: true })
    statsText.value = JSON.stringify(data, null, 2)
  } catch {
    statsText.value = ''
    ElMessage.error('弹幕统计接口不可用')
  }
}
</script>

<style scoped>
.admin-page { padding-top: 20px; }
.admin-tabs { margin-top: 12px; }
.toolbar { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
.cover { width: 46px; height: 62px; object-fit: cover; border-radius: 6px; display: block; }
.cover-ph { display: flex; align-items: center; justify-content: center; font-size: 11px; color: var(--c-text-dim); background: var(--c-bg-soft); }
.pager { margin-top: 14px; justify-content: flex-end; }
.test-form { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 14px; }
.test-summary { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.test-errors pre { white-space: pre-wrap; font-size: 12px; color: #ff9d9d; background: rgba(255, 0, 0, .06); padding: 10px; border-radius: 8px; }
.heat-chart { padding: 10px 0; }
.heat-svg { width: 100%; height: 180px; background: var(--c-bg-soft); border-radius: 10px; }
.stats-json { margin-top: 14px; max-height: 300px; overflow: auto; font-size: 12px; background: var(--c-bg-soft); padding: 12px; border-radius: 10px; }
@media (max-width: 768px) {
  .toolbar { gap: 8px; }
  .admin-tabs :deep(.el-table) { font-size: 12px; }
}
</style>
