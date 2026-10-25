<template>
  <div class="pet-root">
    <!-- 聊天窗（可拖拽，默认在桌宠左上方，不遮挡桌宠） -->
    <transition name="pop">
      <div class="pet-panel" v-if="open" :style="panelStyle">
        <div class="panel-head" @mousedown="startPanelDrag">
          <span>🎀 {{ petName }}</span>
          <div class="head-actions">
            <span class="clear" @click.stop="clearChat" title="新对话">＋</span>
            <span class="close" @click.stop="open = false">✕</span>
          </div>
        </div>
        <div class="panel-body" ref="bodyRef">
          <div v-if="!messages.length" class="welcome">
            你好呀~ 我是{{ petName }} 🎀<br />可以问我「推荐几部像进击的巨人一样的热血番」「最近有什么好看的」～
          </div>
          <div v-for="(m, i) in messages" :key="i" :class="['msg', m.role]">
            <div class="bubble">{{ m.content }}<span class="cursor" v-if="m.streaming">▋</span></div>
          </div>
        </div>
        <div class="panel-foot">
          <input v-model="input" @keyup.enter="send" placeholder="说点什么…" :disabled="sending" />
          <button @click="send" :disabled="sending || !input.trim()">发送</button>
        </div>
      </div>
    </transition>

    <!-- 右键小菜单 -->
    <div class="ctx-menu" v-if="menuOpen" :style="menuStyle" @mousedown.stop>
      <div class="ctx-model">🤖 当前模型：{{ modelInfo }}</div>
      <div class="ctx-item" @click="toggleLock">{{ locked ? '🔓 取消固定' : '📌 固定位置' }}</div>
      <div class="ctx-item" @click="toggleResize">{{ resizeMode ? '✔ 完成调整' : '🔧 调整大小' }}</div>
      <div class="ctx-item" @click="resetPosition">↩️ 重置位置</div>
      <div class="ctx-item" @click="menuOpen = false">✖ 关闭菜单</div>
    </div>

    <!-- 桌宠形象（可拖拽 / 右键菜单 / 调整大小） -->
    <div
      class="pet-avatar"
      :style="avatarStyle"
      @mousedown="startDrag"
      @click="onAvatarClick"
      @contextmenu.prevent="openMenu"
    >
      <img :src="currentGif" class="pet-img" :style="imgStyle" draggable="false" alt="桌宠" />
      <span class="pet-badge" v-if="!open && !menuOpen">找我聊番</span>
      <span class="pet-lock" v-if="locked">📌</span>
      <!-- 调整大小手柄：进入调整模式后出现，拖它缩放 -->
      <span v-if="resizeMode" class="resize-handle" @mousedown.stop="startResize"></span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, reactive, onMounted, onUnmounted, computed } from 'vue'

const petName = '飞行雪绒'
const open = ref(false)
const menuOpen = ref(false)
const sending = ref(false)
const input = ref('')
const messages = ref<{ role: string; content: string; streaming?: boolean }[]>([])
const sessionId = ref<number | null>(null)
const bodyRef = ref<HTMLElement>()

// 缩放 & 固定
const petScale = ref(1.0)
const locked = ref(false)
const resizeMode = ref(false)   // 调整大小模式：显示手柄，拖手柄缩放
const BASE = 72
const imgStyle = computed(() => ({ width: (BASE * petScale.value) + 'px', height: (BASE * petScale.value) + 'px' }))

// ─── 缩放手柄拖拽 ───
let rResizing = false, rStartX = 0, rStartY = 0, rStartScale = 1
const startResize = (e: MouseEvent) => {
  e.preventDefault()
  rResizing = true
  rStartX = e.clientX; rStartY = e.clientY; rStartScale = petScale.value
  document.addEventListener('mousemove', onResize)
  document.addEventListener('mouseup', endResize)
}
const onResize = (e: MouseEvent) => {
  if (!rResizing) return
  const d = Math.max(e.clientX - rStartX, e.clientY - rStartY)   // 右下拖远=放大
  petScale.value = Math.max(0.6, Math.min(2.2, +(rStartScale + d / 90).toFixed(2)))
}
const endResize = () => { rResizing = false; document.removeEventListener('mousemove', onResize); document.removeEventListener('mouseup', endResize) }

// 待机动画（GIF 素材来自 sinlatansen/ameath，MIT 许可）
const idleFrames = ['/pet/idle1.gif', '/pet/idle2.gif', '/pet/idle3.gif', '/pet/idle4.gif']
const currentGif = ref(idleFrames[0])
let idleTimer: number | undefined
const startIdle = () => {
  let i = 0
  idleTimer = window.setInterval(() => {
    if (dragging) return
    i = (i + 1) % idleFrames.length
    currentGif.value = idleFrames[i]
  }, 2500)
}

