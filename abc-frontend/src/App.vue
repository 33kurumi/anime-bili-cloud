<template>
  <div class="app-root">
    <!-- 全局常驻右上角：个人中心入口（登录后显示，登录/注册页除外） -->
    <div class="global-user" v-if="showGlobalUser && userStore.isLoggedIn()">
      <el-dropdown trigger="click" @command="onUserCommand">
        <span class="g-avatar-entry">
          <span class="g-avatar">{{ (userStore.user.username || '?').charAt(0) }}</span>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="center">👤 个人中心</el-dropdown-item>
            <el-dropdown-item command="home">🏠 首页</el-dropdown-item>
            <el-dropdown-item command="logout" divided>🚪 退出登录</el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
    <router-view />
    <!-- 全局桌宠：番剧小助手 -->
    <PetAssistant v-if="showGlobalUser" />
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'
import PetAssistant from '@/components/PetAssistant.vue'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

// 登录/注册页不显示
const showGlobalUser = computed(() => !['/login', '/register'].includes(route.path))

const onUserCommand = (cmd: string) => {
  if (cmd === 'center') router.push('/user/' + userStore.user.userId)
  else if (cmd === 'home') router.push('/')
  else if (cmd === 'logout') userStore.logout().then(() => router.push('/login'))
}
</script>

<style scoped>
.global-user { position: fixed; top: 14px; right: 22px; z-index: 2000; }
.g-avatar-entry { cursor: pointer; outline: none; }
.g-avatar {
  display: flex; align-items: center; justify-content: center;
  width: 38px; height: 38px; border-radius: 50%;
  background: var(--primary); color: #fff; font-weight: 600;
  box-shadow: 0 2px 8px rgba(0,0,0,0.18);
  border: 2px solid #fff;
}
.g-avatar:hover { opacity: 0.9; }
</style>
