<script setup lang="ts">
import { ArrowRight, Building2, PackageCheck, Truck } from 'lucide-vue-next'
import type { WarehouseInfo } from '../../api/warehouse'

defineProps<{
  warehouses: WarehouseInfo[]
}>()

const emit = defineEmits<{
  open: [warehouse: WarehouseInfo]
}>()

function typeLabel(type: WarehouseInfo['type']) {
  return type === 'CENTRAL' ? '总仓' : '区域分仓'
}

function regionLabel(region: WarehouseInfo['regionCode']) {
  return region === 'JINGZHOU' ? '荆州区域' : '山东区域'
}
</script>

<template>
  <div class="network-overview">
    <section class="warehouse-flow" aria-label="供货拓扑">
      <div class="flow-node flow-node--external"><Truck :size="18" /><span>外部供应商</span></div>
      <ArrowRight :size="18" aria-hidden="true" />
      <div class="flow-node flow-node--primary"><Building2 :size="18" /><span>荆州总仓</span></div>
      <ArrowRight :size="18" aria-hidden="true" />
      <div class="flow-destinations">
        <span>荆州门店</span>
        <span>山东分仓 → 山东门店</span>
      </div>
    </section>

    <section class="content-card warehouse-list-card">
      <div class="table-heading">
        <div>
          <h3>全部仓库</h3>
          <span>仓库范围由老板授权，库存与操作权限由后端按仓库校验。</span>
        </div>
      </div>
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>仓库</th>
              <th>类型</th>
              <th>区域</th>
              <th>上级仓库</th>
              <th>外部采购</th>
              <th>门店供货</th>
              <th>状态</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="warehouse in warehouses" :key="warehouse.id">
              <td><b>{{ warehouse.name }}</b><small>{{ warehouse.code }}</small></td>
              <td>{{ typeLabel(warehouse.type) }}</td>
              <td>{{ regionLabel(warehouse.regionCode) }}</td>
              <td>{{ warehouse.parentWarehouseName || (warehouse.type === 'CENTRAL' ? '—' : '荆州总仓') }}</td>
              <td>{{ warehouse.externalPurchaseAllowed ? '允许' : '不允许' }}</td>
              <td>{{ warehouse.storeSupplyAllowed ? '允许' : '不允许' }}</td>
              <td><span class="status-badge" :class="warehouse.enabled ? 'ok' : 'muted'">{{ warehouse.enabled ? '启用' : '停用' }}</span></td>
              <td><button class="mini-button" type="button" @click="emit('open', warehouse)">查看库存</button></td>
            </tr>
            <tr v-if="!warehouses.length"><td colspan="8" class="empty-cell">当前账号没有可访问的仓库。</td></tr>
          </tbody>
        </table>
      </div>
    </section>

    <p class="network-note"><PackageCheck :size="16" />内部调拨只转移库存及成本，不计入销售收入或重复采购费用。</p>
  </div>
</template>

<style scoped>
.network-overview {
  display: grid;
  gap: 14px;
}

.warehouse-flow {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
  padding: 12px 14px;
  overflow-x: auto;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: var(--ds-surface);
  color: var(--ds-muted);
  scrollbar-width: none;
}

.warehouse-flow::-webkit-scrollbar {
  display: none;
}

.flow-node,
.flow-destinations,
.network-note {
  display: flex;
  align-items: center;
}

.flow-node {
  min-height: 36px;
  flex: 0 0 auto;
  gap: 7px;
  padding: 7px 10px;
  border-radius: 6px;
  background: var(--ds-surface-muted);
  color: var(--ds-secondary);
  font-weight: 700;
}

.flow-node--primary {
  background: var(--ds-primary-soft);
  color: var(--ds-primary-hover);
}

.flow-destinations {
  flex: 0 0 auto;
  gap: 8px;
}

.flow-destinations span {
  padding: 7px 10px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  color: var(--ds-secondary);
  white-space: nowrap;
}

.warehouse-list-card {
  min-width: 0;
}

td small {
  display: block;
  margin-top: 2px;
  color: var(--ds-muted);
}

.network-note {
  gap: 7px;
  margin: 0;
  color: var(--ds-secondary);
  font-size: 13px;
}

@media (max-width: 640px) {
  .warehouse-flow {
    padding: 10px;
  }
}
</style>
