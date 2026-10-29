<template>
  <div class="app-root">
    <router-view />
    <!-- 全局桌宠 / AI 助手：登录页、注册页、全屏播放页不显示，避免遮挡 -->
    <PetAssistant v-if="showPet" />
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { useUserStore } from '@/stores/user'
import PetAssistant from '@/components/PetAssistant.vue'

const route = useRoute()
const userStore = useUserStore()

// 登录/注册/播放页不显示桌宠
const showPet = computed(() => !['/login', '/register'].includes(route.path) && !route.path.startsWith('/play/'))

onMounted(() => {
  // 刷新后从 localStorage 兜底水合（token 可能在其它标签页被清掉）
  userStore.hydrate()
})
</script>

<style>
.app-root { min-height: 100vh; }
</style>