// 位置
const pos = reactive({ x: window.innerWidth - 90, y: window.innerHeight - 150 })
const avatarStyle = computed(() => ({ left: pos.x + 'px', top: pos.y + 'px', width: (BASE * petScale.value) + 'px', height: (BASE * petScale.value) + 'px' }))

// 聊天框位置（默认在桌宠左上，不遮挡）
const PANEL_W = 340, PANEL_H = 440
const panelPos = reactive({ x: Math.max(20, window.innerWidth - 90 - PANEL_W - 20), y: Math.max(20, window.innerHeight - 150 - PANEL_H - 10), set: false })
const panelStyle = computed(() => ({ left: panelPos.x + 'px', top: panelPos.y + 'px' }))
const repositionPanel = () => {
  // 放在桌宠左侧；若左边放不下则放上方
  let x = pos.x - PANEL_W - 12
  if (x < 10) x = Math.min(window.innerWidth - PANEL_W - 10, pos.x)
  let y = pos.y + BASE * petScale.value - PANEL_H
  if (y < 10) y = Math.max(10, pos.y - PANEL_H - 12)
  panelPos.x = Math.max(10, x)
  panelPos.y = Math.max(10, Math.min(window.innerHeight - PANEL_H - 10, y))
}

// 菜单位置
const menuStyle = computed(() => ({ left: (pos.x + BASE * petScale.value + 6) + 'px', top: pos.y + 'px' }))

// ─── 桌宠拖拽 ───
let dragging = false, moved = false, startX = 0, startY = 0, origX = 0, origY = 0
const startDrag = (e: MouseEvent) => {
  if (e.button !== 0) return
  if (locked.value) return
  dragging = true; moved = false
  currentGif.value = '/pet/drag.gif'
  startX = e.clientX; startY = e.clientY; origX = pos.x; origY = pos.y
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', endDrag)
}
const onDrag = (e: MouseEvent) => {
  if (!dragging) return
  const dx = e.clientX - startX, dy = e.clientY - startY
  if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved = true
  const size = BASE * petScale.value
  pos.x = Math.max(0, Math.min(window.innerWidth - size, origX + dx))
  pos.y = Math.max(0, Math.min(window.innerHeight - size, origY + dy))
}
const endDrag = () => {
  dragging = false
  currentGif.value = idleFrames[0]
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', endDrag)
}
const onAvatarClick = () => { if (!moved) { open.value = !open.value; if (open.value) repositionPanel() } }

// ─── 聊天框拖拽（拖标题栏）───
let pDragging = false, pStartX = 0, pStartY = 0, pOrigX = 0, pOrigY = 0
const startPanelDrag = (e: MouseEvent) => {
  if (e.target && (e.target as HTMLElement).closest('.head-actions')) return
  pDragging = true
  pStartX = e.clientX; pStartY = e.clientY; pOrigX = panelPos.x; pOrigY = panelPos.y
  document.addEventListener('mousemove', onPanelDrag)
  document.addEventListener('mouseup', endPanelDrag)
}
const onPanelDrag = (e: MouseEvent) => {
  if (!pDragging) return
  panelPos.x = Math.max(0, Math.min(window.innerWidth - 60, pOrigX + e.clientX - pStartX))
  panelPos.y = Math.max(0, Math.min(window.innerHeight - 40, pOrigY + e.clientY - pStartY))
}
const endPanelDrag = () => { pDragging = false; document.removeEventListener('mousemove', onPanelDrag); document.removeEventListener('mouseup', endPanelDrag) }

// ─── 右键菜单 ───
const openMenu = () => { menuOpen.value = true; open.value = false; loadModel() }
const modelInfo = ref('加载中…')
const loadModel = async () => {
  try {
    const token = localStorage.getItem('accessToken') || ''
    const res = await fetch('/api/ai/model', { headers: { Authorization: 'Bearer ' + token } })
    const j = await res.json()
    modelInfo.value = j?.data?.model || '未知'
  } catch { modelInfo.value = '未知' }
}
const toggleLock = () => { locked.value = !locked.value; menuOpen.value = false }
const toggleResize = () => { resizeMode.value = !resizeMode.value; menuOpen.value = false }
const resetPosition = () => { pos.x = window.innerWidth - 90; pos.y = window.innerHeight - 150; menuOpen.value = false }
const closeMenu = () => { menuOpen.value = false }

const scrollBottom = async () => { await nextTick(); if (bodyRef.value) bodyRef.value.scrollTop = bodyRef.value.scrollHeight }
const clearChat = () => { messages.value = []; sessionId.value = null }

