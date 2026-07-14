<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { Plus, Trash2 } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type { WarehouseInfo, WarehouseItem, WarehouseTransfer, WarehouseTransferCreatePayload } from '../../api/warehouse'

const props = withDefaults(defineProps<{
  transfers: WarehouseTransfer[]
  warehouses: WarehouseInfo[]
  items: WarehouseItem[]
  activeWarehouse?: WarehouseInfo | null
  canRequest?: boolean
  canApprove?: boolean
  canShip?: boolean
  canReceive?: boolean
  actioningId?: string
}>(), {
  activeWarehouse: null,
  canRequest: false,
  canApprove: false,
  canShip: false,
  canReceive: false,
  actioningId: '',
})

const emit = defineEmits<{
  create: [payload: WarehouseTransferCreatePayload]
  submit: [id: string]
  approve: [id: string]
  reject: [id: string]
  ship: [id: string]
  receive: [id: string]
  cancel: [id: string]
}>()

interface DraftLine {
  itemId: number
  quantity: number
  note: string
}

const draft = reactive({ note: '', lines: [] as DraftLine[] })
const expandedId = ref('')
const centralWarehouse = computed(() => props.warehouses.find((warehouse) => warehouse.type === 'CENTRAL') || null)
const targetWarehouse = computed(() => (
  props.activeWarehouse?.type === 'REGIONAL'
    ? props.activeWarehouse
    : props.warehouses.find((warehouse) => warehouse.type === 'REGIONAL') || null
))
const sourceWarehouseId = computed(() => centralWarehouse.value?.id || targetWarehouse.value?.parentWarehouseId || null)
const sourceWarehouseName = computed(() => centralWarehouse.value?.name || targetWarehouse.value?.parentWarehouseName || '荆州总仓')
const activeItems = computed(() => props.items.filter((item) => item.active !== false))

function addLine() {
  draft.lines.push({ itemId: 0, quantity: 1, note: '' })
}

function removeLine(index: number) {
  draft.lines.splice(index, 1)
}

function createDraft() {
  if (!sourceWarehouseId.value || !targetWarehouse.value) return
  const lines = draft.lines
    .filter((line) => line.itemId && Number(line.quantity) > 0)
    .map((line) => ({
      itemId: Number(line.itemId),
      quantity: Number(line.quantity),
      note: line.note.trim() || undefined,
    }))
  if (!lines.length) return
  emit('create', {
    sourceWarehouseId: sourceWarehouseId.value,
    targetWarehouseId: targetWarehouse.value.id,
    lines,
    note: draft.note.trim() || undefined,
    clientRequestId: `transfer-${crypto.randomUUID().replace(/-/g, '')}`,
  })
  draft.note = ''
  draft.lines.splice(0)
}

function statusLabel(row: WarehouseTransfer) {
  const labels: Record<string, string> = {
    DRAFT: '草稿',
    SUBMITTED: '待荆州审批',
    APPROVED: '待荆州发货',
    REJECTED: '已驳回',
    SHIPPED: '在途',
    PARTIALLY_RECEIVED: '部分收货',
    RECEIVED: '已完成',
    CANCELLED: '已取消',
  }
  return row.statusLabel || labels[row.status] || row.status
}

