import { isSameBrand, normalizeBrandName } from './brand'

export interface StoreBrandLike {
  id?: string | number
  storeId?: string | number
  brandId?: string | number
  brandName?: string
  brandCode?: string
}

export function filterStoresByBrand<T extends StoreBrandLike>(stores: T[], brandIdOrName?: string | number | null): T[] {
  const selected = String(brandIdOrName || '').trim()
  if (!selected || selected === '全部品牌' || selected === 'all') return stores
  return stores.filter((store) => {
    if (store.brandId !== undefined && store.brandId !== null && String(store.brandId) === selected) return true
    if (store.brandCode && isSameBrand(store.brandCode, selected)) return true
    return isSameBrand(normalizeBrandName(store.brandName || ''), selected)
  })
}

export function isStoreInBrand(store: StoreBrandLike | undefined, brandIdOrName?: string | number | null) {
  if (!store) return false
  return filterStoresByBrand([store], brandIdOrName).length > 0
}