const send = async () => {
  const text = input.value.trim()
  if (!text || sending.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: text })
  const aiMsg = { role: 'assistant', content: '', streaming: true }
  messages.value.push(aiMsg)
  sending.value = true
  await scrollBottom()
  try {
    const token = localStorage.getItem('accessToken') || ''
    const params = new URLSearchParams({ message: text })
    if (sessionId.value) params.set('sessionId', String(sessionId.value))
    const resp = await fetch('/api/ai/chat/stream?' + params.toString(), { headers: { Authorization: 'Bearer ' + token } })
    const reader = resp.body!.getReader()
    const decoder = new TextDecoder()
    let buf = ''
    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      const parts = buf.split('\n')
      buf = parts.pop() || ''
      for (const line of parts) {
        if (line.startsWith('data:')) {
          const data = line.slice(5).trim()
          if (!data) continue
          if (data.startsWith('{"sessionId"')) { try { sessionId.value = JSON.parse(data).sessionId } catch { /* ignore */ } }
          else if (!data.startsWith('###')) aiMsg.content += data
          await scrollBottom()
        }
      }
    }
  } catch (e: any) {
    aiMsg.content += '\n[连接失败：' + (e?.message || '') + ']'
  }
  aiMsg.streaming = false
  sending.value = false
  await scrollBottom()
}

const onGlobalClick = () => { if (menuOpen.value) closeMenu() }
onMounted(() => { startIdle(); document.addEventListener('click', onGlobalClick) })
onUnmounted(() => { if (idleTimer) clearInterval(idleTimer); document.removeEventListener('click', onGlobalClick) })
</script>

<style scoped>
.pet-root { position: fixed; z-index: 3000; }
.pet-avatar { position: fixed; cursor: grab; user-select: none; }
.pet-avatar:active { cursor: grabbing; }
.pet-img { object-fit: contain; filter: drop-shadow(0 4px 8px rgba(0,0,0,0.25)); pointer-events: none; }
.pet-badge { position: absolute; bottom: -4px; left: 50%; transform: translateX(-50%); background: #333; color: #fff; font-size: 10px; padding: 1px 6px; border-radius: 8px; white-space: nowrap; }
.pet-lock { position: absolute; top: -6px; right: -4px; font-size: 14px; }
.resize-handle { position: absolute; right: -6px; bottom: -6px; width: 16px; height: 16px; border-radius: 50%; background: #fb7299; border: 2px solid #fff; cursor: nwse-resize; box-shadow: 0 1px 4px rgba(0,0,0,0.3); }

.ctx-menu { position: fixed; background: #fff; border-radius: 8px; box-shadow: 0 4px 16px rgba(0,0,0,0.2); padding: 6px; z-index: 3100; min-width: 160px; }
.ctx-model { padding: 6px 12px; font-size: 12px; color: #999; border-bottom: 1px solid #f0f0f0; margin-bottom: 4px; white-space: nowrap; }
.ctx-item { padding: 7px 12px; font-size: 13px; cursor: pointer; border-radius: 6px; white-space: nowrap; }
.ctx-item:hover { background: #f2f3f5; }

.pet-panel { position: fixed; width: 340px; height: 440px; background: #fff; border-radius: 14px; box-shadow: 0 8px 32px rgba(0,0,0,0.18); display: flex; flex-direction: column; overflow: hidden; z-index: 3050; }
.panel-head { display: flex; justify-content: space-between; align-items: center; padding: 12px 14px; background: linear-gradient(135deg, #fb7299, #ffb6c1); color: #fff; font-weight: 600; cursor: move; user-select: none; }
.head-actions { display: flex; gap: 12px; }
.head-actions .clear, .head-actions .close { cursor: pointer; font-size: 16px; }
.panel-body { flex: 1; overflow-y: auto; padding: 12px; background: #f7f8fa; }
.welcome { color: #999; font-size: 13px; line-height: 1.8; padding: 20px 10px; text-align: center; }
.msg { display: flex; margin-bottom: 10px; }
.msg.user { justify-content: flex-end; }
.msg.assistant { justify-content: flex-start; }
.bubble { max-width: 78%; padding: 8px 12px; border-radius: 10px; font-size: 14px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; }
.msg.user .bubble { background: #fb7299; color: #fff; }
.msg.assistant .bubble { background: #fff; color: #333; border: 1px solid #eee; }
.cursor { animation: blink 1s infinite; }
@keyframes blink { 50% { opacity: 0; } }
.panel-foot { display: flex; gap: 8px; padding: 10px; border-top: 1px solid #eee; }
.panel-foot input { flex: 1; border: 1px solid #ddd; border-radius: 8px; padding: 8px 10px; outline: none; font-size: 14px; }
.panel-foot button { background: #fb7299; color: #fff; border: none; border-radius: 8px; padding: 0 16px; cursor: pointer; }
.panel-foot button:disabled { opacity: 0.5; cursor: not-allowed; }
.pop-enter-active, .pop-leave-active { transition: all .2s; }
.pop-enter-from, .pop-leave-to { opacity: 0; transform: translateY(20px); }
</style>
