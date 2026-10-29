<template>
  <div class="dm-panel" @click.stop>
    <div class="dm-panel-head">
      <span>弹幕设置</span>
      <span class="weak dm-count">共 {{ total }} 条</span>
    </div>

    <div class="dm-row">
      <label>显示弹幕</label>
      <el-switch v-model="draft.enabled" />
    </div>

    <div class="dm-row">
      <label>不透明度</label>
      <el-slider v-model="draft.opacity" :min="0.2" :max="1" :step="0.05" size="small" class="dm-slider" />
    </div>

    <div class="dm-row">
      <label>字号</label>
      <el-slider v-model="draft.fontSize" :min="14" :max="30" :step="1" size="small" class="dm-slider" />
    </div>

    <div class="dm-row">
      <label>速度</label>
      <el-slider v-model="draft.speed" :min="0.5" :max="2" :step="0.1" size="small" class="dm-slider" />
    </div>

    <div class="dm-row">
      <label>显示区域</label>
      <el-radio-group v-model="draft.displayArea" size="small">
        <el-radio-button :value="0.25">1/4</el-radio-button>
        <el-radio-button :value="0.5">1/2</el-radio-button>
        <el-radio-button :value="0.75">3/4</el-radio-button>
        <el-radio-button :value="1">全屏</el-radio-button>
      </el-radio-group>
    </div>

    <div class="dm-divider">弹幕屏蔽</div>

    <div class="dm-row">
      <label>启用屏蔽</label>
      <el-switch v-model="draft.shieldEnabled" />
    </div>
    <el-input
      v-model="draft.shieldKeywords"
      type="textarea"
      :rows="2"
      size="small"
      placeholder="屏蔽关键词，用逗号或换行分隔"
      :disabled="!draft.shieldEnabled"
    />
    <el-input
      v-model="draft.shieldRegex"
      size="small"
      placeholder="屏蔽正则（可选），如 广告\d+"
      :disabled="!draft.shieldEnabled"
      class="dm-regex"
    />
    <div class="dm-checks">
      <el-checkbox v-model="draft.blockScroll" :disabled="!draft.shieldEnabled">滚动</el-checkbox>
      <el-checkbox v-model="draft.blockTop" :disabled="!draft.shieldEnabled">顶部</el-checkbox>
      <el-checkbox v-model="draft.blockBottom" :disabled="!draft.shieldEnabled">底部</el-checkbox>
    </div>

    <div class="dm-foot">
      <el-button size="small" @click="reset">恢复默认</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 弹幕设置面板。
 * 所有改动实时写回父组件并持久化到 localStorage（key: abc_danmaku_settings），
 * 下次进任意播放页直接沿用——这是"用户可感知的个性化"，也是青空次元/哔哩哔哩的标配能力。
 */
import { reactive, watch } from 'vue'
import { DEFAULT_SETTINGS, saveDanmakuSettings, type DanmakuSettings } from './types'

const props = defineProps<{
  modelValue: DanmakuSettings
  total?: number
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: DanmakuSettings): void
}>()

// 用副本编辑，避免直接改 prop（Vue 单向数据流）
const draft = reactive<DanmakuSettings>({ ...props.modelValue })

watch(
  () => props.modelValue,
  value => Object.assign(draft, value),
  { deep: true }
)

watch(
  draft,
  value => {
    const next: DanmakuSettings = { ...value }
    saveDanmakuSettings(next)
    emit('update:modelValue', next)
  },
  { deep: true }
)

function reset(): void {
  Object.assign(draft, DEFAULT_SETTINGS)
}
</script>

<style scoped>
.dm-panel {
  position: absolute;
  right: 12px;
  bottom: 74px;
  width: 288px;
  padding: 14px 16px;
  border-radius: var(--radius);
  background: rgba(22, 27, 38, .96);
  border: 1px solid var(--c-border);
  box-shadow: var(--shadow-hover);
  backdrop-filter: blur(10px);
  z-index: 20;
  color: var(--c-text);
}
.dm-panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-weight: 600;
  margin-bottom: 10px;
}
.dm-count { font-size: 12px; font-weight: 400; }
.dm-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 34px;
}
.dm-row > label { font-size: 13px; color: var(--c-text-dim); white-space: nowrap; }
.dm-slider { flex: 1; min-width: 120px; }
.dm-divider {
  margin: 10px 0 4px;
  padding-top: 8px;
  border-top: 1px dashed var(--c-border);
  font-size: 12.5px;
  color: var(--c-text-dim);
}
.dm-regex { margin-top: 6px; }
.dm-checks { display: flex; gap: 6px; margin-top: 2px; }
.dm-foot { display: flex; justify-content: flex-end; margin-top: 10px; }
</style>
