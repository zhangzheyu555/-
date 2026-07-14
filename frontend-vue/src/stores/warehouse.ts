import { defineStore } from 'pinia'
import {
  cancelWarehouseTransfer,
  approveWarehousePurchaseOrder,
  createWarehousePurchaseOrder,
  createWarehouseTransfer,
  createWarehouseRequisition,
  deleteWarehouseItemCategory,
  downloadWarehousePdf,
  getWarehouseReturns,
  getWarehouseItemCategories,
  getWarehouseOverview,
  getWarehouseTransfers,
  getWarehouses,
  receiveWarehouseTransfer,
  receiveWarehouseRequisition,
  receiveWarehousePurchaseOrder,
  receiveWarehouseReturn,
  receiveWarehouseStock,
  reviewWarehouseRequisition,
  reviewWarehouseReturn,
  reviewWarehouseTransfer,
  shipWarehouseTransfer,
  shipWarehouseRequisition,
  submitWarehouseTransfer,
  saveWarehouseItem,
  saveWarehouseItemCategory,
  setWarehouseItemEnabled,
  updateWarehouseAlertSettings,
  type WarehouseItemCategory,
  type WarehouseItemPayload,
  type WarehouseInfo,
  type WarehouseOverview,
  type WarehousePurchaseOrderCreatePayload,
  type WarehousePurchaseOrderReceivePayload,
  type WarehouseReturnOrder,
  type WarehouseTransfer,
  type WarehouseTransferCreatePayload,
} from '../api/warehouse'

export type WarehouseTab = 'overview' | 'warehouse' | 'transfers' | 'requisitions' | 'inventory' | 'purchase' | 'catalog' | 'alerts' | 'returns' | 'movements' | 'receipts' | 'prints'

export const useWarehouseStore = defineStore('warehouse', {
  state: () => ({
    overview: null as WarehouseOverview | null,
    warehouses: [] as WarehouseInfo[],
    transfers: [] as WarehouseTransfer[],
    selectedWarehouseId: '' as string | number,
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
    async loadWarehouses() {
      const rows = await getWarehouses()
      this.warehouses = rows
      if (!rows.some((row) => String(row.id) === String(this.selectedWarehouseId))) {
        this.selectedWarehouseId = rows.find((row) => row.code === 'JZ-CENTRAL')?.id || rows[0]?.id || ''
      }
      return rows
    },
    async loadOverview(warehouseId?: string | number) {
      this.loading = true
      this.error = ''
      try {
        this.overview = await getWarehouseOverview(warehouseId || this.selectedWarehouseId || undefined)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '仓库数据加载失败'
        throw error
      } finally {
        this.loading = false
      }
    },
    async loadTransfers(warehouseId?: string | number) {
      try {
        this.transfers = await getWarehouseTransfers(warehouseId || this.selectedWarehouseId || undefined)
      } catch (error) {
        this.error = error instanceof Error ? error.message : '仓间调拨加载失败'
        throw error
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
      await this.loadWarehouses()
      await Promise.all([
        this.loadOverview(this.selectedWarehouseId),
        this.loadCategories(),
        this.loadReturns(),
        this.loadTransfers(this.selectedWarehouseId),
      ])
    },
    async selectWarehouse(warehouseId: string | number) {
      const selected = this.warehouses.find((row) => String(row.id) === String(warehouseId))
      if (!selected) {
        throw new Error('当前账号无权访问该仓库')
      }
      this.selectedWarehouseId = selected.id
      await Promise.all([this.loadOverview(selected.id), this.loadTransfers(selected.id)])
    },
    setTab(tab: string | null | undefined) {
      const normalized = String(tab || 'overview') as WarehouseTab
      this.activeTab = ['overview', 'warehouse', 'transfers', 'requisitions', 'inventory', 'purchase', 'catalog', 'alerts', 'returns', 'movements', 'receipts', 'prints'].includes(normalized)
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
        if (!this.selectedWarehouseId) {
          throw new Error('未确定当前仓库，不能执行采购入库')
        }
        await receiveWarehouseStock({ ...payload, warehouseId: this.selectedWarehouseId })
        this.actionMessage = '采购到货已入库'
      })
    },
    async createPurchaseOrder(payload: Omit<WarehousePurchaseOrderCreatePayload, 'warehouseId'>) {
      await this.runAction(`purchase:create:${payload.clientRequestId}`, async () => {
        if (!this.selectedWarehouseId) {
          throw new Error('未确定当前仓库，不能创建采购单')
        }
        await createWarehousePurchaseOrder({ ...payload, warehouseId: this.selectedWarehouseId })
        this.actionMessage = '采购草稿已创建，等待审批'
      })
    },
    async approvePurchaseOrder(purchaseOrderId: string) {
      await this.runAction(`purchase:approve:${purchaseOrderId}`, async () => {
        await approveWarehousePurchaseOrder(purchaseOrderId)
        this.actionMessage = '采购单已审批'
      })
    },
    async receivePurchaseOrder(purchaseOrderId: string, payload: WarehousePurchaseOrderReceivePayload) {
      await this.runAction(`purchase:receive:${purchaseOrderId}`, async () => {
        await receiveWarehousePurchaseOrder(purchaseOrderId, payload)
        this.actionMessage = '采购单已核对入库'
      })
    },
    async createTransfer(payload: WarehouseTransferCreatePayload) {
      await this.runAction(`transfer:create:${payload.clientRequestId}`, async () => {
        await createWarehouseTransfer(payload)
        this.actionMessage = '调拨草稿已创建'
      })
    },
    async submitTransfer(id: string) {
      await this.runAction(`transfer:submit:${id}`, async () => {
        await submitWarehouseTransfer(id)
        this.actionMessage = '调拨申请已提交荆州总仓'
      })
    },
    async reviewTransfer(id: string, approved: boolean, note?: string) {
      await this.runAction(`transfer:review:${id}`, async () => {
        await reviewWarehouseTransfer(id, approved, note)
        this.actionMessage = approved ? '调拨申请已审批通过' : '调拨申请已驳回'
      })
    },
    async shipTransfer(id: string, clientRequestId: string, note?: string) {
      await this.runAction(`transfer:ship:${id}`, async () => {
        await shipWarehouseTransfer(id, clientRequestId, note)
        this.actionMessage = '调拨单已发货，库存已转为在途'
      })
    },
    async receiveTransfer(
      id: string,
      payload: { clientRequestId: string; note?: string; lines?: Array<{ itemId: number; receivedQuantity: number }> },
    ) {
      await this.runAction(`transfer:receive:${id}`, async () => {
        await receiveWarehouseTransfer(id, payload)
        this.actionMessage = '调拨到货已确认入库'
      })
    },
    async cancelTransfer(id: string, clientRequestId: string, note?: string) {
      await this.runAction(`transfer:cancel:${id}`, async () => {
        await cancelWarehouseTransfer(id, clientRequestId, note)
        this.actionMessage = '调拨单已取消'
      })
    },
    async saveAlertSettings(itemId: number, payload: { minStockQuantity: number; alertEnabled: boolean; expiryAlertDays?: number }) {
      await this.runAction(`alert-${itemId}`, async () => {
        if (!this.selectedWarehouseId) {
          throw new Error('未确定当前仓库，不能保存库存预警')
        }
        await updateWarehouseAlertSettings(itemId, { ...payload, warehouseId: this.selectedWarehouseId })
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
      this.warehouses = []
      this.transfers = []
      this.selectedWarehouseId = ''
      this.categories = []
      this.returns = []
      this.error = ''
      this.actionMessage = ''
    },
  },
})
