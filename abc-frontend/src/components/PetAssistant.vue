<template>
  <div class="pet-root">
    <!-- 对话窗 -->
    <transition name="pop">
      <div class="pet-panel" v-if="open">
        <div class="panel-head">
          <span>🎀 番剧小助手</span>
          <div class="head-actions">
            <span class="clear" @click="clearChat" title="新对话">＋</span>
            <span class="close" @click="open = false">✕</span>
          </div>
        </div>
        <div class="panel-body" ref="bodyRef">
          <div v-if="!messages.length" class="welcome">
            你好呀~ 我是番剧小助手 🎀<br />可以问我「推荐几部像进击的巨人一样的热血番」「最近有什么好看的」～
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

    <!-- 桌宠形象（可拖拽，用 GIF 角色动画） -->
    <div class="pet-avatar" :style="avatarStyle" @mousedown="startDrag" @click="onAvatarClick">
      <img :src="currentGif" class="pet-img" draggable="false" alt="桌宠" />
      <span class="pet-badge" v-if="!open">找我聊番</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, nextTick, reactive, onMounted, onUnmounted } from 'vue'

const open = ref(false)
const sending = ref(false)
const input = ref('')
const messages = ref<{ role: string; content: string; streaming?: boolean }[]>([])
const sessionId = ref<number | null>(null)
const bodyRef = ref<HTMLElement>()

// 待机动画：循环 idle1~idle4（GIF 素材来自 sinlatansen/ameath，MIT 许可）
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

// 拖拽位置（默认右下角）
const pos = reactive({ x: window.innerWidth - 90, y: window.innerHeight - 150 })
const avatarStyle = { left: pos.x + 'px', top: pos.y + 'px' }

let dragging = false
let moved = false
let startX = 0, startY = 0, origX = 0, origY = 0

const startDrag = (e: MouseEvent) => {
  dragging = true; moved = false
  currentGif.value = '/pet/drag.gif'
  startX = e.clientX; startY = e.clientY
  origX = pos.x; origY = pos.y
  document.addEventListener('mousemove', onDrag)
  document.addEventListener('mouseup', endDrag)
}
const onDrag = (e: MouseEvent) => {
  if (!dragging) return
  const dx = e.clientX - startX, dy = e.clientY - startY
  if (Math.abs(dx) > 4 || Math.abs(dy) > 4) moved = true
  pos.x = Math.max(0, Math.min(window.innerWidth - 70, origX + dx))
  pos.y = Math.max(0, Math.min(window.innerHeight - 70, origY + dy))
}
const endDrag = () => {
  dragging = false
  currentGif.value = idleFrames[0]
  document.removeEventListener('mousemove', onDrag)
  document.removeEventListener('mouseup', endDrag)
}
const onAvatarClick = () => { if (!moved) open.value = !open.value }

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
    const resp = await fetch('/api/ai/chat/stream?' + params.toString(), {
      headers: { Authorization: 'Bearer ' + token }
    })
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
          if (data.startsWith('{"sessionId"')) {
            try { sessionId.value = JSON.parse(data).sessionId } catch { /* ignore */ }
          } else if (data.startsWith('###')) {
            // 后端异常信息忽略
          } else {
            aiMsg.content += data
          }
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

onMounted(startIdle)
onUnmounted(() => { if (idleTimer) clearInterval(idleTimer) })
</script>

<style scoped>
.pet-root { position: fixed; z-index: 3000; }
.pet-avatar { position: fixed; width: 72px; height: 72px; cursor: grab; user-select: none; }
.pet-avatar:active { cursor: grabbing; }
.pet-img { width: 72px; height: 72px; object-fit: contain; filter: drop-shadow(0 4px 8px rgba(0,0,0,0.25)); pointer-events: none; }
.pet-badge { position: absolute; bottom: -4px; left: 50%; transform: translateX(-50%); background: #333; color: #fff; font-size: 10px; padding: 1px 6px; border-radius: 8px; white-space: nowrap; }

.pet-panel { position: fixed; right: 20px; bottom: 110px; width: 340px; height: 440px; background: #fff; border-radius: 14px; box-shadow: 0 8px 32px rgba(0,0,0,0.18); display: flex; flex-direction: column; overflow: hidden; }
.panel-head { display: flex; justify-content: space-between; align-items: center; padding: 12px 14px; background: linear-gradient(135deg, #fb7299, #ffb6c1); color: #fff; font-weight: 600; }
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
