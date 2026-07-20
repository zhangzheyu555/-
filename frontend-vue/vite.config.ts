import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import type { ServerResponse } from 'node:http'

function backendProxy(target: string) {
  return {
    target,
    changeOrigin: true,
    configure(proxy: { on: (event: 'error', handler: (error: Error, request: unknown, response: unknown) => void) => void }) {
      proxy.on('error', (_error, _request, response) => {
        const serverResponse = response as ServerResponse
        if (!serverResponse || typeof serverResponse.writeHead !== 'function' || serverResponse.writableEnded) return
        if (!serverResponse.headersSent) {
          serverResponse.writeHead(503, { 'Content-Type': 'application/json; charset=utf-8' })
        }
        serverResponse.end(JSON.stringify({
          success: false,
          code: 'BACKEND_UNAVAILABLE',
          message: '暂时无法连接服务器',
          data: null,
        }))
      })
    },
  }
}

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  // 本项目本机后端的受控运行端口为 18081。仍允许维护环境用
  // VITE_BACKEND_PROXY_TARGET 覆盖，避免未加载 .env.local 时悄悄转发到旧的 8080。
  const backendTarget = env.VITE_BACKEND_PROXY_TARGET || 'http://127.0.0.1:18081'
  return {
    // The gateway mounts the desktop app at /admin/.  Keep Vite's asset base
    // aligned with Vue Router's BASE_URL so a deployed page never renders an
    // unmatched blank RouterView at that mount point.
    base: env.VITE_PUBLIC_BASE || '/',
    plugins: [vue()],
    server: {
      host: '127.0.0.1',
      port: 5173,
      proxy: {
        '/api': backendProxy(backendTarget),
        '/train-img': backendProxy(backendTarget),
      },
    },
  }
})
