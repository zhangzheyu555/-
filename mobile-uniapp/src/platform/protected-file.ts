import { resolveApiUrl } from './runtime'
import { expireSession, readSessionToken } from './session'

export interface ProtectedFileOptions {
  path: string
  fileName?: string
  contentType?: string
}

export function downloadProtectedFile(path: string): Promise<string> {
  const token = readSessionToken()
  if (!token) return Promise.reject(fileError('请先登录后再查看附件', 401))
  return new Promise((resolve, reject) => {
    uni.downloadFile({
      url: resolveApiUrl(path),
      header: { Authorization: `Bearer ${token}` },
      timeout: 120_000,
      success: (response) => {
        if (response.statusCode === 401) { expireSession(); reject(fileError('登录已失效，请重新登录', 401)); return }
        if (response.statusCode === 403) { reject(fileError('当前账号无权查看该附件', 403)); return }
        if (response.statusCode < 200 || response.statusCode >= 300 || !response.tempFilePath) {
          reject(fileError('附件下载失败，请稍后重试', response.statusCode)); return
        }
        resolve(response.tempFilePath)
      },
      fail: () => reject(fileError('网络连接失败，请检查网络后重试')),
    })
  })
}

export async function openProtectedFile(options: ProtectedFileOptions): Promise<void> {
  const tempFilePath = await downloadProtectedFile(options.path)
  if (isImage(options)) {
    await new Promise<void>((resolve, reject) => uni.previewImage({ urls: [tempFilePath], current: tempFilePath, success: () => resolve(), fail: () => reject(fileError('图片预览失败')) }))
    return
  }
  const fileType = extension(options.fileName)
  await new Promise<void>((resolve, reject) => uni.openDocument({
    filePath: tempFilePath,
    fileType: fileType || undefined,
    showMenu: true,
    success: () => resolve(),
    fail: () => reject(fileError('文件无法打开，请使用微信菜单保存后查看')),
  }))
}

function isImage(options: ProtectedFileOptions) {
  return String(options.contentType || '').toLowerCase().startsWith('image/') || ['jpg','jpeg','png','gif','webp'].includes(extension(options.fileName))
}
function extension(fileName?: string) { return String(fileName || '').split('.').pop()?.toLowerCase() || '' }
function fileError(message: string, status?: number) { const error = new Error(message) as Error & { status?: number }; error.status = status; return error }
