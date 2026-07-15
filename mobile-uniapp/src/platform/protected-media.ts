import { expireSession, readSessionToken } from './session'
import { resolveApiUrl } from './runtime'
import type { ApiEnvelope } from '../types/api'

export interface PreparedMedia {
  url: string
  headers?: Record<string, string>
  expiresAt?: string
  revoke?: () => void
}

interface PlaybackTicketResponse {
  playbackPath: string
  expiresAt: string
}

function authorizationHeader(): Record<string, string> {
  const token = readSessionToken()
  if (!token) throw new Error('请先登录后再播放培训视频')
  return { Authorization: `Bearer ${token}` }
}

export async function prepareProtectedMedia(videoId: number): Promise<PreparedMedia> {
  if (!Number.isSafeInteger(videoId) || videoId <= 0) throw mediaError('视频信息无效，请刷新后重试')
  const contentPath = `/api/exam-center/videos/${videoId}/content`

  // The App video component can attach Authorization to its native range requests.
  // #ifdef APP-PLUS
  return { url: resolveApiUrl(contentPath), headers: authorizationHeader() }
  // #endif

  // H5 and MP-WEIXIN video elements cannot reliably attach a Bearer header. Exchange the current
  // authenticated session for a short-lived, video-scoped URL; the primary token never enters it.
  // #ifndef APP-PLUS
  const response = await requestPlaybackTicket(videoId)
  const expectedPrefix = `/api/exam-center/videos/${videoId}/stream?ticket=`
  if (!response.playbackPath.startsWith(expectedPrefix) || response.playbackPath.includes('#')) {
    throw mediaError('视频播放地址无效，请稍后重试')
  }
  return { url: resolveApiUrl(response.playbackPath), expiresAt: response.expiresAt }
  // #endif

  throw mediaError('当前平台暂不支持受保护媒体播放')
}

function requestPlaybackTicket(videoId: number): Promise<PlaybackTicketResponse> {
  return new Promise((resolve, reject) => {
    uni.request({
      url: resolveApiUrl(`/api/exam-center/videos/${videoId}/playback-ticket`),
      method: 'POST',
      header: authorizationHeader(),
      success: (response) => {
        if (response.statusCode === 401) {
          expireSession()
          reject(mediaError('登录已失效，请重新登录', 401))
          return
        }
        const payload = response.data as ApiEnvelope<PlaybackTicketResponse>
        if (response.statusCode >= 200 && response.statusCode < 300 && payload?.success && payload.data) {
          resolve(payload.data)
          return
        }
        reject(mediaError(
          response.statusCode === 403 ? '当前账号无权播放该视频' : '视频加载失败，请稍后重试',
          response.statusCode,
        ))
      },
      fail: () => reject(mediaError('网络连接失败，请检查网络后重试')),
    })
  })
}

function mediaError(message: string, status?: number): Error & { status?: number } {
  const error = new Error(message) as Error & { status?: number }
  error.status = status
  return error
}
