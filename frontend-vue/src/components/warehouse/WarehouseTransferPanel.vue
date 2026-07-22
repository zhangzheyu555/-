<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { Plus, Trash2 } from 'lucide-vue-next'
import StatusBadge from '../common/StatusBadge.vue'
import type {
  WarehouseTransfer,
  WarehouseTransferContext,
  WarehouseTransferCreatePayload,
  WarehouseTransferMaterial,
  WarehouseTransferRoute,
  WarehouseTransferRouteActions,
} from '../../api/warehouse'

const props = withDefaults(defineProps<{
  transfers: WarehouseTransfer[]
  context?: WarehouseTransferContext | null
  actioningId?: string
}>(), {
  context: null,
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
  materialQuery: string
}

type TransferAction = keyof WarehouseTransferRouteActions

const draft = reactive({ note: '', lines: [] as DraftLine[] })
const expandedId = ref('')
const selectedRouteKey = ref('')
const routes = computed(() => props.context?.routes || [])
const createRoutes = computed(() => (
  props.context?.mode === 'NONE' ? [] : routes.value.filter((route) => route.actions.canCreate)
))
const activeRoute = computed(() => (
  createRoutes.value.find((route) => routeKey(route) === selectedRouteKey.value)
  || createRoutes.value[0]
  || null
))
const isProactiveAllocation = computed(() => props.context?.mode === 'PROACTIVE_ALLOCATION')
const canChooseTargetWarehouse = computed(() => (
  isProactiveAllocation.value && createRoutes.value.length > 1
))
const visibleTransfers = computed(() => {
  const warehouseId = props.context?.currentWarehouse?.id
  if (warehouseId === undefined || warehouseId === null) return props.transfers
  return props.transfers.filter((row) => (
    String(row.sourceWarehouseId) === String(warehouseId)
    || String(row.targetWarehouseId) === String(warehouseId)
  ))
})
const formTitle = computed(() => (
  activeRoute.value?.workbenchLabel
  || props.context?.workbenchLabel
  || (isProactiveAllocation.value ? '向分仓主动配货' : '向上级总仓申请补货')
))
const formHint = computed(() => (
  isProactiveAllocation.value
    ? '调出仓固定为当前总仓；请选择后端授权的直属分仓后填写配货明细。'
    : '来源和目标仓由后端有效路线固定，提交后由上级仓审批和发货。'
))
const listHint = computed(() => (
  isProactiveAllocation.value
    ? '当前总仓视角：处理待审批、待发货，并可主动向直属分仓配货。'
    : '当前分仓视角：跟进草稿、待收货和已完成调拨。'
))
const todoCards = computed(() => {
  const todos = props.context?.todos || {}
  if (isProactiveAllocation.value) {
    return [
      { key: 'draft', label: '主动配货草稿', value: todos.draft || 0 },
      { key: 'pending-approval', label: '待审批', value: todos.pendingApproval || 0 },
      { key: 'pending-shipment', label: '待发货', value: todos.pendingShipment || 0 },
    ]
  }
  return [
    { key: 'draft', label: '草稿', value: todos.draft || 0 },
    { key: 'pending-receipt', label: '待收货', value: todos.pendingReceipt || 0 },
    { key: 'completed', label: '已完成', value: todos.completed || 0 },
  ]
})

watch(
  () => createRoutes.value.map((route) => routeKey(route)).join('|'),
  () => {
    if (!createRoutes.value.some((route) => routeKey(route) === selectedRouteKey.value)) {
      selectedRouteKey.value = createRoutes.value[0] ? routeKey(createRoutes.value[0]) : ''
      resetDraft()
    }
  },
  { immediate: true },
)

watch(selectedRouteKey, (current, previous) => {
  if (previous && current !== previous) resetDraft()
})

function routeKey(route: WarehouseTransferRoute) {
  return `${route.sourceWarehouse.id}:${route.targetWarehouse.id}`
}

function resetDraft() {
  draft.note = ''
  draft.lines.splice(0)
}

function addLine() {
  draft.lines.push({ itemId: 0, quantity: 1, note: '', materialQuery: '' })
}

function removeLine(index: number) {
  draft.lines.splice(index, 1)
}

function materialFor(line: DraftLine): WarehouseTransferMaterial | null {
  return activeRoute.value?.materials.find((item) => item.itemId === Number(line.itemId)) || null
}

function filteredMaterials(line: DraftLine) {
  const materials = activeRoute.value?.materials || []
  const query = line.materialQuery.trim().toLocaleLowerCase('zh-CN')
  if (!query) return materials
  return materials.filter((item) => (
    item.itemId === Number(line.itemId)
    || [item.itemName, item.itemCode, item.unit]
      .filter(Boolean)
      .some((value) => String(value).toLocaleLowerCase('zh-CN').includes(query))
  ))
}

function shortageFor(line: DraftLine) {
  const material = materialFor(line)
  if (!material) return line.itemId ? '该物料不在当前有效调拨路线内，请重新选择。' : ''
  if (Number(line.quantity) <= Number(material.availableQuantity)) return ''
  return material.shortageMessage || `当前可发数量为 ${qty(material.availableQuantity, material.unit)}，请调整数量或等待补货。`
}

function createDraft() {
  const route = activeRoute.value
  if (!route || !route.actions.canCreate) return
  const lines = draft.lines
    .filter((line) => line.itemId && Number(line.quantity) > 0)
    .map((line) => ({
      itemId: Number(line.itemId),
      quantity: Number(line.quantity),
      note: line.note.trim() || undefined,
    }))
  if (!lines.length) return
  emit('create', {
    sourceWarehouseId: route.sourceWarehouse.id,
    targetWarehouseId: route.targetWarehouse.id,
    lines,
    note: draft.note.trim() || undefined,
    clientRequestId: `transfer-${crypto.randomUUID().replace(/-/g, '')}`,
  })
  resetDraft()
}

function routeFor(row: WarehouseTransfer) {
  return routes.value.find((route) => (
    String(route.sourceWarehouse.id) === String(row.sourceWarehouseId)
    && String(route.targetWarehouse.id) === String(row.targetWarehouseId)
  )) || null
}

function canAction(row: WarehouseTransfer, action: TransferAction) {
  const mode = props.context?.mode
  const allowedByWorkbench = mode === 'PROACTIVE_ALLOCATION'
    ? ['canCreate', 'canSubmit', 'canCancel', 'canApprove', 'canReject', 'canShip'].includes(action)
    : mode === 'REQUEST_REPLENISHMENT'
      ? ['canCreate', 'canSubmit', 'canReceive', 'canCancel'].includes(action)
      : false
  return allowedByWorkbench && Boolean(routeFor(row)?.actions[action])
}

function isMyTodo(row: WarehouseTransfer) {
  return (row.status === 'DRAFT' && canAction(row, 'canSubmit'))
    || (row.status === 'SUBMITTED' && (canAction(row, 'canApprove') || canAction(row, 'canReject')))
    || (row.status === 'APPROVED' && canAction(row, 'canShip'))
    || (['SHIPPED', 'PARTIALLY_RECEIVED'].includes(row.status) && canAction(row, 'canReceive'))
}

function statusLabel(row: WarehouseTransfer) {
  const labels: Record<string, string> = {
    DRAFT: '草稿',
    SUBMITTED: '待审批',
    APPROVED: '待发货',
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
    <section v-if="context" class="content-card transfer-todos" aria-label="调拨待办">
      <div class="table-heading">
        <div>
          <h3>当前仓库待办</h3>
          <span>{{ listHint }}</span>
        </div>
      </div>
      <div class="transfer-todo-grid">
        <div v-for="todo in todoCards" :key="todo.key" class="transfer-todo-card">
          <span>{{ todo.label }}</span>
          <b>{{ todo.value }}</b>
        </div>
      </div>
    </section>

    <form v-if="activeRoute && activeRoute.actions.canCreate" class="content-card transfer-form" @submit.prevent="createDraft">
      <div class="table-heading transfer-heading">
        <div>
          <h3>{{ formTitle }}</h3>
          <span>{{ formHint }}</span>
        </div>
        <button class="mini-button" type="button" @click="addLine"><Plus :size="15" />增加物料</button>
      </div>
      <div class="transfer-route-fields" aria-label="调拨路线">
        <label>
          调出仓
          <input :value="activeRoute.sourceWarehouse.name" readonly aria-label="调出仓" />
        </label>
        <span class="route-arrow" aria-hidden="true">→</span>
        <label>
          调入仓
          <select
            v-if="canChooseTargetWarehouse"
            v-model="selectedRouteKey"
            aria-label="调入仓"
          >
            <option v-for="route in createRoutes" :key="routeKey(route)" :value="routeKey(route)">
              {{ route.targetWarehouse.name }}
            </option>
          </select>
          <input v-else :value="activeRoute.targetWarehouse.name" readonly aria-label="调入仓" />
        </label>
      </div>
      <div v-if="draft.lines.length" class="transfer-lines">
        <div v-for="(line, index) in draft.lines" :key="index" class="transfer-line">
          <label>
            物料
            <div class="material-picker">
              <input
                v-model="line.materialQuery"
                type="search"
                placeholder="搜索物料名称或编码"
                aria-label="搜索调拨物料"
                autocomplete="off"
              />
              <select v-model.number="line.itemId" required aria-label="调拨物料">
                <option :value="0" disabled>请选择物料</option>
                <option v-if="line.materialQuery.trim() && !filteredMaterials(line).length" :value="0" disabled>没有匹配的物料</option>
                <option v-for="item in filteredMaterials(line)" :key="item.itemId" :value="item.itemId">
                  {{ item.itemName }}{{ item.itemCode ? ` · ${item.itemCode}` : '' }} · 可发 {{ qty(item.availableQuantity, item.unit) }}
                </option>
              </select>
              <small v-if="line.materialQuery.trim()" class="material-filter-count">
                找到 {{ filteredMaterials(line).length }} 条，共 {{ activeRoute.materials.length }} 条物料
              </small>
            </div>
            <small v-if="materialFor(line)" :class="{ 'stock-shortage': shortageFor(line) }">
              实时可发 {{ qty(materialFor(line)?.availableQuantity, materialFor(line)?.unit) }}
            </small>
            <small v-if="shortageFor(line)" class="stock-shortage">{{ shortageFor(line) }}</small>
          </label>
          <label>
            {{ isProactiveAllocation ? '配货数量' : '申请数量' }}
            <input v-model.number="line.quantity" type="number" min="0.01" step="0.01" required aria-label="调拨数量" />
          </label>
          <label>
            明细备注
            <input v-model="line.note" placeholder="选填" />
          </label>
          <button class="icon-button remove-line" type="button" aria-label="删除调拨物料" @click="removeLine(index)"><Trash2 :size="15" /></button>
        </div>
      </div>
      <div v-else class="inline-empty">点击“增加物料”填写当前有效路线的调拨明细。</div>
      <label class="transfer-note">调拨说明<input v-model="draft.note" placeholder="补货原因、配货说明或到货要求（选填）" /></label>
      <button class="primary-button transfer-submit" type="submit" :disabled="!draft.lines.length || Boolean(actioningId)">保存调拨草稿</button>
    </form>

    <section v-else-if="context && context.mode !== 'NONE'" class="content-card inline-empty">
      当前账号可以查看该仓库调拨记录，但没有可新建的有效调拨路线。
    </section>

    <section class="content-card transfer-list">
      <div class="table-heading">
        <div><h3>仓间调拨</h3><span>{{ context ? listHint : '正在读取当前仓库的调拨上下文。' }}</span></div>
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
            <template v-for="row in visibleTransfers" :key="row.id">
              <tr>
                <td>
                  <b>{{ row.transferNo || row.id }}</b>
                  <small>{{ row.note || '内部调拨' }}</small>
                  <small v-if="isMyTodo(row)" class="my-todo">本人待办</small>
                </td>
                <td>{{ row.sourceWarehouseName }}</td>
                <td>{{ row.targetWarehouseName }}</td>
                <td class="line-summary">{{ detailText(row) }}</td>
                <td><StatusBadge :label="statusLabel(row)" :tone="statusTone(row.status)" /></td>
                <td><span>{{ row.requestedBy || row.approvedBy || row.shippedBy || row.receivedBy || '—' }}</span><small>{{ row.submittedAt || row.shippedAt || row.receivedAt || row.createdAt || '—' }}</small></td>
                <td>
                  <div class="row-actions">
                    <button class="mini-button" type="button" @click="expandedId = expandedId === row.id ? '' : row.id">{{ expandedId === row.id ? '收起' : '明细' }}</button>
                    <button v-if="canAction(row, 'canSubmit') && row.status === 'DRAFT'" class="mini-button primary" type="button" :disabled="Boolean(actioningId)" @click="emit('submit', row.id)">提交</button>
                    <button v-if="canAction(row, 'canApprove') && row.status === 'SUBMITTED'" class="mini-button primary" type="button" :disabled="Boolean(actioningId)" @click="emit('approve', row.id)">审批通过</button>
                    <button v-if="canAction(row, 'canReject') && row.status === 'SUBMITTED'" class="mini-button" type="button" :disabled="Boolean(actioningId)" @click="emit('reject', row.id)">驳回</button>
                    <button v-if="canAction(row, 'canShip') && row.status === 'APPROVED'" class="mini-button primary" type="button" :disabled="Boolean(actioningId)" @click="emit('ship', row.id)">发货</button>
                    <button v-if="canAction(row, 'canReceive') && (row.status === 'SHIPPED' || row.status === 'PARTIALLY_RECEIVED')" class="mini-button primary" type="button" :disabled="Boolean(actioningId)" @click="emit('receive', row.id)">确认收货</button>
                    <button v-if="canAction(row, 'canCancel') && (row.status === 'DRAFT' || row.status === 'SUBMITTED')" class="mini-button" type="button" :disabled="Boolean(actioningId)" @click="emit('cancel', row.id)">取消</button>
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
            <tr v-if="!visibleTransfers.length"><td colspan="7" class="empty-cell">当前仓库暂无仓间调拨单。</td></tr>
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

.transfer-todos,
.transfer-route-fields {
  display: grid;
  gap: 12px;
}

.transfer-todo-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.transfer-todo-card {
  display: grid;
  gap: 3px;
  padding: 11px 12px;
  border: 1px solid var(--ds-line);
  border-radius: 7px;
  background: var(--ds-surface-muted);
}

.transfer-todo-card span {
  color: var(--ds-muted);
  font-size: 12px;
}

.transfer-todo-card b {
  color: var(--ds-ink);
  font-size: 22px;
}

.transfer-route-fields {
  grid-template-columns: minmax(0, 1fr) auto minmax(0, 1fr);
  align-items: end;
  padding: 11px;
  border: 1px solid var(--ds-line);
  border-radius: 7px;
  background: var(--ds-surface-muted);
}

.transfer-route-fields label {
  display: grid;
  min-width: 0;
  gap: 5px;
  color: var(--ds-secondary);
  font-size: 13px;
  font-weight: 700;
}

.route-arrow {
  padding: 0 2px 9px;
  color: var(--ds-primary-hover);
  font-size: 22px;
  font-weight: 800;
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

.transfer-line small {
  color: var(--ds-muted);
  font-size: 12px;
  font-weight: 500;
}

.material-picker {
  display: grid;
  gap: 6px;
}

.material-picker .material-filter-count {
  color: var(--ds-primary-hover);
}

.transfer-line .stock-shortage {
  color: var(--ds-danger, #b94a48);
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

td .my-todo {
  color: var(--ds-primary-hover);
  font-weight: 800;
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
  .transfer-detail > div,
  .transfer-route-fields {
    grid-template-columns: 1fr;
  }

  .transfer-todo-grid {
    grid-template-columns: 1fr;
  }

  .route-arrow {
    display: none;
  }

  .remove-line {
    width: 100%;
  }
}
</style>
