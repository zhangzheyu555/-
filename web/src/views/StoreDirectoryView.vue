<template>
  <section class="view-stack">
    <div class="filter-bar">
      <select v-model="selectedBrand">
        <option value="全部品牌">全部品牌</option>
        <option v-for="brand in brands" :key="brand">{{ brand }}</option>
      </select>
      <select v-model="selectedStatus">
        <option value="全部状态">全部状态</option>
        <option>营业中</option>
        <option>待开业</option>
        <option>已停业</option>
      </select>
      <button class="primary-button">
        <Building2 />
        新增门店
      </button>
    </div>

    <section class="panel">
      <div class="panel-head">
        <h2>门店档案</h2>
        <span>{{ visibleRows.length }} 家</span>
      </div>
      <DataTable :columns="columns" :rows="visibleRows" row-key="id">
        <template #name="{ row }">
          <strong>{{ row.name }}</strong>
          <small>{{ row.code }}</small>
        </template>
        <template #status="{ row }">
          <StatusTag :label="String(row.status)" :tone="row.status === '营业中' ? 'good' : 'neutral'" />
        </template>
        <template #net="{ row }">{{ currency(Number(row.net)) }}</template>
        <template #margin="{ row }">{{ percent(Number(row.margin)) }}</template>
      </DataTable>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { Building2 } from 'lucide-vue-next';
import DataTable, { type TableColumn } from '../components/DataTable.vue';
import StatusTag from '../components/StatusTag.vue';
import { stores } from '../data/mock';

const selectedBrand = ref('全部品牌');
const selectedStatus = ref('全部状态');

const brands = computed(() => [...new Set(stores.map((store) => store.brand))]);
const visibleRows = computed(() => stores
  .filter((store) => selectedBrand.value === '全部品牌' || store.brand === selectedBrand.value)
  .filter((store) => selectedStatus.value === '全部状态' || store.status === selectedStatus.value) as unknown as Record<string, unknown>[]);

const columns: TableColumn[] = [
  { key: 'name', label: '门店' },
  { key: 'brand', label: '品牌' },
  { key: 'area', label: '区域' },
  { key: 'manager', label: '负责人' },
  { key: 'status', label: '状态' },
  { key: 'net', label: '净利润', align: 'right' },
  { key: 'margin', label: '净利率', align: 'right' }
];

function currency(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(value);
}

function percent(value: number) {
  return `${(value * 100).toFixed(1)}%`;
}
</script>
