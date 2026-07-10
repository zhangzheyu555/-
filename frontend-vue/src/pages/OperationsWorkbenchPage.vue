<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import DataHealthPanel from '../components/operations/DataHealthPanel.vue'
import DataImportPanel from '../components/operations/DataImportPanel.vue'
import MigrationStatusPanel from '../components/operations/MigrationStatusPanel.vue'
import OperationsAnalysisPanel from '../components/operations/OperationsAnalysisPanel.vue'
import OperationsDoneReview from '../components/operations/OperationsDoneReview.vue'
import OperationsElemePanel from '../components/operations/OperationsElemePanel.vue'
import OperationsExamPanel from '../components/operations/OperationsExamPanel.vue'
import OperationsInventoryCheckPanel from '../components/operations/OperationsInventoryCheckPanel.vue'
import OperationsStatCards from '../components/operations/OperationsStatCards.vue'
import OperationsTrainingPanel from '../components/operations/OperationsTrainingPanel.vue'
import OperationsTodoPanel from '../components/operations/OperationsTodoPanel.vue'
import PlatformAccountPanel from '../components/operations/PlatformAccountPanel.vue'
import {
  buildHealthItems,
  buildImportItems,
  buildPlatformItems,
  useOperationsStore,
  type OperationsTab,
} from '../stores/operations'
import type { RoleTodoItem } from '../api/todos'

const route = useRoute()
const router = useRouter()
const operations = useOperationsStore()

const healthItems = computed(() => buildHealthItems(operations.$state))
const importItems = computed(() => buildImportItems(operations.auditLogs, operations.todos))
const platformItems = computed(() => buildPlatformItems(operations.stores, operations.todos))
const dataIssueCount = computed(() => healthItems.value.filter((item) => item.status === '数据异常' || item.status === '需要检查').length)
const headline = computed(() => {
  const elemeOrders = operations.elemeSummary?.orderCount || 0
  return `今天有 ${dataIssueCount.value} 条数据状态需要关注，${platformItems.value.filter((item) => item.loginStatus !== '正常').length} 个门店平台账号需要检查，饿了么订单 ${elemeOrders} 单，${operations.escalatedCount} 条事项已上报老板。`
})

const tabs: Array<{ id: OperationsTab; label: string }> = [
  { id: 'overview', label: '今日运营重点' },
  { id: 'todos', label: '待我处理' },
  { id: 'analysis', label: '数据分析' },
  { id: 'training', label: '新人培训' },
  { id: 'exam', label: '考试系统' },
  { id: 'inventory-check', label: '店铺盘存' },
  { id: 'eleme', label: '饿了么订单' },
  { id: 'data-health', label: '数据健康' },
  { id: 'imports', label: '数据导入' },
  { id: 'platform', label: '平台账号' },
  { id: 'migration', label: '迁移状态' },
  { id: 'logs', label: '操作日志' },
  { id: 'done', label: '已处理复盘' },
]

const tabRoutes: Record<OperationsTab, string> = {
  overview: '/operations',
  todos: '/operations',
  analysis: '/operations/analysis',
  training: '/operations/training',
  exam: '/exam-center',
  'inventory-check': '/operations/inventory-check',
  eleme: '/operations/eleme',
  'data-health': '/operations/data-health',
  imports: '/operations/imports',
  platform: '/operations/platform',
  migration: '/operations/migration',
  logs: '/operations/logs',
  done: '/operations',
}

const pageTitle = computed(() => {
  const routeTitle = route.meta.title
  if (typeof routeTitle === 'string' && routeTitle) return routeTitle
  return '运营中心'
})

function queryTab() {
  const metaTab = route.meta.operationsTab
  if (typeof metaTab === 'string') return metaTab
  const value = route.query.tab
  return Array.isArray(value) ? value[0] : value
}

function selectTab(tab: OperationsTab) {
  void router.push(tabRoutes[tab])
}

async function refresh() {
  await operations.load()
}

function openTodo(item: RoleTodoItem) {
  if (item.sourceModule?.includes('migration') || item.title?.includes('迁移')) {
    selectTab('migration')
    return
  }
  if (item.title?.includes('平台') || item.summary?.includes('账号')) {
    selectTab('platform')
    return
  }
  if (item.title?.includes('饿了么') || item.summary?.includes('饿了么')) {
    selectTab('eleme')
    return
  }
  if (item.title?.includes('培训') || item.summary?.includes('培训')) {
    selectTab('training')
    return
  }
  if (item.title?.includes('考试') || item.summary?.includes('考试')) {
    selectTab('exam')
    return
  }
  if (item.title?.includes('盘存') || item.summary?.includes('盘存')) {
    selectTab('inventory-check')
    return
  }
  selectTab('data-health')
}

async function resolveTodo(item: RoleTodoItem) {
  const note = window.prompt('请输入处理说明', '运营已完成数据核对或配置检查')
  if (note === null) return
  await operations.resolveTodo(item, note)
}

async function escalateTodo(item: RoleTodoItem) {
  const reason = window.prompt('请输入上报老板的原因', '该运营事项需要老板确认处理方向')
  if (reason === null) return
  await operations.escalateTodo(item, reason)
}

watch(
  () => route.fullPath,
  () => operations.setTab(queryTab()),
  { immediate: true },
)

onMounted(() => {
  void refresh()
})
</script>

