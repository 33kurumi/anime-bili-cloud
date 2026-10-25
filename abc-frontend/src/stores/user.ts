import { defineStore } from 'pinia'
import { ref } from 'vue'
import api from '@/api'

interface UserInfo {
  userId: number | null
  username: string
  role: string
  avatar: string
}

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('accessToken') || '')
  const refreshToken = ref(localStorage.getItem('refreshToken') || '')
  const user = ref<UserInfo>((() => {
    // 从 localStorage 恢复用户信息（否则刷新后 role 会丢失）
    try {
      const s = localStorage.getItem('userInfo')
      if (s) return JSON.parse(s)
    } catch { /* ignore */ }
    return { userId: null, username: '', role: 'USER', avatar: '' }
  })())

  const isLoggedIn = () => !!token.value
  const isAdmin = () => user.value.role === 'ADMIN'

  const login = async (username: string, password: string) => {
    const res = await api.post('/api/auth/login', { username, password })
    const data = res.data.data
    token.value = data.accessToken
    refreshToken.value = data.refreshToken
    user.value = {
      userId: data.userId,
      username: data.username,
      role: data.role,
      avatar: ''
    }
    localStorage.setItem('accessToken', data.accessToken)
    localStorage.setItem('refreshToken', data.refreshToken)
    localStorage.setItem('userInfo', JSON.stringify(user.value))
    return data
  }

  const logout = async () => {
    try {
      await api.post('/api/auth/logout')
    } catch { /* ignore */ }
    token.value = ''
    refreshToken.value = ''
    user.value = { userId: null, username: '', role: 'USER', avatar: '' }
    localStorage.removeItem('accessToken')
    localStorage.removeItem('refreshToken')
    localStorage.removeItem('userInfo')
  }

  const register = async (username: string, password: string, email: string) => {
    return api.post('/api/auth/register', { username, password, email })
  }

  return { token, refreshToken, user, isLoggedIn, isAdmin, login, logout, register }
})
