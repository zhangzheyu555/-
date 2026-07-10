import { defineStore } from 'pinia'
import {
  createWarehouseRequisition,
  deleteWarehouseItemCategory,
  downloadWarehousePdf,
  getWarehouseReturns,
  getWarehouseItemCategories,
  getWarehouseOverview,
  receiveWarehouseRequisition,
  receiveWarehouseReturn,
  receiveWarehouseStock,
  reviewWarehouseRequisition,
  reviewWarehouseReturn,
  shipWarehouseRequisition,
  saveWarehouseItem,
  saveWarehouseItemCategory,
  setWarehouseItemEnabled,
  updateWarehouseAlertSettings,
  type WarehouseItemCategory,
  type WarehouseItemPayload,
  type WarehouseOverview,
  type WarehouseReturnOrder,
} from '../api/warehouse'

export type WarehouseTab = 'overview' | 'requisitions' | 'inventory' | 'purchase' | 'catalog' | 'alerts' | 'returns' | 'movements' | 'receipts' | 'prints'

export const useWarehouseStore = defineStore('warehouse', {
  state: () => ({
    overview: null as WarehouseOverview | null,
    categories: [] as WarehouseItemCategory[],
    returns: [] as WarehouseReturnOrder[],
    activeTab: 'overview' as WarehouseTab,
    selectedCategory: 'all',
    loading: false,
    categoryLoading: false,
    submitting: false,
    receivingId: '',
    actioningId: '',
    downloadingId: '',
    error: '',
    actionMessage: '',
  }),
  actions: {
    async loadOverview() {
      this.loading = true
      this.error = ''
      try {
        this.overview = await getWarehouseOverview()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '仓库数据加载失败'
        throw error
      } finally {
        this.loading = false
      }
    },
    async loadCategories() {
      this.categoryLoading = true
      try {
        this.categories = await getWarehouseItemCategories()
        if (!this.categoryExists(this.selectedCategory)) {
          this.selectedCategory = 'all'
        }
      } catch (error) {
        this.error = error instanceof Error ? error.message : '物料分类加载失败'
        throw error
      } finally {
        this.categoryLoading = false
      }
    },
    async loadReturns() {
      try {
        this.returns = await getWarehouseReturns()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '配送退货单加载失败'
        throw error
      }
    },
    async loadAll() {
      await Promise.all([this.loadOverview(), this.loadCategories(), this.loadReturns()])
    },
    setTab(tab: string | null | undefined) {
      const normalized = String(tab || 'overview') as WarehouseTab
      this.activeTab = ['overview', 'requisitions', 'inventory', 'purchase', 'catalog', 'alerts', 'returns', 'movements', 'receipts', 'prints'].includes(normalized)
        ? normalized
        : 'overview'
    },
    setCategory(category: string) {
      this.selectedCategory = category || 'all'
    },
    categoryExists(category: string) {
      if (category === 'all') return true
      if (category.startsWith('name:')) return true
      const id = Number(category.replace(/^id:/, ''))
      const stack = [...this.categories]
      while (stack.length) {
        const current = stack.shift()
        if (!current) continue
        if (current.id === id) return true
        stack.push(...(current.children || []))
      }
      return false
    },
    async submitRequisition(
      lines: Array<{ itemId: number; requestedQuantity: number; note?: string }>,
      note?: string,
      clientRequestId?: string,
    ) {
      this.submitting = true
      this.actionMessage = ''
      try {
        await createWarehouseRequisition({
          lines,
          note,
          clientRequestId,
        })
        this.actionMessage = '叫货单已提交'
        await this.loadOverview()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '叫货提交失败'
        throw error
      } finally {
        this.submitting = false
      }
    },
    async receiveRequisition(requisitionId: string, note?: string) {
      this.receivingId = requisitionId
      this.actionMessage = ''
      try {
        await receiveWarehouseRequisition(requisitionId, note)
        this.actionMessage = '已确认收货'
        await this.loadOverview()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '确认收货失败'
        throw error
      } finally {
        this.receivingId = ''
      }
    },
    async approveRequisition(requisitionId: string) {
      const requisition = this.overview?.requisitions.find((row) => row.id === requisitionId)
      if (!requisition) throw new Error('叫货单不存在')
      await this.runAction(requisitionId, async () => {
        await reviewWarehouseRequisition(requisitionId, {
          approved: true,
          lines: requisition.lines.map((line) => ({
            itemId: line.itemId,
            approvedQuantity: Number(line.approvedQuantity || line.requestedQuantity || 0),
          })),
          note: '仓库管理员审核通过',
        })
        this.actionMessage = '叫货单已审核通过'
      })
    },
    async rejectRequisition(requisitionId: string, note?: string) {
      const requisition = this.overview?.requisitions.find((row) => row.id === requisitionId)
      if (!requisition) throw new Error('叫货单不存在')
      await this.runAction(requisitionId, async () => {
        await reviewWarehouseRequisition(requisitionId, {
          approved: false,
          lines: requisition.lines.map((line) => ({
            itemId: line.itemId,
            approvedQuantity: 0,
          })),
          note: note || '仓库管理员驳回叫货单',
        })
        this.actionMessage = '叫货单已驳回'
      })
    },
    async shipRequisition(requisitionId: string) {
      await this.runAction(requisitionId, async () => {
        await shipWarehouseRequisition(requisitionId)
        this.actionMessage = '叫货单已发货'
      })
    },
    async receiveStock(payload: {
      itemId: number
      batchNo: string
      receivedDate: string
      expiryDate?: string
      quantity: number
      unitCost: number
      note?: string
      clientRequestId?: string
    }) {
      await this.runAction(`stock:${payload.clientRequestId || `${payload.itemId}-${payload.batchNo}`}`, async () => {
        await receiveWarehouseStock(payload)
        this.actionMessage = '采购到货已入库'
      })
    },
    async saveAlertSettings(itemId: number, payload: { minStockQuantity: number; alertEnabled: boolean; expiryAlertDays?: number }) {
      await this.runAction(`alert-${itemId}`, async () => {
        await updateWarehouseAlertSettings(itemId, payload)
        this.actionMessage = '库存预警已保存'
      })
    },
    async saveItem(payload: WarehouseItemPayload) {
      const actionId = payload.id ? `item:${payload.id}` : `item:new:${payload.code}`
      await this.runAction(actionId, async () => {
        await saveWarehouseItem(payload)
        this.actionMessage = payload.id ? '物料档案已更新' : '物料档案已新增'
      })
    },
    async setItemEnabled(itemId: number, enabled: boolean) {
      await this.runAction(`item-enabled:${itemId}`, async () => {
        await setWarehouseItemEnabled(itemId, enabled)
        this.actionMessage = enabled ? '物料已启用' : '物料已停用'
      })
    },
    async saveCategory(payload: { id?: number; name: string; parentId?: number | null; sortOrder?: number; enabled?: boolean }) {
      await this.runAction(`category:${payload.id || 'new'}`, async () => {
        await saveWarehouseItemCategory(payload)
        this.actionMessage = payload.id ? '物料分类已更新' : '物料分类已新增'
      })
    },
    async deleteCategory(categoryId: number) {
      await this.runAction(`category-delete:${categoryId}`, async () => {
        await deleteWarehouseItemCategory(categoryId)
        this.actionMessage = '物料分类已删除'
      })
    },
    async reviewReturn(returnId: string, approved: boolean, note?: string) {
      await this.runAction(`return-review-${returnId}`, async () => {
        await reviewWarehouseReturn(returnId, { approved, note })
        this.actionMessage = approved ? '配送退货单已审核通过' : '配送退货单已驳回'
      })
    },
    async receiveReturn(returnId: string, note?: string) {
      await this.runAction(`return-receive-${returnId}`, async () => {
        await receiveWarehouseReturn(returnId, { note })
        this.actionMessage = '配送退货已确认入库'
      })
    },
    async downloadPdf(kind: string, url: string, fallbackName: string) {
      this.downloadingId = `${kind}:${url}`
      this.error = ''
      try {
        await downloadWarehousePdf(url, fallbackName)
        this.actionMessage = '打印单已开始下载'
      } catch (error) {
        this.error = error instanceof Error ? error.message : '打印单下载失败'
        throw error
      } finally {
        this.downloadingId = ''
      }
    },
    async runAction(id: string, action: () => Promise<void>) {
      this.actioningId = id
      this.error = ''
      this.actionMessage = ''
      try {
        await action()
        await this.loadAll()
      } catch (error) {
        this.error = error instanceof Error ? error.message : '仓库操作失败'
        throw error
      } finally {
        this.actioningId = ''
      }
    },
    clear() {
      this.overview = null
      this.categories = []
      this.returns = []
      this.error = ''
      this.actionMessage = ''
    },
  },
})
