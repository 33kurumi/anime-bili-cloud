<template>
  <div class="layout">
    <!-- ============ 顶部导航（青空次元式：品牌 + 主导航 + 搜索 + 用户） ============ -->
    <header class="nav">
      <div class="nav-inner">
        <RouterLink to="/" class="brand">
          <span class="brand-mark">ABC</span>
          <span class="brand-text">Anime</span>
        </RouterLink>

        <nav class="nav-links" :class="{ open: mobileOpen }">
          <RouterLink v-for="item in navItems" :key="item.path" :to="item.path" class="nav-link"
            :class="{ active: isActive(item.path) }" @click="mobileOpen = false">
            {{ item.label }}
          </RouterLink>
        </nav>

        <div class="nav-search">
          <el-input v-model="keyword" placeholder="搜索番剧…" clearable size="default" @keyup.enter="doSearch">
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </div>

        <div class="nav-right">
          <el-dropdown v-if="userStore.isLoggedIn()" trigger="click" @command="onUserCommand">
            <span class="avatar-entry">
              <span class="avatar" :class="{ admin: userStore.isAdmin() }">{{ avatarChar }}</span>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="center">个人中心</el-dropdown-item>
                <el-dropdown-item command="favorites">我的追番</el-dropdown-item>
                <el-dropdown-item v-if="userStore.isAdmin()" command="admin">管理后台</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <template v-else>
            <el-button size="small" @click="router.push('/login')">登录</el-button>
            <el-button size="small" type="primary" @click="router.push('/register')">注册</el-button>
          </template>

          <!-- 移动端菜单开关 -->
          <button class="menu-btn" aria-label="打开菜单" @click="mobileOpen = !mobileOpen">☰</button>
        </div>
      </div>
    </header>

    <main class="main">
      <slot />
    </main>

    <footer class="foot">
      <span>ABC Anime · 番剧聚合与弹幕播放（毕业设计）</span>
      <span class="weak">Spring Cloud 微服务 + Vue 3 · 数据来自 Bangumi / 本地库</span>
    </footer>
  </div>
</template>

<script setup lang="ts">
/**
 * 全局外壳布局。
 *
 * 【为什么单独抽这个组件】
 * 旧版把"右上角头像"直接 fixed 在 App.vue 里，每个页面各自实现导航，导致：
 * 首页一套、搜索页一套、播放页没有导航，移动端完全没有导航入口。
 * 这里统一为「顶部导航 + 内容 slot + 页脚」，所有页面共享同一套导航与搜索框。
 */
import { computed, ref } from 'vue'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const keyword = ref('')
const mobileOpen = ref(false)

const navItems = computed(() => {
  const items = [
    { path: '/', label: '首页' },
    { path: '/bangumi', label: '番剧库' },
    { path: '/favorites', label: '我的追番' },
    { path: '/search', label: '搜索' }
  ]
  if (userStore.isAdmin()) items.push({ path: '/admin', label: '管理后台' })
  return items
})

const avatarChar = computed(() => {
  const name = userStore.user.username
  return name ? Array.from(name)[0] : '👤'
})

/** 导航高亮：前缀匹配（/anime/1 时"首页"不该亮） */
function isActive(path: string): boolean {
  if (path === '/') return route.path === '/'
  return route.path.startsWith(path)
}

function doSearch(): void {
  const k = keyword.value.trim()
  if (!k) return
  router.push({ path: '/search', query: { keyword: k } })
  mobileOpen.value = false
}

function onUserCommand(cmd: string): void {
  if (cmd === 'center') router.push('/user/' + (userStore.user.userId ?? ''))
  else if (cmd === 'favorites') router.push('/favorites')
  else if (cmd === 'admin') router.push('/admin')
  else if (cmd === 'logout') userStore.logout().then(() => router.push('/login'))
}
</script>

<style scoped>
.layout { display: flex; flex-direction: column; min-height: 100vh; }

.nav {
  position: sticky;
  top: 0;
  z-index: 1000;
  background: rgba(15, 17, 23, .86);
  backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--c-border);
}
.nav-inner {
  max-width: var(--page-max);
  margin: 0 auto;
  height: var(--header-h);
  padding: 0 20px;
  display: flex;
  align-items: center;
  gap: 22px;
}
.brand { display: flex; align-items: baseline; gap: 6px; font-weight: 800; }
.brand-mark {
  font-size: 22px;
  letter-spacing: 1px;
  background: var(--c-gradient);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.brand-text { font-size: 15px; color: var(--c-text-dim); font-weight: 600; }

.nav-links { display: flex; align-items: center; gap: 4px; }
.nav-link {
  padding: 7px 13px;
  border-radius: 999px;
  color: var(--c-text-dim);
  font-weight: 500;
  transition: all .15s ease;
  white-space: nowrap;
}
.nav-link:hover { color: var(--c-text); background: rgba(255, 255, 255, .05); }
.nav-link.active { color: #fff; background: var(--c-gradient); box-shadow: 0 4px 14px rgba(251, 114, 153, .35); }

.nav-search { flex: 1; max-width: 320px; margin-left: auto; }
.nav-right { display: flex; align-items: center; gap: 8px; }
.avatar-entry { cursor: pointer; outline: none; }
.avatar {
  display: flex; align-items: center; justify-content: center;
  width: 36px; height: 36px; border-radius: 50%;
  background: var(--c-gradient); color: #fff; font-weight: 700;
  border: 2px solid #fff2;
}
.avatar.admin { background: linear-gradient(135deg, #f56c6c, #ff8a8a); }
.menu-btn {
  display: none;
  background: transparent;
  border: 1px solid var(--c-border);
  color: var(--c-text);
  border-radius: 8px;
  padding: 4px 10px;
  font-size: 16px;
  cursor: pointer;
}

.main { flex: 1; }
.foot {
  border-top: 1px solid var(--c-border);
  padding: 20px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px 18px;
  justify-content: center;
  color: var(--c-text-weak);
  font-size: 12.5px;
}

/* 移动端：导航折叠为下拉面板 */
@media (max-width: 768px) {
  .nav-inner { height: 56px; padding: 0 12px; gap: 10px; }
  .nav-search { display: none; }
  .menu-btn { display: block; }
  .nav-links {
    display: none;
    position: absolute;
    top: 56px;
    left: 0;
    right: 0;
    flex-direction: column;
    align-items: stretch;
    gap: 2px;
    padding: 10px 12px 14px;
    background: var(--c-bg-soft);
    border-bottom: 1px solid var(--c-border);
  }
  .nav-links.open { display: flex; }
  .nav-link { padding: 11px 14px; border-radius: 10px; }
}
</style>
