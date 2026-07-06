<template>
  <section class="view-stack">
    <div class="filter-bar">
      <button class="primary-button" @click="loadUsers">
        <RefreshCw />
        刷新用户
      </button>
      <span class="soft-label">当前阶段为只读权限总览</span>
    </div>

    <div v-if="error" class="inline-alert">{{ error }}</div>

    <section class="panel">
      <div class="panel-head">
        <h2>用户权限</h2>
        <span>{{ users.length }} 个账号</span>
      </div>
      <DataTable :columns="columns" :rows="rows" row-key="id">
        <template #displayName="{ row }">
          <strong>{{ row.displayName }}</strong>
          <small>{{ row.username }}</small>
        </template>
        <template #roleLabel="{ row }">
          <StatusTag :label="String(row.roleLabel)" tone="info" />
        </template>
        <template #enabled="{ row }">
          <StatusTag :label="row.enabled ? '启用' : '停用'" :tone="row.enabled ? 'good' : 'bad'" />
        </template>
        <template #storeScope="{ row }">
          {{ scopeText(row.storeScope) }}
        </template>
      </DataTable>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { RefreshCw } from 'lucide-vue-next';
import DataTable, { type TableColumn } from '../components/DataTable.vue';
import StatusTag from '../components/StatusTag.vue';
import { fetchUsers, type UserRecord } from '../services/api';

const users = ref<UserRecord[]>([]);
const error = ref('');
const rows = computed(() => users.value as unknown as Record<string, unknown>[]);

const columns: TableColumn[] = [
  { key: 'displayName', label: '用户' },
  { key: 'roleLabel', label: '角色' },
  { key: 'storeId', label: '直属门店' },
  { key: 'storeScope', label: '数据范围' },
  { key: 'enabled', label: '状态' }
];

onMounted(loadUsers);

async function loadUsers() {
  error.value = '';
  try {
    users.value = await fetchUsers();
  } catch {
    error.value = '用户权限加载失败。当前接口仅管理员可查看。';
  }
}

function scopeText(value: unknown) {
  if (!Array.isArray(value) || value.length === 0) {
    return '无门店范围';
  }
  return value.includes('all') ? '全部门店' : value.join('、');
}
</script>
