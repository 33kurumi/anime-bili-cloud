import { createRouter, createWebHistory } from 'vue-router'
import { useUserStore } from '@/stores/user'
import { ElMessage } from 'element-plus'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      component: () => import('@/views/home/HomePage.vue'),
      meta: { title: '首页' }
    },
    {
      path: '/login',
      component: () => import('@/views/login/LoginPage.vue'),
      meta: { title: '登录' }
    },
    {
      path: '/register',
      component: () => import('@/views/login/RegisterPage.vue'),
      meta: { title: '注册' }
    },
    {
      path: '/search',
      component: () => import('@/views/search/SearchPage.vue'),
      meta: { title: '搜索' }
    },
    {
      path: '/anime/:id',
      component: () => import('@/views/anime/AnimeDetailPage.vue'),
      meta: { title: '番剧详情' }
    },
    {
      // animeKey: 统一番剧标识，远程为 "bgm:123"，本地为纯数字 id
      // episodeNum: 第几集（远程/本地都有，稳定）
      path: '/play/:animeKey/:episodeNum',
      component: () => import('@/views/play/PlayPage.vue'),
      meta: { title: '播放' }
    },
    {
      path: '/user/:id?',
      component: () => import('@/views/user/UserCenterPage.vue'),
      meta: { title: '个人中心' }
    },
    {
      path: '/admin',
      component: () => import('@/views/admin/AdminPage.vue'),
      meta: { title: '管理后台', requireAdmin: true }
    }
  ]
})

router.beforeEach((to, _from, next) => {
  document.title = (to.meta.title as string) || 'Anime Bilibili Cloud'
  // 管理后台：仅管理员可进
  if (to.meta.requireAdmin) {
    const userStore = useUserStore()
    if (!userStore.isLoggedIn() || !userStore.isAdmin()) {
      ElMessage.warning('需要管理员权限')
      next('/')
      return
    }
  }
  next()
})

export default router