function statusTone(status: WarehouseTransfer['status']) {
  if (status === 'RECEIVED') return 'ok'
  if (status === 'SHIPPED' || status === 'PARTIALLY_RECEIVED') return 'info'
  if (status === 'SUBMITTED' || status === 'APPROVED') return 'warn'
  if (status === 'REJECTED' || status === 'CANCELLED') return 'bad'
  return 'muted'
}

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 2 })}${unit ? ` ${unit}` : ''}`
}

function detailText(row: WarehouseTransfer) {
  return row.lines.map((line) => `${line.itemName} ${qty(line.requestedQuantity, line.unit)}`).join('，')
}
</script>

<template>
  <div class="transfer-stack">
    <form v-if="canRequest" class="content-card transfer-form" @submit.prevent="createDraft">
      <div class="table-heading transfer-heading">
        <div>
          <h3>向荆州总仓申请补货</h3>
          <span>{{ sourceWarehouseName }} → {{ targetWarehouse?.name || '山东分仓' }}，路线由系统固定。</span>
        </div>
        <button class="mini-button" type="button" @click="addLine"><Plus :size="15" />增加物料</button>
      </div>
      <div v-if="draft.lines.length" class="transfer-lines">
        <div v-for="(line, index) in draft.lines" :key="index" class="transfer-line">
          <label>
            物料
            <select v-model.number="line.itemId" required aria-label="调拨物料">
              <option :value="0" disabled>请选择物料</option>
              <option v-for="item in activeItems" :key="item.id" :value="item.id">{{ item.name }} · {{ item.code }}</option>
            </select>
          </label>
          <label>
            申请数量
            <input v-model.number="line.quantity" type="number" min="0.01" step="0.01" required aria-label="调拨数量" />
          </label>
          <label>
            明细备注
            <input v-model="line.note" placeholder="选填" />
          </label>
          <button class="icon-button remove-line" type="button" aria-label="删除调拨物料" @click="removeLine(index)"><Trash2 :size="15" /></button>
        </div>
      </div>
      <div v-else class="inline-empty">点击“增加物料”填写山东分仓补货需求。</div>
      <label class="transfer-note">申请说明<input v-model="draft.note" placeholder="补货原因或到货要求（选填）" /></label>
      <button class="primary-button transfer-submit" type="submit" :disabled="!draft.lines.length || Boolean(actioningId)">保存调拨草稿</button>
    </form>

    <section class="content-card transfer-list">
      <div class="table-heading">
        <div><h3>仓间调拨</h3><span>山东申请，荆州审批发货，山东确认收货。</span></div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>调拨单</th>
              <th>来源仓</th>
              <th>目标仓</th>
              <th>物料</th>
              <th>状态</th>
              <th>操作人 / 时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <template v-for="row in transfers" :key="row.id">
              <tr>
                <td><b>{{ row.transferNo || row.id }}</b><small>{{ row.note || '内部调拨' }}</small></td>
                <td>{{ row.sourceWarehouseName }}</td>
                <td>{{ row.targetWarehouseName }}</td>
                <td class="line-summary">{{ detailText(row) }}</td>
                <td><StatusBadge :label="statusLabel(row)" :tone="statusTone(row.status)" /></td>
                <td><span>{{ row.requestedBy || row.approvedBy || row.shippedBy || row.receivedBy || '—' }}</span><small>{{ row.submittedAt || row.shippedAt || row.receivedAt || row.createdAt || '—' }}</small></td>
                <td>
                  <div class="row-actions">
                    <button class="mini-button" type="button" @click="expandedId = expandedId === row.id ? '' : row.id">{{ expandedId === row.id ? '收起' : '明细' }}</button>
                    <button v-if="canRequest && row.status === 'DRAFT'" class="mini-button primary" type="button" :disabled="Boolean(actioningId)" @click="emit('submit', row.id)">提交</button>
                    <button v-if="canApprove && row.status === 'SUBMITTED'" class="mini-button primary" type="button" :disabled="Boolean(actioningId)" @click="emit('approve', row.id)">审批通过</button>
                    <button v-if="canApprove && row.status === 'SUBMITTED'" class="mini-button" type="button" :disabled="Boolean(actioningId)" @click="emit('reject', row.id)">驳回</button>
                    <button v-if="canShip && row.status === 'APPROVED'" class="mini-button primary" type="button" :disabled="Boolean(actioningId)" @click="emit('ship', row.id)">发货</button>
                    <button v-if="canReceive && (row.status === 'SHIPPED' || row.status === 'PARTIALLY_RECEIVED')" class="mini-button primary" type="button" :disabled="Boolean(actioningId)" @click="emit('receive', row.id)">确认收货</button>
                    <button v-if="canRequest && (row.status === 'DRAFT' || row.status === 'SUBMITTED')" class="mini-button" type="button" :disabled="Boolean(actioningId)" @click="emit('cancel', row.id)">取消</button>
                  </div>
                </td>
              </tr>
              <tr v-if="expandedId === row.id">
                <td colspan="7" class="transfer-detail-cell">
                  <div class="transfer-detail">
                    <div v-for="line in row.lines" :key="line.id || line.itemId">
                      <b>{{ line.itemName }}</b>
                      <span>申请 {{ qty(line.requestedQuantity, line.unit) }}</span>
                      <span>批准 {{ qty(line.approvedQuantity, line.unit) }}</span>
                      <span>发出 {{ qty(line.shippedQuantity, line.unit) }}</span>
                      <span>收到 {{ qty(line.receivedQuantity, line.unit) }}</span>
                    </div>
                  </div>
                </td>
              </tr>
            </template>
            <tr v-if="!transfers.length"><td colspan="7" class="empty-cell">暂无仓间调拨单。</td></tr>
          </tbody>
        </table>
      </div>
    </section>
  </div>
</template>

<style scoped>
.transfer-stack,
.transfer-form {
  display: grid;
  gap: 14px;
}

.transfer-heading {
  align-items: center;
}

.transfer-lines {
  display: grid;
  gap: 8px;
}

.transfer-line {
  display: grid;
  grid-template-columns: minmax(200px, 1.4fr) minmax(130px, .6fr) minmax(180px, 1fr) auto;
  gap: 10px;
  align-items: end;
  padding: 10px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: var(--ds-surface-muted);
}

.transfer-line label,
.transfer-note {
  display: grid;
  min-width: 0;
  gap: 5px;
  color: var(--ds-secondary);
  font-size: 13px;
  font-weight: 700;
}

.remove-line {
  min-width: 36px;
  min-height: 36px;
}

.transfer-submit {
  width: auto;
  justify-self: start;
}

.transfer-list,
.line-summary {
  min-width: 0;
}

.line-summary {
  max-width: 280px;
  white-space: normal;
}

td small {
  display: block;
  margin-top: 3px;
  color: var(--ds-muted);
}

.row-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.transfer-detail-cell {
  padding: 8px 12px 12px;
}

.transfer-detail {
  display: grid;
  gap: 6px;
  padding: 10px;
  border-radius: 6px;
  background: var(--ds-surface-muted);
}

.transfer-detail > div {
  display: grid;
  grid-template-columns: minmax(160px, 1.4fr) repeat(4, minmax(100px, .7fr));
  gap: 8px;
  align-items: center;
  font-size: 13px;
}

@media (max-width: 820px) {
  .transfer-line,
  .transfer-detail > div {
    grid-template-columns: 1fr;
  }

  .remove-line {
    width: 100%;
  }
}
</style>
