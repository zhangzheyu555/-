import { apiGet } from './client'
import type { StoreOption } from '@/types/auth'

export function listAuthorizedStores(): Promise<StoreOption[]> {
  return apiGet<StoreOption[]>('/api/stores')
}

