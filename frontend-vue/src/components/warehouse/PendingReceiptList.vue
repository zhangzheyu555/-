<script setup lang="ts">
import type { WarehouseRequisition } from '../../api/warehouse'

defineProps<{
  requisitions: WarehouseRequisition[]
  receivingId: string
}>()

const emit = defineEmits<{
  receive: [requisitionId: string]
}>()

function qty(value: number | undefined, unit?: string) {
  return `${Number(value || 0).toLocaleString('zh-CN', { maximumFractionDigits: 1 })}${unit || ''}`
}

function lineText(row: WarehouseRequisition) {
  return row.lines.map((line) => `${line.itemName} × ${qty(line.shippedQuantity || line.approvedQuantity || line.requestedQuantity, line.unit)}`).join('，')
}
</script>

<template>
  <div class="content-card">
    <div class="table-heading">
      <div>
        <h3>待确认收货</h3>
        <span>部分发货也可确认本次收货，未发数量会继续保留为待补货。</span>
      </div>
    </div>
    <div class="pending-receipt-card-list" aria-label="待确认收货列表">
      <article v-for="row in requisitions" :key="row.id" class="pending-receipt-card">
        <dl>
          <div>
            <dt>单号</dt>
            <dd><b>{{ row.id }}</b></dd>
          </div>
          <div>
            <dt>商品</dt>
            <dd>{{ lineText(row) }}</dd>
          </div>
          <div>
            <dt>发货时间</dt>
            <dd>{{ row.shippedAt || '-' }}</dd>
          </div>
        </dl>
        <button class="mini-button primary" type="button" :disabled="receivingId === row.id" @click="emit('receive', row.id)">
          {{ receivingId === row.id ? '确认中...' : '确认已收货' }}
        </button>
      </article>
      <p v-if="!requisitions.length" class="empty-cell">当前没有待确认收货的叫货单。</p>
    </div>
    <div class="table-wrap pending-receipt-table-wrap">
      <table>
        <thead>
          <tr>
            <th>单号</th>
            <th>商品</th>
            <th>发货时间</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in requisitions" :key="row.id">
            <td><b>{{ row.id }}</b></td>
            <td>{{ lineText(row) }}</td>
            <td>{{ row.shippedAt || '-' }}</td>
            <td>
              <button class="mini-button primary" type="button" :disabled="receivingId === row.id" @click="emit('receive', row.id)">
                {{ receivingId === row.id ? '确认中...' : '确认已收货' }}
              </button>
            </td>
          </tr>
          <tr v-if="!requisitions.length">
            <td colspan="4" class="empty-cell">当前没有待确认收货的叫货单。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.pending-receipt-card-list {
  display: none;
}

@media (max-width: 768px) {
  .pending-receipt-table-wrap {
    display: none;
  }

  .pending-receipt-card-list {
    display: grid;
    gap: 12px;
  }

  .pending-receipt-card {
    display: grid;
    gap: 14px;
    padding: 14px;
    border: 1px solid var(--line);
    border-radius: 10px;
    background: #fff;
  }

  .pending-receipt-card dl {
    display: grid;
    gap: 10px;
    margin: 0;
  }

  .pending-receipt-card dl > div {
    display: grid;
    grid-template-columns: 76px minmax(0, 1fr);
    gap: 10px;
    align-items: start;
  }

  .pending-receipt-card dt {
    color: var(--muted);
    font-size: 13px;
  }

  .pending-receipt-card dd {
    min-width: 0;
    margin: 0;
    color: var(--ink);
    overflow-wrap: anywhere;
  }

  .pending-receipt-card .mini-button {
    width: 100%;
    min-height: 44px;
  }

  .pending-receipt-card-list > .empty-cell {
    margin: 0;
    padding: 14px;
    border: 1px solid var(--line);
    border-radius: 10px;
  }
}
</style>
