<template>
  <div class="app-root">
    <!-- 全局常驻右上角：个人中心入口（登录后显示，登录/注册页除外） -->
    <div class="global-user" v-if="showGlobalUser && userStore.isLoggedIn()">
      <el-dropdown trigger="click" @command="onUserCommand">
        <span class="g-avatar-entry">
          <span class="g-avatar" :class="{ admin: userStore.isAdmin() }">
            {{ avatarChar }}
          </span>
          <span class="g-role" v-if="userStore.isAdmin()">管理</span>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item command="center">👤 个人中心</el-dropdown-item>
            <el-dropdown-item command="admin" v-if="userStore.isAdmin()">🛠️ 管理后台</el-dropdown-item>
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

// 头像字符：取用户名首字（按码点，兼容 emoji）；取不到则用默认图标（避免老缓存 username 为空显示 "?"）
const avatarChar = computed(() => {
  const n = userStore.user.username
  if (!n) return '👤'
  return Array.from(n)[0]
})

const onUserCommand = (cmd: string) => {
  if (cmd === 'center') router.push('/user/' + userStore.user.userId)
  else if (cmd === 'admin') router.push('/admin')
  else if (cmd === 'home') router.push('/')
  else if (cmd === 'logout') userStore.logout().then(() => router.push('/login'))
}
</script>

<style scoped>
.global-user { position: fixed; top: 14px; right: 22px; z-index: 2000; }
.g-avatar-entry { cursor: pointer; outline: none; position: relative; }
.g-avatar {
  display: flex; align-items: center; justify-content: center;
  width: 38px; height: 38px; border-radius: 50%;
  background: var(--primary); color: #fff; font-weight: 600;
  box-shadow: 0 2px 8px rgba(0,0,0,0.18);
  border: 2px solid #fff;
}
.g-avatar:hover { opacity: 0.9; }
.g-avatar.admin { background: linear-gradient(135deg, #f56c6c, #ff8a8a); }
.g-role { position: absolute; top: -2px; right: -2px; background: #f56c6c; color: #fff; font-size: 10px; padding: 1px 5px; border-radius: 8px; font-weight: 600; }
</style>
