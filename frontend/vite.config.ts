import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // 后端地址可通过 VITE_PROXY_TARGET 覆盖，默认本地 8080
  const target = env.VITE_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      port: 5173,
      // 开发环境代理：/api 与 /ws 都转发到后端，前端因此始终是同源请求，
      // 既不需要跨域配置，也不需要把后端地址写进代码
      proxy: {
        '/api': {
          target,
          changeOrigin: true
        },
        '/ws': {
          target,
          changeOrigin: true,
          ws: true
        }
      }
    }
  }
})
