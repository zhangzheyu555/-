<script setup lang="ts">
import { onMounted, ref } from 'vue'
import PageHeader from '../components/common/PageHeader.vue'
import { getAuditLogs, type OperationLog } from '../api/operations'
import { useForegroundReload } from '../composables/useForegroundReload'
import { formatAuditAction, formatAuditReason, formatAuditTarget, rawAuditTarget } from '../utils/auditLogDisplay'

const logs = ref<OperationLog[]>([])
const loading = ref(false)
const error = ref('')

const { markFresh } = useForegroundReload(async () => {
  const loaded = await loadLogs()
  if (!loaded) throw new Error(error.value || '操作日志加载失败')
}, {
  canReload: () => !loading.value,
})

async function loadLogs() {
  loading.value = true
  error.value = ''
  try {
    logs.value = await getAuditLogs(120)
    markFresh()
    return true
  } catch (loadError) {
    error.value = loadError instanceof Error ? loadError.message : '操作日志加载失败'
    return false
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void loadLogs()
})
</script>

<template>
  <section class="page-panel logs-page">
    <PageHeader />

    <div v-if="error" class="error-box logs-error" role="alert">
      <span>{{ error }}</span>
      <button type="button" :disabled="loading" @click="loadLogs">重试</button>
    </div>
    <div v-if="loading && !logs.length" class="empty-state">正在读取操作日志...</div>

    <section v-else class="content-card">
      <div class="table-wrap">
        <table>
          <thead>
            <tr>
              <th>时间</th>
              <th>操作人</th>
              <th>动作</th>
              <th>对象</th>
              <th>门店</th>
              <th>月份</th>
              <th>说明</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="log in logs" :key="log.id">
              <td>{{ log.createdAt || '-' }}</td>
              <td>{{ log.operatorName || log.operatorId || '-' }}</td>
              <td>{{ formatAuditAction(log.action) }}</td>
              <td :title="rawAuditTarget(log)">{{ formatAuditTarget(log) }}</td>
              <td>{{ log.storeId || '全部门店' }}</td>
              <td>{{ log.month || '-' }}</td>
              <td>{{ formatAuditReason(log.reason) }}</td>
            </tr>
            <tr v-if="!logs.length">
              <td colspan="7" class="empty-cell">暂无操作日志。</td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>
  </section>
</template>

<style scoped>
.logs-page {
  display: grid;
  gap: 18px;
}

.logs-error {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.logs-error button {
  flex: none;
  border: 0;
  background: transparent;
  color: currentColor;
  font-weight: 700;
}

</style>
