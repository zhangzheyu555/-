export type MediaSource = 'camera' | 'album' | 'both'
export type MediaKind = 'image' | 'video'

export interface ChooseMediaOptions {
  count?: number
  source?: MediaSource
  kinds?: MediaKind[]
}

export interface MediaAsset {
  path: string
  kind: MediaKind
  name?: string
  size?: number
}

interface VideoResult {
  tempFilePath: string
  size?: number
}

interface ActionSheetResult {
  tapIndex: number
}

interface WechatMediaTempFile {
  tempFilePath: string
  fileType?: MediaKind
  size?: number
}

interface WechatChooseMediaResult {
  tempFiles: WechatMediaTempFile[]
}

interface WechatImageTempFile {
  path?: string
  size?: number
}

interface WechatChooseImageResult {
  tempFilePaths: string[]
  tempFiles?: WechatImageTempFile[]
}

interface WechatMediaApi {
  chooseMedia?: (options: {
    count: number
    mediaType: MediaKind[]
    sourceType: Array<'camera' | 'album'>
    success: (result: WechatChooseMediaResult) => void
    fail: (error: unknown) => void
  }) => void
  chooseImage?: (options: {
    count: number
    sourceType: Array<'camera' | 'album'>
    success: (result: WechatChooseImageResult) => void
    fail: (error: unknown) => void
  }) => void
}

function wechatMediaApi(): WechatMediaApi | undefined {
  if (typeof globalThis === 'undefined') return undefined
  return (globalThis as typeof globalThis & { wx?: WechatMediaApi }).wx
}

function sourceTypes(source: MediaSource): Array<'camera' | 'album'> {
  if (source === 'camera') return ['camera']
  if (source === 'album') return ['album']
  return ['camera', 'album']
}

export async function chooseMedia(options: ChooseMediaOptions = {}): Promise<MediaAsset[]> {
  const source = options.source || 'both'
  const kinds: MediaKind[] = options.kinds?.length ? options.kinds : ['image']
  const count = Math.max(1, Math.min(options.count || 1, 9))

  // #ifdef MP-WEIXIN
  // All current mobile business flows request images only. uni.chooseImage is
  // supported across older and newer WeChat base libraries and avoids calling
  // wx.chooseMedia on devices where that API is unavailable.
  if (kinds.length === 1 && kinds[0] === 'image') return chooseImages(options)

  try {
    const wechat = wechatMediaApi()
    if (wechat && typeof wechat.chooseMedia === 'function') {
      const media = await new Promise<WechatChooseMediaResult>((resolve, reject) => {
        wechat.chooseMedia!({
          count,
          mediaType: kinds,
          sourceType: sourceTypes(source),
          success: resolve,
          fail: reject,
        })
      })
      return media.tempFiles.map((file) => ({
        path: file.tempFilePath,
        kind: file.fileType === 'video' ? 'video' : 'image',
        size: file.size,
      }))
    }
    if (kinds.length === 1 && kinds[0] === 'image') return chooseWechatImages(count, source)
    throw new Error('chooseMedia is not supported')
  } catch (error) {
    if (isSelectionCancelled(error)) return []
    // Some WeChat DevTools and older base-library combinations expose chooseMedia but fail
    // before opening the picker. Image-only flows can safely fall back to chooseImage.
    if (kinds.length === 1 && kinds[0] === 'image') return chooseWechatImages(count, source)
    throw mediaSelectionError(error)
  }
  // #endif

  // #ifdef H5
  if (kinds.length === 1 && kinds[0] === 'video') return chooseSingleVideo(source)
  return chooseUniImages(count, source)
  // #endif

  // #ifdef APP-PLUS
  if (kinds.length === 1 && kinds[0] === 'video') return chooseSingleVideo(source)
  if (kinds.includes('video') && kinds.includes('image')) {
    const selected = await chooseKind()
    return selected === 'video' ? chooseSingleVideo(source) : chooseUniImages(count, source)
  }
  return chooseUniImages(count, source)
  // #endif

  return []
}

