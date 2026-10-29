import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { registerUnauthorizedHandler } from '@/api'
import AppLayout from '@/components/AppLayout.vue'

/**
 * 路由表。
 *
 * 【结构变化】除登录/注册/播放页外，所有页面都挂在 AppLayout 下成为子路由：
 * 这样"顶部导航/搜索框/页脚"只渲染一次，切换页面不会整块重建（旧版每个页面自己实现导航）。
 *
 * 【鉴权策略】公开页面（首页/搜索/详情/番剧库）允许匿名浏览（meta.requireAuth 缺省 = 公开）；
 * 只有"追番、个人中心、管理后台"要求登录，管理后台额外要求 ADMIN 角色。
 * 这与产品定位一致：游客可以先看再决定注册。
 */
const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: AppLayout,
    children: [
      { path: '', name: 'home', component: () => import('@/views/home/HomePage.vue'), meta: { title: '首页' } },
      { path: 'bangumi', name: 'bangumi', component: () => import('@/views/anime/AnimeListPage.vue'), meta: { title: '番剧库' } },
      {
        path: 'favorites',
        name: 'favorites',
        component: () => import('@/views/user/FavoritesPage.vue'),
        meta: { title: '我的追番', requireAuth: true }
      },
      { path: 'search', name: 'search', component: () => import('@/views/search/SearchPage.vue'), meta: { title: '搜索' } },
      { path: 'anime/:id', name: 'anime-detail', component: () => import('@/views/anime/AnimeDetailPage.vue'), meta: { title: '番剧详情' } },
      {
        path: 'user/:id?',
        name: 'user-center',
        component: () => import('@/views/user/UserCenterPage.vue'),
        meta: { title: '个人中心', requireAuth: true }
      },
      {
        path: 'admin',
        name: 'admin',
        component: () => import('@/views/admin/AdminPage.vue'),
        meta: { title: '管理后台', requireAuth: true, requireAdmin: true }
      }
    ]
  },
  // 播放页不带顶栏：沉浸式播放（青空次元也是全屏播放页）
  {
    path: '/play/:animeKey/:episodeNum',
    name: 'play',
    component: () => import('@/views/play/PlayPage.vue'),
    meta: { title: '播放' }
  },
  { path: '/login', name: 'login', component: () => import('@/views/login/LoginPage.vue'), meta: { title: '登录' } },
  { path: '/register', name: 'register', component: () => import('@/views/login/RegisterPage.vue'), meta: { title: '注册' } },
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach(to => {
  document.title = (to.meta.title as string) ? `${to.meta.title as string} · ABC Anime` : 'ABC Anime'
  const userStore = useUserStore()

  if (to.meta.requireAuth && !userStore.isLoggedIn()) {
    ElMessage.warning('请先登录')
    // 带 redirect，登录成功后回到原页面
    return { path: '/login', query: { redirect: to.fullPath } }
  }
  if (to.meta.requireAdmin) {
    if (!userStore.isLoggedIn()) {
      ElMessage.warning('请先登录')
      return { path: '/login', query: { redirect: to.fullPath } }
    }
    if (!userStore.isAdmin()) {
      ElMessage.warning('需要管理员权限')
      return '/'
    }
  }
  // 已登录用户访问登录/注册页 → 回首页（或 redirect 目标）
  if ((to.path === '/login' || to.path === '/register') && userStore.isLoggedIn()) {
    const redirect = typeof to.query.redirect === 'string' ? to.query.redirect : '/'
    return redirect
  }
  return true
})

/**
 * 401 统一处理：清本地登录态；仅当"当前页面本身需要登录"时才跳登录页。
 * 公开页面收到 401（比如游客点播放）不跳转，由页面自己给出"登录后可播放"的引导，避免打断浏览。
 */
registerUnauthorizedHandler(() => {
  const userStore = useUserStore()
  userStore.clearAuth()
  const current = router.currentRoute.value
  if (current.meta.requireAuth) {
    router.push({ path: '/login', query: { redirect: current.fullPath } })
  }
})

export default router
