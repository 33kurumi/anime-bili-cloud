import axios from 'axios'
import { ElMessage } from 'element-plus'

const api = axios.create({
  baseURL: '',
  timeout: 15000
})

// Request interceptor — attach token
api.interceptors.request.use(config => {
  const token = localStorage.getItem('accessToken')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// Response interceptor — handle errors
api.interceptors.response.use(
  response => response,
  error => {
    if (error.response) {
      const { status, data } = error.response
      if (status === 401) {
        localStorage.removeItem('accessToken')
        localStorage.removeItem('refreshToken')
        if (window.location.pathname !== '/login') {
          window.location.href = '/login'
        }
      } else if (data && data.message) {
        ElMessage.error(data.message)
      }
    }
    return Promise.reject(error)
  }
)

export default api
