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
  const backendTarget = env.VITE_BACKEND_PROXY_TARGET || 'http://127.0.0.1:8080'
  return {
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
