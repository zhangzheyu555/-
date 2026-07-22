import { defineConfig, loadEnv, type Plugin } from 'vite'
import uni from '@dcloudio/vite-plugin-uni'

function disableDCloudShadowPreload(): Plugin {
  return {
    name: 'disable-dcloud-shadow-preload',
    enforce: 'pre',
    transform(code, id) {
      if (!id.includes('@dcloudio/uni-mp-weixin') || !id.endsWith('uni.mp.esm.js')) return null
      const transformed = code.replace(/\n\s*preloadAsset\(\);\n/, '\n')
      return transformed === code ? null : { code: transformed, map: null }
    },
  }
}

function isCandidateOutput(outputDirectory: string | undefined): boolean {
  return /(^|[\\/])dist[\\/]candidate([\\/]|$)/i.test(String(outputDirectory || ''))
}

function assertCandidateApiBaseUrl(apiBaseUrl: string | undefined): void {
  const configured = String(apiBaseUrl || '').trim()
  if (!configured) {
    throw new Error('候选构建缺少 VITE_API_BASE_URL；请通过受控的 staging 环境变量提供 HTTPS 地址。')
  }

  let parsed: URL
  try {
    parsed = new URL(configured)
  } catch {
    throw new Error('候选构建的 VITE_API_BASE_URL 必须是有效的 HTTPS 地址。')
  }

  const host = parsed.hostname.toLowerCase().replace(/\.$/, '')
  const isLoopback = host === 'localhost'
    || host.endsWith('.localhost')
    || host === '127.0.0.1'
    || host === '0.0.0.0'
    || host === '::1'
    || host === '[::1]'
    || /^127(?:\.\d{1,3}){3}$/.test(host)

  if (parsed.protocol !== 'https:' || parsed.username || parsed.password || isLoopback
    || host === 'mobile-api.invalid' || host.endsWith('.invalid')) {
    throw new Error('候选构建的接口地址不符合发布要求；必须使用非本机、非 .invalid、且不含凭据的 HTTPS staging 地址。')
  }
}

export default defineConfig(({ command, mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const proxyTarget = env.VITE_DEV_PROXY_TARGET?.trim()

  // The official candidate scripts write every release output beneath dist/candidate.
  // Keep the same safety boundary in Vite so an ad-hoc npm build cannot embed
  // mobile-api.invalid or a local development endpoint into a candidate artifact.
  if (command === 'build' && isCandidateOutput(process.env.UNI_OUTPUT_DIR)) {
    assertCandidateApiBaseUrl(env.VITE_API_BASE_URL)
  }

  return {
    plugins: [disableDCloudShadowPreload(), uni()],
    server: proxyTarget
      ? {
          proxy: {
            '/api': {
              target: proxyTarget,
              changeOrigin: true,
            },
          },
        }
      : undefined,
  }
})
