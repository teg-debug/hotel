/**
 * axios 实例：baseURL /api/v1，响应拦截器已解包 Result.data。
 *
 * 后端现在按错误语义返回对应的 HTTP 状态码（400/401/403/404/409/422/500），
 * 因此这里在错误分支中优先透出响应体里的 msg，调用方拿到的 Error 也带有该消息。
 */
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'
import router from '@/router'

const request = axios.create({
  baseURL: '/api/v1',
  timeout: 15000
})

// 请求拦截：携带 JWT
request.interceptors.request.use((config) => {
  const store = useUserStore()
  if (store.token) {
    config.headers.Authorization = `Bearer ${store.token}`
  }
  return config
})

// 响应拦截：code=200 直接返回 data；其余统一提示并抛出带消息的错误
request.interceptors.response.use(
  (response) => {
    const res = response.data
    if (res.code === 200) {
      return res.data
    }
    const msg = res.msg || '请求失败'
    ElMessage.error(msg)
    return Promise.reject(new Error(msg))
  },
  (error) => {
    const status = error.response?.status
    const msg = error.response?.data?.msg
    if (status === 401) {
      const store = useUserStore()
      // 客服会话必须与登录态一起清理，否则同一标签页换账号后会复用上一个账号的会话。
      // 这里用动态导入而不是顶层 import，避免 api 层与 store 层形成循环依赖。
      void import('@/stores/chat')
        .then(({ useChatStore }) => useChatStore().reset())
        .catch(() => {
          // 清理失败不影响登出流程
        })
      store.logout()
      ElMessage.error(msg || '登录已失效，请重新登录')
      router.push('/login')
    } else {
      ElMessage.error(msg || error.message || '网络异常')
    }
    return Promise.reject(new Error(msg || error.message || '网络异常'))
  }
)

export default request
