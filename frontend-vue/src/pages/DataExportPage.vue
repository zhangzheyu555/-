<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Download, ReceiptText, RefreshCw, WalletCards } from 'lucide-vue-next'
import { downloadExpenseCsv, downloadProfitRankingCsv, downloadSalaryCsv } from '../api/reports'
import BrandSelect from '../components/common/BrandSelect.vue'
import { useProfitStore } from '../stores/profit'

const profit = useProfitStore()
const selectedMonth = ref('')
const selectedBrandId = ref('')
const downloading = ref('')
const message = ref('')
const error = ref('')

const monthOptions = computed(() => profit.months)
const brandOptions = computed(() => profit.brands)
const exportMonth = computed(() => selectedMonth.value || profit.summary.month || profit.month)

async function loadOptions() {
  error.value = ''
  try {
    await profit.load()
    selectedMonth.value = profit.month || profit.months[0] || ''
  } catch (loadError) {
    error.value = loadError instanceof Error ? loadError.message : '导出筛选项加载失败'
  }
}

async function exportProfitRanking() {
  downloading.value = 'profit'
  message.value = ''
  error.value = ''
  try {
    const month = exportMonth.value
    await downloadProfitRankingCsv(
      { month, brandId: selectedBrandId.value || undefined },
      `利润排行-${month || '当前月份'}.csv`,
    )
    message.value = '利润排行 CSV 已开始下载。'
  } catch (downloadError) {
    error.value = downloadError instanceof Error ? downloadError.message : '利润排行导出失败'
  } finally {
    downloading.value = ''
  }
}

async function exportExpenses() {
  downloading.value = 'expenses'
  message.value = ''
  error.value = ''
  try {
    const month = exportMonth.value
    await downloadExpenseCsv({ month, brandId: selectedBrandId.value || undefined }, `报销记录-${month || '当前月份'}.csv`)
    message.value = '报销记录 CSV 已开始下载。'
  } catch (downloadError) {
    error.value = downloadError instanceof Error ? downloadError.message : '报销记录导出失败'
  } finally {
    downloading.value = ''
  }
}

async function exportSalaries() {
  downloading.value = 'salary'
  message.value = ''
  error.value = ''
  try {
    const month = exportMonth.value
    await downloadSalaryCsv({ month, brandId: selectedBrandId.value || undefined }, `员工工资-${month || '当前月份'}.csv`)
    message.value = '员工工资 CSV 已开始下载。'
  } catch (downloadError) {
    error.value = downloadError instanceof Error ? downloadError.message : '员工工资导出失败'
  } finally {
    downloading.value = ''
  }
}

onMounted(() => {
  void loadOptions()
})
</script>

<template>
  <section class="page-panel export-page">
    <div class="page-head">
      <div>
        <h2>数据导出</h2>
      </div>
      <button class="ghost-button" type="button" :disabled="profit.loading" @click="loadOptions">
        <RefreshCw :size="16" />
        刷新
      </button>
    </div>

    <div v-if="message" class="success-box">{{ message }}</div>
    <div v-if="error" class="error-box">{{ error }}</div>

    <section class="content-card export-filter-card">
      <div>
        <h3>导出筛选</h3>
      </div>
      <div class="export-filters">
        <label>
          月份
          <select v-model="selectedMonth" :disabled="profit.loading">
            <option v-for="month in monthOptions" :key="month" :value="month">{{ month }}</option>
          </select>
        </label>
        <label>
          品牌
          <BrandSelect v-model="selectedBrandId" :brands="brandOptions" :disabled="profit.loading" />
        </label>
      </div>
    </section>

    <div class="export-grid">
      <article class="export-card ready">
        <div class="export-card-head">
          <Download :size="22" />
        </div>
        <h3>全部门店月度利润汇总</h3>
        <button class="primary-button submit-inline" type="button" :disabled="downloading === 'profit'" @click="exportProfitRanking">
          {{ downloading === 'profit' ? '正在导出...' : '下载利润排行 CSV' }}
        </button>
      </article>

      <article class="export-card ready">
        <div class="export-card-head">
          <ReceiptText :size="22" />
        </div>
        <h3>报销记录</h3>
        <button class="primary-button submit-inline" type="button" :disabled="downloading === 'expenses'" @click="exportExpenses">
          {{ downloading === 'expenses' ? '正在导出...' : '下载报销 CSV' }}
        </button>
      </article>

      <article class="export-card ready">
        <div class="export-card-head">
          <WalletCards :size="22" />
        </div>
        <h3>工资报表导出</h3>
        <button class="primary-button submit-inline" type="button" :disabled="downloading === 'salary'" @click="exportSalaries">
          {{ downloading === 'salary' ? '正在导出...' : '下载工资 CSV' }}
        </button>
      </article>
    </div>
  </section>
</template>

<style scoped>
.export-page {
  display: grid;
  gap: 18px;
}

.export-filter-card {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 14px;
}

.export-filter-card h3 {
  margin: 0 0 3px;
  font-size: 18px;
}

.export-filters {
  display: flex;
  align-items: flex-end;
  gap: 10px;
  flex-wrap: wrap;
}

.export-filters label {
  display: grid;
  gap: 6px;
  color: var(--muted);
  font-size: 12px;
  font-weight: 900;
}

.export-filters select {
  min-width: 150px;
  min-height: 40px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}

.export-filters :deep(.brand-select-wrap select) {
  min-width: 150px;
  min-height: 40px;
  padding: 8px 10px;
  border: 1px solid var(--line);
  border-radius: 10px;
  background: #fff;
}

.export-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
}

.export-card {
  display: grid;
  align-content: start;
  gap: 10px;
  min-height: 150px;
  padding: 16px;
  border: 1px solid var(--line);
  border-radius: 14px;
  background: #fff;
}

.export-card.ready {
  border-color: rgba(30, 158, 106, 0.24);
  background: #fbfffd;
}

.export-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.export-card h3 {
  margin: 0;
}

@media (max-width: 980px) {
  .export-grid {
    grid-template-columns: 1fr;
  }

  .export-filter-card {
    align-items: stretch;
    flex-direction: column;
  }
}

@media (max-width: 720px) {
  .export-filters,
  .export-filters label,
  .export-filters select,
  .export-filter-card .ghost-button {
    width: 100%;
  }
}
</style>