export async function chooseImages(options: ChooseMediaOptions = {}): Promise<MediaAsset[]> {
  const source = options.source || 'both'
  const count = Math.max(1, Math.min(options.count || 1, 9))
  return chooseUniImages(count, source)
}

// #ifdef MP-WEIXIN
async function chooseWechatImages(count: number, source: MediaSource): Promise<MediaAsset[]> {
  try {
    const wechat = wechatMediaApi()
    if (!wechat || typeof wechat.chooseImage !== 'function') {
      throw new Error('chooseImage is not supported')
    }
    const result = await new Promise<WechatChooseImageResult>((resolve, reject) => {
      wechat.chooseImage!({
        count,
        sourceType: sourceTypes(source),
        success: resolve,
        fail: reject,
      })
    })
    const paths = Array.isArray(result.tempFilePaths) ? result.tempFilePaths : [result.tempFilePaths]
    const files = Array.isArray(result.tempFiles) ? result.tempFiles : []
    return paths.filter(Boolean).map((path: string, index: number) => ({
      path,
      kind: 'image' as const,
      size: files[index]?.size,
    }))
  } catch (error) {
    if (isSelectionCancelled(error)) return []
    throw mediaSelectionError(error)
  }
}
// #endif

async function chooseUniImages(count: number, source: MediaSource): Promise<MediaAsset[]> {
  try {
    const result = await new Promise<UniApp.ChooseImageSuccessCallbackResult>((resolve, reject) => {
      uni.chooseImage({
        count,
        sourceType: sourceTypes(source),
        success: resolve,
        fail: reject,
      })
    })
    const paths = Array.isArray(result.tempFilePaths) ? result.tempFilePaths : [result.tempFilePaths]
    const files = Array.isArray(result.tempFiles) ? result.tempFiles : [result.tempFiles]
    return paths.filter(Boolean).map((path: string, index: number) => ({
      path,
      kind: 'image' as const,
      size: files[index]?.size,
    }))
  } catch (error) {
    if (isSelectionCancelled(error)) return []
    throw mediaSelectionError(error)
  }
}

async function chooseSingleVideo(source: MediaSource): Promise<MediaAsset[]> {
  const result = await new Promise<VideoResult>((resolve, reject) => {
    uni.chooseVideo({
      sourceType: sourceTypes(source),
      success: (response) => resolve(response as VideoResult),
      fail: reject,
    })
  })
  return [{ path: result.tempFilePath, kind: 'video', size: result.size }]
}

async function chooseKind(): Promise<MediaKind> {
  const result = await new Promise<ActionSheetResult>((resolve, reject) => {
    uni.showActionSheet({ itemList: ['照片', '视频'], success: (response) => resolve(response), fail: reject })
  })
  return result.tapIndex === 1 ? 'video' : 'image'
}

function isSelectionCancelled(error: unknown) {
  return errorText(error).toLowerCase().includes('cancel')
}

function mediaSelectionError(error: unknown) {
  const detail = errorText(error).toLowerCase()
  if (detail.includes('privacy') || detail.includes('declare') || detail.includes('隐私')) {
    return new Error('相机或相册尚未在微信小程序隐私保护指引中声明，请完成配置后重试。')
  }
  if (detail.includes('auth deny') || detail.includes('authorize') || detail.includes('permission')) {
    return new Error('未获得相机或相册权限，请在微信设置中允许后重试。')
  }
  if (detail.includes('not support') || detail.includes('not implemented')) {
    return new Error('当前微信版本暂不支持选择照片，请升级微信后重试。')
  }
  return new Error('无法打开相机或相册，请检查微信权限后重试。')
}

function errorText(error: unknown) {
  const source = error as { errMsg?: string; message?: string } | null
  return String(source?.errMsg || source?.message || '')
}
