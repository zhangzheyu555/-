import { resolveApiUrl, runtimePlatform } from './runtime'
import { readSessionToken } from './session'

export interface VersionCheckResult {
  platform: ReturnType<typeof runtimePlatform>
  currentVersion: string
  minimumVersion?: string
  updateAvailable: boolean
  forceUpdate?: boolean
  downloadUrl?: string | null
  message: string
}

export async function checkVersion(): Promise<VersionCheckResult> {
  const platform = runtimePlatform()

  // #ifdef MP-WEIXIN
  const updateManager = uni.getUpdateManager()
  updateManager.onCheckForUpdate((result) => {
    if (result.hasUpdate) uni.$emit('ai-profit-mobile:update-available')
  })
  updateManager.onUpdateReady(() => {
    uni.showModal({
      title: '新版本已准备好',
      content: '更新后可继续使用最新功能。',
      confirmText: '立即更新',
      success: (result) => { if (result.confirm) updateManager.applyUpdate() },
    })
  })
  updateManager.onUpdateFailed(() => {
    uni.showToast({ title: '新版本下载失败，请稍后重新打开小程序', icon: 'none', duration: 3000 })
  })
  return {
    platform,
    currentVersion: '由微信托管',
    updateAvailable: false,
    message: '微信会在启动时检查并准备新版本',
  }
  // #endif

  // #ifdef APP-PLUS
  const currentVersion = await appVersionName()
  return queryAppVersion(appOs(), currentVersion).catch(() => ({
    platform,
    currentVersion,
    updateAvailable: false,
    message: '版本服务暂不可用，不影响继续使用',
  }))
  // #endif

  // #ifdef H5
  return {
    platform,
    currentVersion: '0.1.0',
    updateAvailable: false,
    message: 'H5 版本随网页发布更新',
  }
  // #endif

  return { platform, currentVersion: '0.1.0', updateAvailable: false, message: '当前平台无需检查更新' }
}

export async function promptVersionUpdate(result: VersionCheckResult): Promise<void> {
  if (!result.updateAvailable) return

  // #ifdef APP-PLUS
  const downloadUrl = trustedHttpsUrl(result.downloadUrl)
  if (!downloadUrl) {
    uni.showToast({ title: '新版本下载地址尚未配置', icon: 'none' })
    return
  }
  const choice = await new Promise<{ confirm: boolean }>((resolve) => {
    uni.showModal({
      title: result.forceUpdate ? '需要更新后继续使用' : '发现新版本',
      content: result.message || '是否前往下载新版本？',
      confirmText: '前往下载',
      showCancel: !result.forceUpdate,
      success: (response) => resolve({ confirm: Boolean(response.confirm) }),
    })
  })
  if (!choice.confirm) return
  const openUrl = (globalThis as unknown as { plus?: { runtime?: { openURL?: (url: string) => void } } })
    .plus?.runtime?.openURL
  if (openUrl) openUrl(downloadUrl)
  else await uni.setClipboardData({ data: downloadUrl })
  // #endif
}

function trustedHttpsUrl(value: string | null | undefined): string | null {
  const raw = String(value || '').trim()
  if (!/^https:\/\//i.test(raw)) return null
  try {
    const parsed = new URL(raw)
    if (parsed.protocol !== 'https:' || parsed.username || parsed.password || !parsed.hostname) return null
    return parsed.toString()
  } catch {
    return null
  }
}

function appVersionName(): Promise<string> {
  return new Promise((resolve) => {
    const runtime = (globalThis as unknown as {
      plus?: { runtime?: { appid?: string; getProperty?: (id: string, callback: (info: { version?: string }) => void) => void } }
    }).plus?.runtime
    if (!runtime?.appid || !runtime.getProperty) {
      resolve('0.1.0')
      return
    }
    runtime.getProperty(runtime.appid, (info) => resolve(info.version || '0.1.0'))
  })
}

function appOs(): 'android' | 'ios' {
  const osName = String((globalThis as unknown as { plus?: { os?: { name?: string } } }).plus?.os?.name || '')
    .toLowerCase()
  return osName.includes('ios') ? 'ios' : 'android'
}

interface AppVersionPayload {
  currentVersion: string
  minimumVersion: string
  updateAvailable: boolean
  forceUpdate: boolean
  downloadUrl: string | null
  message: string
}

function queryAppVersion(os: 'android' | 'ios', version: string): Promise<VersionCheckResult> {
  const token = readSessionToken()
  if (!token) return Promise.reject(new Error('未登录'))
  const query = `platform=${os}&version=${encodeURIComponent(version)}`
  return new Promise((resolve, reject) => {
    uni.request({
      url: resolveApiUrl(`/api/mobile/version?${query}`),
      method: 'GET',
      header: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
      timeout: 5_000,
      success: (response) => {
        const envelope = response.data as { success?: boolean; data?: AppVersionPayload; message?: string }
        if (response.statusCode < 200 || response.statusCode >= 300 || envelope.success === false || !envelope.data) {
          reject(new Error(envelope.message || '版本服务暂不可用'))
          return
        }
        resolve({
          platform: 'app',
          currentVersion: envelope.data.currentVersion || version,
          minimumVersion: envelope.data.minimumVersion,
          updateAvailable: Boolean(envelope.data.updateAvailable),
          forceUpdate: Boolean(envelope.data.forceUpdate),
          downloadUrl: envelope.data.downloadUrl,
          message: envelope.data.message || '当前已是最新版本',
        })
      },
      fail: reject,
    })
  })
}
