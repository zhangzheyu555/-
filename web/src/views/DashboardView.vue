<template>
  <section class="view-stack">
    <div class="filter-bar">
      <select v-model="selectedMonth">
        <option v-for="month in monthOptions" :key="month">{{ month }}</option>
      </select>
      <select v-model="selectedBrandId">
        <option value="all">全部品牌</option>
        <option v-for="brand in brandOptions" :key="brand.id" :value="String(brand.id)">{{ brand.name }}</option>
      </select>
      <button class="primary-button" @click="loadDashboard">
        <BarChart3 />
        生成报表
      </button>
      <span v-if="loading" class="soft-label">加载中</span>
    </div>

    <div v-if="error" class="inline-alert">{{ error }}</div>

    <div class="metric-grid">
      <MetricCard label="营业总收入" :value="currency(summary.sales)" :foot="summaryFoot" tone="info" />
      <MetricCard label="净利润" :value="currency(summary.net)" :foot="profitFoot" :tone="summary.net >= 0 ? 'good' : 'bad'" />
      <MetricCard label="净利率" :value="percent(summary.margin)" foot="按实收收入口径" :tone="summary.margin >= 0.12 ? 'good' : 'warn'" />
      <MetricCard label="风险门店" :value="String(summary.riskStoreCount)" foot="亏损或低净利率" :tone="summary.riskStoreCount ? 'bad' : 'good'" />
    </div>

    <div class="content-grid">
      <section class="panel wide-panel">
        <div class="panel-head">
          <h2>利润排名</h2>
          <span>{{ summary.month }}</span>
        </div>
        <DataTable :columns="columns" :rows="rankRows" row-key="id">
          <template #storeName="{ row }">
            <strong>{{ row.storeName }}</strong>
            <small>{{ row.brandName }} · {{ row.area || '-' }}</small>
          </template>
          <template #income="{ row }">{{ currency(Number(row.income)) }}</template>
          <template #net="{ row }">{{ currency(Number(row.net)) }}</template>
          <template #margin="{ row }">{{ percent(Number(row.margin)) }}</template>
          <template #risk="{ row }">
            <StatusTag :label="String(row.risk)" :tone="riskTone(String(row.risk))" />
          </template>
        </DataTable>
      </section>

      <section class="panel">
        <div class="panel-head">
          <h2>经营趋势</h2>
          <span>近 6 月</span>
        </div>
        <div class="trend-list">
          <div v-for="item in trendRows" :key="item.month" class="trend-row">
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
import { computed, onMounted, ref } from 'vue';
import { BarChart3 } from 'lucide-vue-next';
import DataTable, { type TableColumn } from '../components/DataTable.vue';
import MetricCard from '../components/MetricCard.vue';
import StatusTag from '../components/StatusTag.vue';
import { fetchFinanceDashboard, type ProfitDashboard, type ProfitSummary } from '../services/api';

const selectedMonth = ref(currentMonth());
const selectedBrandId = ref('all');
const dashboard = ref<ProfitDashboard | null>(null);
const loading = ref(false);
const error = ref('');

const emptySummary: ProfitSummary = {
  month: selectedMonth.value,
  storeCount: 0,
  entryCount: 0,
  sales: 0,
  income: 0,
  costSum: 0,
  expenseSum: 0,
  net: 0,
  margin: 0,
  riskStoreCount: 0
};

const monthOptions = computed(() => dashboard.value?.months.length ? dashboard.value.months : [selectedMonth.value]);
const brandOptions = computed(() => dashboard.value?.brands ?? []);
const summary = computed(() => dashboard.value?.summary ?? emptySummary);
const summaryFoot = computed(() => `${summary.value.entryCount} 条记录 / ${summary.value.storeCount} 家门店`);
const profitFoot = computed(() => summary.value.net >= 0 ? '利润为正' : '存在亏损');
const rankRows = computed(() => (dashboard.value?.entries ?? []) as unknown as Record<string, unknown>[]);
const trendRows = computed(() => {
  const rows = dashboard.value?.trend ?? [];
  const max = Math.max(...rows.map((item) => Math.abs(item.net)), 1);
  return rows.map((item) => ({
    ...item,
    rate: Math.max(8, Math.round((Math.abs(item.net) / max) * 100))
  }));
});

const columns: TableColumn[] = [
  { key: 'storeName', label: '门店' },
  { key: 'income', label: '实收收入', align: 'right' },
  { key: 'net', label: '净利润', align: 'right' },
  { key: 'margin', label: '净利率', align: 'right' },
  { key: 'risk', label: '状态' }
];

onMounted(loadDashboard);

async function loadDashboard() {
  loading.value = true;
  error.value = '';
  try {
    const brandId = selectedBrandId.value === 'all' ? undefined : Number(selectedBrandId.value);
    dashboard.value = await fetchFinanceDashboard(selectedMonth.value, brandId);
    selectedMonth.value = dashboard.value.summary.month;
  } catch {
    error.value = '利润看板加载失败，请确认后端服务和登录状态正常。';
  } finally {
    loading.value = false;
  }
}

function currentMonth() {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`;
}

function currency(value: number) {
  return new Intl.NumberFormat('zh-CN', { style: 'currency', currency: 'CNY', maximumFractionDigits: 0 }).format(value);
}

function percent(value: number) {
  return `${(value * 100).toFixed(1)}%`;
}

function riskTone(risk: string): 'good' | 'warn' | 'bad' {
  return risk === '亏损' ? 'bad' : risk === '关注' ? 'warn' : 'good';
}
</script>
