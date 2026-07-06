<template>
  <section class="view-stack">
    <div class="filter-bar">
      <select v-model.number="limit">
        <option :value="50">最近 50 条</option>
        <option :value="200">最近 200 条</option>
        <option :value="500">最近 500 条</option>
      </select>
      <button class="primary-button" @click="loadLogs">
        <RefreshCw />
        刷新日志
      </button>
    </div>

    <div v-if="error" class="inline-alert">{{ error }}</div>

    <section class="panel">
      <div class="panel-head">
        <h2>操作日志</h2>
        <span>{{ logs.length }} 条</span>
      </div>
      <DataTable :columns="columns" :rows="rows" row-key="id">
        <template #operatorName="{ row }">
          <strong>{{ row.operatorName || '-' }}</strong>
          <small>ID {{ row.operatorId || '-' }}</small>
        </template>
        <template #targetId="{ row }">
          <strong>{{ row.targetType }}</strong>
          <small>{{ row.targetId || '-' }}</small>
        </template>
      </DataTable>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { RefreshCw } from 'lucide-vue-next';
import DataTable, { type TableColumn } from '../components/DataTable.vue';
import { fetchOperationLogs, type OperationLogRecord } from '../services/api';

const limit = ref(200);
const logs = ref<OperationLogRecord[]>([]);
const error = ref('');
const rows = computed(() => logs.value as unknown as Record<string, unknown>[]);

const columns: TableColumn[] = [
  { key: 'createdAt', label: '时间' },
  { key: 'operatorName', label: '操作人' },
  { key: 'action', label: '动作' },
  { key: 'targetId', label: '对象' },
  { key: 'storeId', label: '门店' },
  { key: 'month', label: '月份' },
  { key: 'reason', label: '说明' }
];

onMounted(loadLogs);

async function loadLogs() {
  error.value = '';
  try {
    logs.value = await fetchOperationLogs(limit.value);
  } catch {
    error.value = '操作日志加载失败，请确认当前账号权限和后端服务状态。';
  }
}
</script>
