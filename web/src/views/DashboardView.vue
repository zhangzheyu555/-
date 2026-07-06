<template>
  <section class="view-stack">
    <div class="filter-bar">
      <select v-model="selectedMonth">
        <option v-for="month in months" :key="month">{{ month }}</option>
      </select>
      <select v-model="selectedBrand">
        <option value="全部品牌">全部品牌</option>
        <option v-for="brand in brands" :key="brand">{{ brand }}</option>
      </select>
      <button class="primary-button">
        <BarChart3 />
        生成报表
      </button>
    </div>

    <div class="metric-grid">
      <MetricCard label="营业总收入" :value="currency(totalSales)" foot="样例门店汇总" tone="info" />
      <MetricCard label="净利润" :value="currency(totalNet)" :foot="profitFoot" :tone="totalNet >= 0 ? 'good' : 'bad'" />
      <MetricCard label="净利率" :value="percent(netMargin)" foot="按实收收入口径" :tone="netMargin >= 0.12 ? 'good' : 'warn'" />
      <MetricCard label="风险门店" :value="String(riskStores.length)" foot="亏损或低毛利" :tone="riskStores.length ? 'bad' : 'good'" />
    </div>

    <div class="content-grid">
      <section class="panel wide-panel">
        <div class="panel-head">
          <h2>利润排名</h2>
          <span>{{ selectedMonth }}</span>
        </div>
        <DataTable :columns="columns" :rows="rankRows" row-key="id">
          <template #name="{ row }">
            <strong>{{ row.name }}</strong>
            <small>{{ row.brand }} · {{ row.area }}</small>
          </template>
          <template #sales="{ row }">{{ currency(Number(row.sales)) }}</template>
          <template #net="{ row }">{{ currency(Number(row.net)) }}</template>
          <template #margin="{ row }">{{ percent(Number(row.margin)) }}</template>
          <template #risk="{ row }">
            <StatusTag :label="riskLabel(String(row.risk))" :tone="riskTone(String(row.risk))" />
          </template>
        </DataTable>
      </section>

      <section class="panel">
        <div class="panel-head">
          <h2>经营趋势</h2>
          <span>近 4 月</span>
        </div>
        <div class="trend-list">
          <div v-for="item in trend" :key="item.month" class="trend-row">
            <span>{{ item.month }}</span>
            <div class="bar-track"><div class="bar-fill" :style="{ width: `${item.rate}%` }"></div></div>
            <strong>{{ currency(item.net) }}</strong>
          </div>
        </div>
      </section>
    </div>
  </section>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { BarChart3 } from 'lucide-vue-next';
import DataTable, { type TableColumn } from '../components/DataTable.vue';
import MetricCard from '../components/MetricCard.vue';
import StatusTag from '../components/StatusTag.vue';
import { months, stores } from '../data/mock';

const selectedMonth = ref(months[0]);
const selectedBrand = ref('全部品牌');

const brands = computed(() => [...new Set(stores.map((store) => store.brand))]);
const visibleStores = computed(() => stores.filter((store) => selectedBrand.value === '全部品牌' || store.brand === selectedBrand.value));
const totalSales = computed(() => visibleStores.value.reduce((sum, store) => sum + store.sales, 0));
const totalNet = computed(() => visibleStores.value.reduce((sum, store) => sum + store.net, 0));
const netMargin = computed(() => totalSales.value ? totalNet.value / totalSales.value : 0);
const riskStores = computed(() => visibleStores.value.filter((store) => store.risk !== 'good'));
const profitFoot = computed(() => totalNet.value >= 0 ? '利润为正' : '存在亏损');

const rankRows = computed(() => [...visibleStores.value].sort((a, b) => b.net - a.net) as unknown as Record<string, unknown>[]);
const trend = computed(() => months.map((month, index) => {
  const net = Math.round(totalNet.value * (0.76 + index * 0.08));
  return { month, net, rate: Math.max(18, Math.min(100, Math.round((net / Math.max(totalNet.value, 1)) * 100))) };
}));

const columns: TableColumn[] = [
  { key: 'name', label: '门店' },
  { key: 'sales', label: '营业额', align: 'right' },
  { key: 'net', label: '净利润', align: 'right' },
  { key: 'margin', label: '净利率', align: 'right' },
  { key: 'risk', label: '状态' }
];

function currency(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(value);
}

function percent(value: number) {
  return `${(value * 100).toFixed(1)}%`;
}

function riskLabel(risk: string) {
  return risk === 'bad' ? '亏损' : risk === 'warn' ? '关注' : '健康';
}

function riskTone(risk: string) {
  return risk === 'bad' ? 'bad' : risk === 'warn' ? 'warn' : 'good';
}
</script>
