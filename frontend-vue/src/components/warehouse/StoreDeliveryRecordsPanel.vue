<script setup lang="ts">
import { computed } from 'vue'
import { PackageCheck, RotateCcw } from 'lucide-vue-next'
import type { WarehouseRequisition, WarehouseReturnOrder } from '../../api/warehouse'
import StatusBadge from '../common/StatusBadge.vue'
import WarehousePrintButtons from './WarehousePrintButtons.vue'

const props = defineProps<{
  requisitions: WarehouseRequisition[]
  returns: WarehouseReturnOrder[]
  receivingId: string
  actioningId: string
  downloadingId: string
  canReceive?: boolean
  canCreateReturn?: boolean
}>()

const emit = defineEmits<{
  receive: [requisitionId: string]
  createReturn: [requisition: WarehouseRequisition]
  downloadReturn: [id: string, returnNo: string]
}>()

const pendingReceiptCount = computed(() => props.requisitions.filter(isPendingReceipt).length)
const processingReturnCount = computed(() => props.returns.filter((row) => ['SUBMITTED', 'APPROVED'].includes(row.status)).length)

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}${unit || ''}`
}

function money(value: number | undefined) {
  return Number(value || 0).toLocaleString('zh-CN', { style: 'currency', currency: 'CNY' })
}

function isPendingReceipt(row: WarehouseRequisition) {
  return ['SHIPPED', 'PARTIALLY_SHIPPED'].includes(row.status)
}

function canReturn(row: WarehouseRequisition) {
  return props.canCreateReturn && row.status === 'RECEIVED' && row.lines.some((line) => Number(line.shippedQuantity || 0) > 0)
}

function requisitionStatusLabel(row: WarehouseRequisition) {
  const map: Record<string, string> = {
    SUBMITTED: '待仓库处理',
    APPROVED: '待仓库发货',
    BACKORDERED: '缺货待处理',
    WAITING_REPLENISHMENT: '待补货',
    PARTIALLY_SHIPPED: '部分发货 / 待补货',
    SHIPPED: '待门店收货',
    RECEIVED: '门店已收货',
    REJECTED: '已驳回',
  }
  return row.statusLabel || map[row.status] || row.status
}

function requisitionTone(status: string) {
  if (status === 'RECEIVED') return 'ok'
  if (status === 'REJECTED') return 'bad'
  if (['SHIPPED', 'PARTIALLY_SHIPPED'].includes(status)) return 'info'
  if (['SUBMITTED', 'APPROVED', 'BACKORDERED', 'WAITING_REPLENISHMENT'].includes(status)) return 'warn'
  return 'muted'
}

function requisitionLineText(row: WarehouseRequisition) {
  return row.lines.map((line) => `${line.itemName} × ${qty(line.requestedQuantity, line.unit)}`).join('，')
}

function progressText(row: WarehouseRequisition) {
  return row.lines.map((line) => {
    const shipped = Number(line.shippedQuantity || 0)
    const outstanding = Math.max(0, Number(line.requestedQuantity || 0) - shipped)
    if (['BACKORDERED', 'WAITING_REPLENISHMENT', 'PARTIALLY_SHIPPED'].includes(row.status)) {
      return `${line.itemName}：已发 ${qty(shipped, line.unit)}，缺货 ${qty(outstanding, line.unit)}`
    }
    if (row.status === 'SUBMITTED') {
      return `${line.itemName}：已发 ${qty(shipped, line.unit)}，待仓库处理 ${qty(outstanding, line.unit)}`
    }
    if (row.status === 'APPROVED') {
      return `${line.itemName}：已发 ${qty(shipped, line.unit)}，待发 ${qty(outstanding, line.unit)}`
    }
    return `${line.itemName}：已发 ${qty(shipped, line.unit)}，未发 ${qty(outstanding, line.unit)}`
  }).join('；')
}

function returnStatusLabel(row: WarehouseReturnOrder) {
  const map: Record<string, string> = {
    DRAFT: '草稿',
    SUBMITTED: '待仓库审核',
    APPROVED: '待退货入库',
    CHECKED: '已核对',
    RECEIVED: '仓库已收货',
    REJECTED: '已驳回',
    CANCELLED: '已作废',
  }
  return row.statusLabel || map[row.status] || row.status
}

function returnTone(status: string) {
  if (['RECEIVED', 'CHECKED'].includes(status)) return 'ok'
  if (['REJECTED', 'CANCELLED'].includes(status)) return 'bad'
  if (['SUBMITTED', 'APPROVED'].includes(status)) return 'warn'
  return 'muted'
}

function returnLineText(row: WarehouseReturnOrder) {
  return row.lines.map((line) => `${line.itemName} × ${qty(line.quantity, line.unit)}`).join('，')
}
</script>

<template>
  <section class="content-card store-delivery-records" aria-labelledby="store-delivery-records-title">
    <div class="table-heading store-delivery-records__head">
      <div>
        <h3 id="store-delivery-records-title">配送与退货记录</h3>
        <span>在同一处查看叫货进度、确认收货、发起配送退货并下载退货单。</span>
      </div>
      <div class="store-delivery-summary" aria-label="配送待办概览">
        <span><b>{{ pendingReceiptCount }}</b> 单待收货</span>
        <span><b>{{ processingReturnCount }}</b> 单退货处理中</span>
      </div>
    </div>

    <div class="store-delivery-section">
      <div class="store-delivery-section__title">
        <div>
          <h4>叫货与收货</h4>
          <span>收货操作直接显示在对应叫货单上，不再重复展示一份待收货列表。</span>
        </div>
        <strong>{{ requisitions.length }} 单</strong>
      </div>

      <div class="store-delivery-card-list pending-receipt-card-list" aria-label="叫货与收货记录">
        <article
          v-for="row in requisitions"
          :key="row.id"
          class="store-delivery-card"
          :class="{ 'pending-receipt-card': isPendingReceipt(row) }"
        >
          <header>
            <div>
              <strong>{{ row.id }}</strong>
              <small>{{ row.submittedAt || '-' }}</small>
            </div>
            <StatusBadge :label="requisitionStatusLabel(row)" :tone="requisitionTone(row.status)" />
          </header>
          <dl>
            <div><dt>商品</dt><dd>{{ requisitionLineText(row) }}</dd></div>
            <div><dt>发货进度</dt><dd>{{ progressText(row) }}</dd></div>
            <div v-if="row.note"><dt>仓库说明</dt><dd>{{ row.note }}</dd></div>
            <div v-if="row.shippedAt"><dt>发货时间</dt><dd>{{ row.shippedAt }}</dd></div>
          </dl>
          <div class="store-delivery-card__actions">
            <button
              v-if="canReceive && isPendingReceipt(row)"
              class="mini-button primary"
              type="button"
              :disabled="receivingId === row.id"
              @click="emit('receive', row.id)"
            >
              <PackageCheck :size="15" />
              {{ receivingId === row.id ? '确认中...' : '确认已收货' }}
            </button>
            <button
              v-if="canReturn(row)"
              class="mini-button"
              type="button"
              :disabled="Boolean(actioningId)"
              @click="emit('createReturn', row)"
            >
              <RotateCcw :size="15" />发起配送退货
            </button>
          </div>
        </article>
        <p v-if="!requisitions.length" class="empty-cell">还没有叫货或收货记录。</p>
      </div>

      <div class="table-wrap store-delivery-table-wrap pending-receipt-table-wrap">
        <table>
          <thead>
            <tr>
              <th>单号</th>
              <th>商品</th>
              <th>发货进度</th>
              <th>状态</th>
              <th>提交 / 发货时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in requisitions" :key="row.id">
              <td>
                <b>{{ row.id }}</b>
                <small v-if="row.note">{{ row.note }}</small>
              </td>
              <td>{{ requisitionLineText(row) }}</td>
              <td>{{ progressText(row) }}</td>
              <td><StatusBadge :label="requisitionStatusLabel(row)" :tone="requisitionTone(row.status)" /></td>
              <td>
                <span>{{ row.submittedAt || '-' }}</span>
                <small v-if="row.shippedAt">发货 {{ row.shippedAt }}</small>
              </td>
              <td>
                <div class="store-delivery-row-actions">
                  <button
                    v-if="canReceive && isPendingReceipt(row)"
                    class="mini-button primary"
                    type="button"
                    :disabled="receivingId === row.id"
                    @click="emit('receive', row.id)"
                  >
                    {{ receivingId === row.id ? '确认中...' : '确认已收货' }}
                  </button>
                  <button
                    v-if="canReturn(row)"
                    class="mini-button"
                    type="button"
                    :disabled="Boolean(actioningId)"
                    @click="emit('createReturn', row)"
                  >
                    发起配送退货
                  </button>
                  <span v-if="!isPendingReceipt(row) && !canReturn(row)" class="store-delivery-no-action">—</span>
                </div>
              </td>
            </tr>
            <tr v-if="!requisitions.length">
              <td colspan="6" class="empty-cell">还没有叫货或收货记录。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="store-delivery-section store-return-records">
      <div class="store-delivery-section__title">
        <div>
          <h4>配送退货单</h4>
          <span>退货单提交后由供货仓审核和确认入库，状态会自动更新。</span>
        </div>
        <strong>{{ returns.length }} 单</strong>
      </div>

      <div class="store-return-card-list">
        <article v-for="row in returns" :key="row.id" class="store-return-card">
          <header>
            <div>
              <strong>{{ row.returnNo || row.id }}</strong>
              <small>来源叫货单 {{ row.sourceRequisitionId || '-' }}</small>
            </div>
            <StatusBadge :label="returnStatusLabel(row)" :tone="returnTone(row.status)" />
          </header>
          <dl>
            <div><dt>退货物料</dt><dd>{{ returnLineText(row) || '-' }}</dd></div>
            <div><dt>退货原因</dt><dd>{{ row.reason || row.note || '-' }}</dd></div>
            <div><dt>退货日期</dt><dd>{{ row.returnDate || row.createdAt || '-' }}</dd></div>
            <div v-if="Number(row.totalAmount || 0) > 0"><dt>退货金额</dt><dd>{{ money(row.totalAmount) }}</dd></div>
          </dl>
          <WarehousePrintButtons
            label="下载配送退货单"
            :disabled="downloadingId.includes(row.id)"
            @download="emit('downloadReturn', row.id, row.returnNo || row.id)"
          />
        </article>
        <p v-if="!returns.length" class="empty-cell">暂无配送退货单，可在已收货叫货单上发起退货。</p>
      </div>

      <div class="table-wrap store-return-table-wrap">
        <table>
          <thead>
            <tr>
              <th>退货单号</th>
              <th>来源叫货单</th>
              <th>退货物料</th>
              <th>原因</th>
              <th>状态</th>
              <th>日期</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in returns" :key="row.id">
              <td><b>{{ row.returnNo || row.id }}</b></td>
              <td>{{ row.sourceRequisitionId || '-' }}</td>
              <td>{{ returnLineText(row) || '-' }}</td>
              <td>{{ row.reason || row.note || '-' }}</td>
              <td><StatusBadge :label="returnStatusLabel(row)" :tone="returnTone(row.status)" /></td>
              <td>{{ row.returnDate || row.createdAt || '-' }}</td>
              <td>
                <WarehousePrintButtons
                  label="下载退货单"
                  :disabled="downloadingId.includes(row.id)"
                  @download="emit('downloadReturn', row.id, row.returnNo || row.id)"
                />
              </td>
            </tr>
            <tr v-if="!returns.length">
              <td colspan="7" class="empty-cell">暂无配送退货单，可在已收货叫货单上发起退货。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>

<style scoped>
.store-delivery-records {
  display: grid;
  gap: 0;
  min-width: 0;
}

.store-delivery-records__head {
  align-items: center;
  padding-bottom: 16px;
}

.store-delivery-summary {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.store-delivery-summary span {
  padding: 7px 10px;
  border-radius: 999px;
  background: var(--ds-primary-soft, #e9f6f5);
  color: var(--ds-primary-hover, #285f5c);
  font-size: 13px;
  white-space: nowrap;
}

.store-delivery-summary b {
  font-size: 15px;
}

.store-delivery-section {
  display: grid;
  gap: 12px;
  padding: 18px 0;
  border-top: 1px solid var(--ds-line);
}

.store-delivery-section__title {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.store-delivery-section__title h4 {
  margin: 0;
  color: var(--ds-text);
  font-size: 16px;
}

.store-delivery-section__title span {
  display: block;
  margin-top: 4px;
  color: var(--ds-muted);
  font-size: 13px;
}

.store-delivery-section__title > strong {
  color: var(--ds-primary-hover);
  font-size: 13px;
  white-space: nowrap;
}

.store-delivery-card-list,
.store-return-card-list {
  display: none;
}

.store-delivery-table-wrap,
.store-return-table-wrap {
  min-width: 0;
}

.store-delivery-table-wrap table,
.store-return-table-wrap table {
  min-width: 960px;
}

.store-delivery-table-wrap td,
.store-return-table-wrap td {
  vertical-align: top;
}

.store-delivery-table-wrap td small {
  display: block;
  margin-top: 4px;
  color: var(--ds-muted);
}

.store-delivery-row-actions {
  display: flex;
  min-width: 130px;
  flex-wrap: wrap;
  gap: 7px;
}

.store-delivery-no-action {
  color: var(--ds-muted);
}

.store-return-records {
  padding-bottom: 0;
}

@media (max-width: 768px) {
  .store-delivery-records__head,
  .store-delivery-section__title {
    align-items: flex-start;
    flex-direction: column;
  }

  .store-delivery-summary {
    width: 100%;
    justify-content: flex-start;
  }

  .store-delivery-table-wrap,
  .store-return-table-wrap {
    display: none;
  }

  .store-delivery-card-list,
  .store-return-card-list {
    display: grid;
    gap: 12px;
  }

  .store-delivery-card,
  .store-return-card {
    display: grid;
    min-width: 0;
    gap: 14px;
    padding: 14px;
    border: 1px solid var(--ds-line);
    border-radius: 10px;
    background: #fff;
  }

  .store-delivery-card header,
  .store-return-card header {
    display: flex;
    min-width: 0;
    align-items: flex-start;
    justify-content: space-between;
    gap: 12px;
  }

  .store-delivery-card header > div,
  .store-return-card header > div {
    display: grid;
    min-width: 0;
    gap: 4px;
  }

  .store-delivery-card header strong,
  .store-return-card header strong {
    overflow-wrap: anywhere;
  }

  .store-delivery-card header small,
  .store-return-card header small {
    color: var(--ds-muted);
  }

  .store-delivery-card dl,
  .store-return-card dl {
    display: grid;
    gap: 10px;
    margin: 0;
  }

  .store-delivery-card dl > div,
  .store-return-card dl > div {
    display: grid;
    grid-template-columns: 76px minmax(0, 1fr);
    gap: 10px;
  }

  .store-delivery-card dt,
  .store-return-card dt {
    color: var(--ds-muted);
    font-size: 13px;
  }

  .store-delivery-card dd,
  .store-return-card dd {
    min-width: 0;
    margin: 0;
    overflow-wrap: anywhere;
  }

  .store-delivery-card__actions {
    display: grid;
    gap: 8px;
  }

  .store-delivery-card .mini-button,
  .store-return-card :deep(.mini-button) {
    display: inline-flex;
    width: 100%;
    min-height: 44px;
    align-items: center;
    justify-content: center;
    gap: 7px;
  }

  .store-delivery-card-list > .empty-cell,
  .store-return-card-list > .empty-cell {
    margin: 0;
    padding: 14px;
    border: 1px solid var(--ds-line);
    border-radius: 10px;
  }
}
</style>