<template>
  <section class="page-panel operations-page">
    <div class="page-head">
      <div>
        <h2>{{ pageTitle }}</h2>
      </div>
      <button class="ghost-button" type="button" :disabled="operations.loading" @click="refresh">
        <RefreshCw :size="16" />
        刷新
      </button>
    </div>

    <div v-if="operations.error" class="error-box">{{ operations.error }}</div>
    <div v-if="operations.actionMessage" class="success-box">{{ operations.actionMessage }}</div>
    <div v-if="operations.loading && !operations.todos.length && !operations.auditLogs.length" class="empty-state">
      正在读取运营中心...
    </div>

    <template v-else>
      <OperationsStatCards
        :data-issue-count="dataIssueCount"
        :import-todo-count="importItems.length"
        :platform-issue-count="platformItems.filter((item) => item.loginStatus !== '正常').length"
        :escalated-count="operations.escalatedCount"
      />

      <div class="operations-focus">
        <b>今日运营重点</b>
        <span>{{ headline }}</span>
      </div>

      <div class="operations-tabs">
        <button
          v-for="tab in tabs"
          :key="tab.id"
          class="operations-tab"
          :class="{ active: operations.activeTab === tab.id }"
          type="button"
          @click="selectTab(tab.id)"
        >
          <b>{{ tab.label }}</b>
        </button>
      </div>

      <div v-if="operations.activeTab === 'overview'" class="operations-stack">
        <OperationsTodoPanel
          :items="operations.openTodos.slice(0, 6)"
          :actioning-id="operations.actioningId"
          @open="openTodo"
          @resolve="resolveTodo"
          @escalate="escalateTodo"
        />
        <DataHealthPanel :items="healthItems" />
        <OperationsElemePanel
          :status="operations.elemeStatus"
          :summary="operations.elemeSummary"
          :month="operations.elemeMonth"
          :loading="operations.elemeLoading"
          @load="operations.loadEleme"
        />
        <MigrationStatusPanel :status="operations.migrationStatus" :legacy-preview="operations.legacyPreview" />
      </div>

      <OperationsTodoPanel
        v-else-if="operations.activeTab === 'todos'"
        :items="operations.openTodos"
        :actioning-id="operations.actioningId"
        @open="openTodo"
        @resolve="resolveTodo"
        @escalate="escalateTodo"
      />

      <OperationsAnalysisPanel v-else-if="operations.activeTab === 'analysis'" />
      <OperationsTrainingPanel v-else-if="operations.activeTab === 'training'" />
      <OperationsExamPanel v-else-if="operations.activeTab === 'exam'" :stores="operations.stores" />
      <OperationsInventoryCheckPanel v-else-if="operations.activeTab === 'inventory-check'" :stores="operations.stores" />
      <OperationsElemePanel
        v-else-if="operations.activeTab === 'eleme'"
        :status="operations.elemeStatus"
        :summary="operations.elemeSummary"
        :month="operations.elemeMonth"
        :loading="operations.elemeLoading"
        @load="operations.loadEleme"
      />
      <DataHealthPanel v-else-if="operations.activeTab === 'data-health'" :items="healthItems" />
      <DataImportPanel v-else-if="operations.activeTab === 'imports'" :items="importItems" />
      <PlatformAccountPanel v-else-if="operations.activeTab === 'platform'" :items="platformItems" />
      <MigrationStatusPanel
        v-else-if="operations.activeTab === 'migration'"
        :status="operations.migrationStatus"
        :legacy-preview="operations.legacyPreview"
      />

      <section v-else-if="operations.activeTab === 'logs'" class="content-card">
        <div class="table-heading">
          <div>
            <h3>操作日志</h3>
          </div>
        </div>
        <div class="table-wrap">
          <table>
            <thead>
              <tr>
                <th>时间</th>
                <th>操作人</th>
                <th>动作</th>
                <th>对象</th>
                <th>门店</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="log in operations.auditLogs" :key="log.id">
                <td>{{ log.createdAt || '-' }}</td>
                <td>{{ log.operatorName || log.operatorId || '-' }}</td>
                <td>{{ log.action }}</td>
                <td>{{ log.targetType || '-' }} {{ log.targetId || '' }}</td>
                <td>{{ log.storeId || '全部门店' }}</td>
                <td>{{ log.reason || '-' }}</td>
              </tr>
              <tr v-if="!operations.auditLogs.length">
                <td colspan="6" class="empty-cell">当前没有操作日志。</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <OperationsDoneReview v-else-if="operations.activeTab === 'done'" :items="operations.doneItems" />
    </template>
  </section>
</template>

<style scoped>
.operations-page {
  display: grid;
  gap: 18px;
}

.operations-focus {
  display: grid;
  gap: 5px;
  padding: 16px;
  border: 1px solid rgba(238, 126, 62, 0.22);
  border-left: 4px solid var(--primary);
  border-radius: 14px;
  background: var(--primary-soft);
}

.operations-focus b {
  color: var(--primary-dark);
  font-size: 14px;
}

.operations-focus span {
  font-size: 16px;
  font-weight: 900;
}

.operations-tabs {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.operations-tab {
  min-height: 46px;
  padding: 12px;
  border: 1px solid var(--line);
  border-radius: 12px;
  background: #fff;
  text-align: left;
}

.operations-tab b {
  display: block;
}

.operations-tab b {
  font-size: 14px;
}

.operations-tab.active {
  border-color: rgba(238, 126, 62, 0.34);
  background: var(--primary-soft);
  color: var(--primary-dark);
}

.operations-stack {
  display: grid;
  gap: 18px;
}

@media (max-width: 980px) {
  .operations-tabs {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 560px) {
  .operations-tabs {
    grid-template-columns: 1fr;
  }
}
</style>
