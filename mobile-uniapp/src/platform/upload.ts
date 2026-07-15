import { resolveApiUrl } from './runtime'

export interface PlatformUploadOptions {
  path: string
  filePath: string
  fieldName?: string
  formData?: Record<string, string>
  headers?: Record<string, string>
}

export interface PlatformUploadResult {
  statusCode: number
  data: string
}

export function uploadFile(options: PlatformUploadOptions): Promise<PlatformUploadResult> {
  return new Promise((resolve, reject) => {
    uni.uploadFile({
      url: resolveApiUrl(options.path),
      filePath: options.filePath,
      name: options.fieldName || 'file',
      formData: options.formData,
      header: options.headers,
      success: (response) => resolve({ statusCode: response.statusCode, data: response.data }),
      fail: reject,
    })
  })
}

