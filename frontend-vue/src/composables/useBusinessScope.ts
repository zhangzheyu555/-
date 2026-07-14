import { computed } from 'vue'
import { useAuthStore } from '../stores/auth'

export function useBusinessScope() {
  const auth = useAuthStore()
  const isBoss = computed(() => auth.role === 'BOSS')
  const isStoreManager = computed(() => auth.role === 'STORE_MANAGER')
  const boundStoreId = computed(() => auth.user?.boundStoreId || '')
  const boundStoreName = computed(() => auth.user?.boundStoreName || '')
  const brandId = computed(() => auth.user?.brandId ?? null)
  const brandName = computed(() => auth.user?.brandName || '')
  const storeScopeIds = computed(() => {
    const source = auth.user?.dataScope?.storeIds?.length
      ? auth.user.dataScope.storeIds
      : auth.user?.dataScopes?.STORE?.storeIds?.length
        ? auth.user.dataScopes.STORE.storeIds
        : auth.user?.storeScope || []
    return Array.from(new Set(source.filter((storeId) => storeId && storeId !== 'all')))
  })

  const configurationError = computed(() => {
    if (!isStoreManager.value) return ''
    if (storeScopeIds.value.length !== 1) {
      return storeScopeIds.value.length
        ? '店长账号配置了多个门店，请联系老板调整为唯一绑定门店。'
        : '店长账号尚未绑定门店，请联系老板完成账号配置。'
    }
    if (!boundStoreId.value || storeScopeIds.value[0] !== boundStoreId.value) {
      return '店长账号的绑定门店与数据范围不一致，请联系老板重新保存账号权限。'
    }
    if (!boundStoreName.value || !brandId.value || !brandName.value) {
      return '当前门店缺少名称或品牌信息，请联系老板补全门店资料。'
    }
    if (auth.user?.dataScope?.mode !== 'OWN_STORE') {
      return '店长账号的数据范围配置错误，请联系老板设置为本店范围。'
    }
    return ''
  })

  const managerScopeLabel = computed(() => {
    if (!boundStoreName.value) return ''
    return brandName.value ? `${boundStoreName.value} · ${brandName.value}` : boundStoreName.value
  })

  function scopedStoreId(requestedStoreId = '') {
    return isStoreManager.value ? boundStoreId.value : requestedStoreId
  }

  function scopedBrandId(requestedBrandId: string | number | null | undefined = '') {
    return isStoreManager.value ? (brandId.value == null ? '' : String(brandId.value)) : String(requestedBrandId ?? '')
  }

  return {
    auth,
    isBoss,
    isStoreManager,
    boundStoreId,
    boundStoreName,
    brandId,
    brandName,
    storeScopeIds,
    managerScopeLabel,
    configurationError,
    scopedStoreId,
    scopedBrandId,
  }
}
