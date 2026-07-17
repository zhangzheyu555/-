<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import PageHeader from '../components/common/PageHeader.vue'
import { getAuditLogs, type OperationLog } from '../api/operations'
import { formatAuditAction, formatAuditReason, formatAuditTarget, rawAuditTarget } from '../utils/auditLogDisplay'

const logs = ref<OperationLog[]>([])
const loading = ref(false)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    logs.value = await getAuditLogs(120)
  } catch (loadError) {
    error.value = loadError instanceof Error ? loadError.message : '操作日志加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  void load()
})
</script>

<template>
  <section class="page-panel logs-page">
    <PageHeader>
      <template #actions>
        <button class="ghost-button" type="button" :disabled="loading" @click="load">
          <RefreshCw :size="16" />刷新
        </button>
      </template>
    </PageHeader>

    <div v-if="error" class="error-box">{{ error }}</div>
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

</style>
