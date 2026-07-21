import type { MobileTodoAttachment } from '@/api/business'
import type { MediaAsset } from '@/platform'

/** Converts locally selected mini-program evidence into the bounded payload accepted by role-todo APIs. */
export async function todoAttachmentsFromMedia(files: MediaAsset[]): Promise<MobileTodoAttachment[]> {
  return Promise.all(files.slice(0, 3).map(async (file, index) => ({
    fileName: file.name || `处理证据-${index + 1}.jpg`,
    contentType: 'image/jpeg',
    dataBase64: await readBase64(file.path),
  })))
}

function readBase64(filePath: string): Promise<string> {
  // #ifdef MP-WEIXIN
  return new Promise((resolve, reject) => {
    uni.getFileSystemManager().readFile({
      filePath,
      encoding: 'base64',
      success: (result) => resolve(String(result.data || '')),
      fail: () => reject(new Error('处理证据读取失败，请重新选择照片')),
    })
  })
  // #endif
  // #ifndef MP-WEIXIN
  return Promise.reject(new Error('待办证据上传请在微信小程序中完成'))
  // #endif
}
