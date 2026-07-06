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
      <button class="ghost-button" @click="loadPreview">
        <RefreshCw />
        预览
      </button>
      <button class="primary-button" :disabled="downloading" @click="downloadCsv">
        <Download />
        {{ downloading ? '下载中' : '导出 CSV' }}
      </button>
    </div>

    <div v-if="error" class="inline-alert">{{ error }}</div>

    <section class="panel">
      <div class="panel-head">
        <div>
          <h2>利润排名导出</h2>
          <span>CSV 文件可直接用 Excel 打开</span>
        </div>
        <span>{{ rows.length }} 条</span>
      </div>
      <DataTable :columns="columns" :rows="rows" row-key="id">
        <template #storeName="{ row }">
          <strong>{{ row.storeName }}</strong>
          <small>{{ row.brandName }} · {{ row.area || '-' }}</small>
        </template>
        <template #income="{ row }">{{ currency(Number(row.income)) }}</template>
        <template #net="{ row }">{{ currency(Number(row.net)) }}</template>
        <template #margin="{ row }">{{ percent(Number(row.margin)) }}</template>
      </DataTable>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Download, RefreshCw } from 'lucide-vue-next';
import DataTable, { type TableColumn } from '../components/DataTable.vue';
import { downloadProfitRankingCsv, fetchFinanceDashboard, type ProfitDashboard } from '../services/api';

const selectedMonth = ref(currentMonth());
const selectedBrandId = ref('all');
const dashboard = ref<ProfitDashboard | null>(null);
const downloading = ref(false);
const error = ref('');

const monthOptions = computed(() => dashboard.value?.months.length ? dashboard.value.months : [selectedMonth.value]);
const brandOptions = computed(() => dashboard.value?.brands ?? []);
const rows = computed(() => (dashboard.value?.entries ?? []) as unknown as Record<string, unknown>[]);

const columns: TableColumn[] = [
  { key: 'storeName', label: '门店' },
  { key: 'income', label: '实收收入', align: 'right' },
  { key: 'net', label: '净利润', align: 'right' },
  { key: 'margin', label: '净利率', align: 'right' },
  { key: 'risk', label: '状态' }
];

onMounted(loadPreview);

async function loadPreview() {
  error.value = '';
  try {
    const brandId = selectedBrandId.value === 'all' ? undefined : Number(selectedBrandId.value);
    dashboard.value = await fetchFinanceDashboard(selectedMonth.value, brandId);
    selectedMonth.value = dashboard.value.summary.month;
  } catch {
    error.value = '导出预览加载失败，请确认后端服务和登录状态正常。';
  }
}

async function downloadCsv() {
  downloading.value = true;
  error.value = '';
  try {
    const brandId = selectedBrandId.value === 'all' ? undefined : Number(selectedBrandId.value);
    const blob = await downloadProfitRankingCsv(selectedMonth.value, brandId);
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `门店利润排名_${selectedMonth.value}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  } catch {
    error.value = '导出失败，请稍后重试。';
  } finally {
    downloading.value = false;
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
</script>
