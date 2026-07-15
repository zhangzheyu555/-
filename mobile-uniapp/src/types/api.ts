export interface ApiEnvelope<T> {
  success: boolean
  message?: string
  code?: string
  requestId?: string
  data: T
}

export type QueryValue = string | number | boolean | null | undefined
export type QueryParams = Record<string, QueryValue | QueryValue[]>

export interface RequestOptions {
  headers?: Record<string, string>
  idempotencyKey?: string
  skipAuth?: boolean
  timeout?: number
}

export interface UploadOptions extends RequestOptions {
  formData?: Record<string, string>
}

