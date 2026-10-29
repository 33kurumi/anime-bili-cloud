import axios, { AxiosError, type AxiosInstance, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResult } from '@/types'

/**
 * 统一请求层。
 *
 * 【重构要点 1】401 不再 window.location.href 硬跳转。
 * 旧实现（审计报告 02 第 6 节）一旦任意接口 401 就把整页刷新到 /login，导致：
 *   - 匿名用户点首页番剧卡 → 详情接口 401 → 直接被打断浏览（与"匿名可看"的产品目标冲突）；
 *   - 后台轮询类请求 401 会把正在填写的表单冲掉。
 * 现在改为：清 token → 通知上层（router 注册的 onUnauthorized 回调）按需跳转 → 不刷新页面。
 *
 * 【重构要点 2】提供泛型 get/post/put/del 封装，页面只关心业务类型，
 * 不再每处写 res.data.data（旧代码里有 30+ 处重复解包，容易漏判空）。
 */

const TOKEN_KEY = 'accessToken'
const REFRESH_KEY = 'refreshToken'
const USER_KEY = 'userInfo'

/** token 读写集中在此，避免 localStorage 的 key 散落各文件 */
export const tokenStore = {
  get(): string {
    return localStorage.getItem(TOKEN_KEY) || ''
  },
  getRefresh(): string {
    return localStorage.getItem(REFRESH_KEY) || ''
  },
  set(accessToken: string, refreshToken?: string): void {
    localStorage.setItem(TOKEN_KEY, accessToken)
    if (refreshToken) localStorage.setItem(REFRESH_KEY, refreshToken)
  },
  clear(): void {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(REFRESH_KEY)
    localStorage.removeItem(USER_KEY)
  }
}

/** 401 处理器（由 router 注入，避免 api 层反向依赖 store/router 造成循环依赖） */
type UnauthorizedHandler = () => void
let unauthorizedHandler: UnauthorizedHandler | null = null
export function registerUnauthorizedHandler(handler: UnauthorizedHandler): void {
  unauthorizedHandler = handler
}

/** 扩展 axios 配置：silent=true 时不弹全局错误提示（轮询/埋点/可选接口用） */
export interface RequestOptions {
  silent?: boolean
}

const api: AxiosInstance = axios.create({
  baseURL: '',
  timeout: 20000
})

// 请求拦截：自动附加 Bearer token
api.interceptors.request.use(config => {
  const token = tokenStore.get()
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// 响应拦截：统一错误提示 + 401 处理
api.interceptors.response.use(
  response => response,
  (error: AxiosError<ApiResult>) => {
    const status = error.response?.status
    const cfg = error.config as (AxiosRequestConfig & RequestOptions) | undefined
    const silent = cfg?.silent === true

    if (status === 401) {
      // 凭据已失效：清掉本地登录态，交给上层决定是否跳登录（不刷新页面）
      tokenStore.clear()
      unauthorizedHandler?.()
      if (!silent) ElMessage.warning('登录状态已失效，请重新登录')
    } else if (status === 403) {
      if (!silent) ElMessage.error('没有权限执行该操作')
    } else if (error.code === 'ECONNABORTED') {
      if (!silent) ElMessage.error('请求超时，请稍后重试')
    } else if (!error.response) {
      if (!silent) ElMessage.error('网络异常，请确认后端服务已启动')
    } else {
      const message = error.response.data?.message || `请求失败（HTTP ${status}）`
      if (!silent) ElMessage.error(message)
    }
    return Promise.reject(error)
  }
)

/** 判断是否 401 错误（页面据此展示"登录后播放/登录后查看"而不是报错） */
export function isUnauthorized(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 401
}

/** 通用请求：解开后端 R<T> 包装，返回 data 本体 */
export async function request<T>(config: AxiosRequestConfig & RequestOptions): Promise<T> {
  const response = await api.request<ApiResult<T> | T>(config)
  const body = response.data
  if (body !== null && typeof body === 'object' && 'code' in (body as Record<string, unknown>)) {
    const wrapped = body as ApiResult<T>
    if (wrapped.code !== 200) {
      // 后端约定异常也用 HTTP 200 + 非 200 code 表示时在这里兜住
      ElMessage.error(wrapped.message || '请求失败')
      throw new Error(wrapped.message || '请求失败')
    }
    return wrapped.data
  }
  return body as T
}

export type Query = Record<string, string | number | boolean | null | undefined>

export function get<T>(url: string, params?: Query, options?: RequestOptions): Promise<T> {
  return request<T>({ url, method: 'GET', params, ...options })
}

export function post<T>(url: string, data?: unknown, options?: RequestOptions): Promise<T> {
  return request<T>({ url, method: 'POST', data, ...options })
}

export function put<T>(url: string, data?: unknown, options?: RequestOptions): Promise<T> {
  return request<T>({ url, method: 'PUT', data, ...options })
}

export function del<T>(url: string, params?: Query, options?: RequestOptions): Promise<T> {
  return request<T>({ url, method: 'DELETE', params, ...options })
}

/** 保留默认导出：历史页面（stores/user.ts 等）仍在使用 axios 实例风格 */
export default api
