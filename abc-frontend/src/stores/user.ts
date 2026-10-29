import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import api, { tokenStore } from '@/api'

export interface UserInfo {
  userId: number | null
  username: string
  role: string
  avatar: string
}

const EMPTY_USER: UserInfo = { userId: null, username: '', role: 'USER', avatar: '' }

function readUserFromStorage(): UserInfo {
  try {
    const raw = localStorage.getItem('userInfo')
    if (raw) {
      const parsed = JSON.parse(raw) as Partial<UserInfo>
      return { ...EMPTY_USER, ...parsed }
    }
  } catch {
    /* 损坏的本地缓存直接忽略 */
  }
  return { ...EMPTY_USER }
}

export const useUserStore = defineStore('user', () => {
  const token = ref(tokenStore.get())
  const refreshToken = ref(tokenStore.getRefresh())
  const user = ref<UserInfo>(readUserFromStorage())

  /** 是否已登录：统一用函数形式，保持与旧页面 userStore.isLoggedIn() 的调用兼容 */
  const isLoggedIn = (): boolean => !!token.value
  const isAdmin = (): boolean => String(user.value.role).toUpperCase() === 'ADMIN'
  const displayName = computed(() => user.value.username || '游客')
  const userId = computed(() => user.value.userId)

  /** 外部写入用户信息（登录后、资料编辑后） */
  function setUser(info: Partial<UserInfo>): void {
    user.value = { ...user.value, ...info }
    localStorage.setItem('userInfo', JSON.stringify(user.value))
  }

  /** 401 后清理本地登录态（由 request 层的 onUnauthorized 调用） */
  function clearAuth(): void {
    token.value = ''
    refreshToken.value = ''
    user.value = { ...EMPTY_USER }
    tokenStore.clear()
  }

  /** 从 localStorage 重新水合（跨标签页同步 / 路由守卫兜底） */
  function hydrate(): void {
    token.value = tokenStore.get()
    refreshToken.value = tokenStore.getRefresh()
    user.value = readUserFromStorage()
  }

  async function login(username: string, password: string): Promise<UserInfo> {
    const res = await api.post('/api/auth/login', { username, password })
    const data = res.data.data as {
      accessToken: string
      refreshToken: string
      userId: number
      username: string
      role: string
    }
    token.value = data.accessToken
    refreshToken.value = data.refreshToken
    tokenStore.set(data.accessToken, data.refreshToken)
    setUser({ userId: data.userId, username: data.username, role: data.role || 'USER' })
    ElMessage.success('登录成功')
    return user.value
  }

  async function logout(): Promise<void> {
    try {
      await api.post('/api/auth/logout', undefined, { silent: true } as never)
    } catch {
      /* 退出接口失败也要清本地态，保证用户能退出 */
    }
    clearAuth()
  }

  async function register(username: string, password: string, email: string): Promise<void> {
    await api.post('/api/auth/register', { username, password, email })
  }

  return {
    token,
    refreshToken,
    user,
    userId,
    displayName,
    isLoggedIn,
    isAdmin,
    setUser,
    clearAuth,
    hydrate,
    login,
    logout,
    register
  }
})
