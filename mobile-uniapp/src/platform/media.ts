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
  const media = await new Promise<UniApp.ChooseMediaSuccessCallbackResult>((resolve, reject) => {
    uni.chooseMedia({
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
  // #endif

  // #ifdef H5
  if (kinds.length === 1 && kinds[0] === 'video') return chooseSingleVideo(source)
  return chooseImages(count, source)
  // #endif

  // #ifdef APP-PLUS
  if (kinds.length === 1 && kinds[0] === 'video') return chooseSingleVideo(source)
  if (kinds.includes('video') && kinds.includes('image')) {
    const selected = await chooseKind()
    return selected === 'video' ? chooseSingleVideo(source) : chooseImages(count, source)
  }
  return chooseImages(count, source)
  // #endif

  return []
}

async function chooseImages(count: number, source: MediaSource): Promise<MediaAsset[]> {
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
  return paths.map((path: string, index: number) => ({
    path,
    kind: 'image' as const,
    size: files[index]?.size,
  }))
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
