import { defineStore } from 'pinia'
import { listAuthorizedStores } from '@/api/organization'
import type { SessionUser, StoreOption } from '@/types/auth'

export const useContextStore = defineStore('mobile-context', {
  state: () => ({
    stores: [] as StoreOption[],
    currentStoreId: '',
    loading: false,
    error: '',
  }),
  getters: {
    currentStore: (state) => state.stores.find((store) => store.id === state.currentStoreId) || null,
    needsStoreSelection: (state) => state.stores.length > 1 && !state.currentStoreId,
  },
  actions: {
    async load(user: SessionUser | null): Promise<void> {
      this.loading = true
      this.error = ''
      try {
        const canReadStores = Boolean(user?.permissions.some((permission) => permission.trim().toLowerCase() === 'store.read'))
        if (!canReadStores) {
          this.stores = user?.boundStoreId
            ? [{
                id: user.boundStoreId,
                code: '',
                name: user.boundStoreName || '本人所属门店',
                brandId: user.brandId || 0,
                brandName: user.brandName || '',
                area: '',
                status: 'ACTIVE',
                supplyWarehouseId: null,
                supplyWarehouseName: null,
              }]
            : []
          this.currentStoreId = this.stores[0]?.id || ''
          return
        }
        const stores = await listAuthorizedStores()
        this.stores = stores.filter((store) => String(store.status || '').toUpperCase() !== 'DISABLED')
        const existing = this.stores.find((store) => store.id === this.currentStoreId)
        const bound = user?.boundStoreId
          ? this.stores.find((store) => store.id === user.boundStoreId)
          : undefined
        if (bound) this.currentStoreId = bound.id
        else if (existing) this.currentStoreId = existing.id
        else if (this.stores.length === 1) this.currentStoreId = this.stores[0]?.id || ''
        else this.currentStoreId = ''
      } catch (error) {
        this.error = error instanceof Error ? error.message : '门店范围加载失败'
        this.stores = []
        this.currentStoreId = ''
      } finally {
        this.loading = false
      }
    },
    selectStore(storeId: string): void {
      if (!this.stores.some((store) => store.id === storeId)) {
        throw new Error('所选门店不在当前账号的数据范围内')
      }
      this.currentStoreId = storeId
    },
    clear(): void {
      this.stores = []
      this.currentStoreId = ''
      this.error = ''
    },
  },
})
