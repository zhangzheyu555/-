<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertTriangle, Bot, ClipboardList, GraduationCap, RefreshCw, Store, UserRound, WalletCards } from 'lucide-vue-next'
import { ApiError } from '../api/http'
import { getEmployeeWorkbench, type EmployeeWorkbench, type EmployeeWorkbenchItem } from '../api/employeeWorkbench'

const router = useRouter()
const loading = ref(false)
const errorMessage = ref('')
const workbench = ref<EmployeeWorkbench | null>(null)

const summaryTiles = computed(() => {
  const summary = workbench.value?.workSummary
  return [
    { label: '待处理', value: summary?.pending ?? 0 },
    { label: '已逾期', value: summary?.overdue ?? 0 },
    { label: '待重考', value: summary?.retakePending ?? 0 },
    { label: '已完成', value: summary?.completed ?? 0 },
  ]
})

async function loadWorkbench() {
  loading.value = true
  errorMessage.value = ''
  try {
    workbench.value = await getEmployeeWorkbench()
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '员工工作台加载失败，请稍后重试'
  } finally {
    loading.value = false
  }
}

function itemIcon(item: EmployeeWorkbenchItem) {
  return item.type === 'ASSISTANT' ? Bot : GraduationCap
}

function openItem(item: EmployeeWorkbenchItem) {
  if (!item.route) return
  void router.push(item.route)
}

onMounted(() => {
  void loadWorkbench()
})
</script>

<template>
  <section class="employee-workbench page-container">
    <div class="employee-head">
      <div>
        <p class="section-kicker">员工工作台</p>
        <h1>{{ workbench?.profile.displayName || '当前员工' }}</h1>
        <p class="employee-subtitle">{{ workbench?.store.storeName || '所属门店待加载' }}</p>
      </div>
      <button class="ghost-button" type="button" :disabled="loading" @click="loadWorkbench">
        <RefreshCw :size="17" :class="{ spinning: loading }" />
        刷新
      </button>
    </div>

    <div v-if="errorMessage" class="notice warning">
      <AlertTriangle :size="19" />
      <span>{{ errorMessage }}</span>
    </div>

    <div v-else-if="workbench" class="employee-content">
      <section class="profile-band">
        <div class="profile-line">
          <span class="profile-icon"><UserRound :size="20" /></span>
          <div>
            <span class="profile-label">姓名</span>
            <strong>{{ workbench.profile.displayName }}</strong>
          </div>
        </div>
        <div class="profile-line">
          <span class="profile-icon"><Store :size="20" /></span>
          <div>
            <span class="profile-label">门店</span>
            <strong>{{ workbench.store.storeName }}</strong>
            <small>{{ workbench.store.brandName || '品牌未配置' }} · {{ workbench.store.storeId }}</small>
          </div>
        </div>
      </section>

      <section class="profile-action-band">
        <div class="section-title compact">
          <WalletCards :size="20" />
          <div>
            <h2>我的资料与工资</h2>
            <p>查看个人档案、所属门店、最近工资和还缺哪些资料。</p>
          </div>
        </div>
        <button type="button" @click="router.push('/employee/profile')">查看资料</button>
      </section>

      <section class="summary-grid" aria-label="工作汇总">
        <div v-for="tile in summaryTiles" :key="tile.label" class="summary-tile">
          <span>{{ tile.label }}</span>
          <strong>{{ tile.value }}</strong>
        </div>
      </section>

      <section class="work-section">
        <div class="section-title">
          <ClipboardList :size="20" />
          <h2>当前工作事项</h2>
        </div>
        <div v-if="workbench.workItems.length" class="work-list">
          <button
            v-for="item in workbench.workItems"
            :key="item.id"
            class="work-row"
            type="button"
            @click="openItem(item)"
          >
            <span class="work-icon" :class="{ urgent: item.priority === 'HIGH' }">
              <component :is="itemIcon(item)" :size="20" />
            </span>
            <span class="work-copy">
              <strong>{{ item.title }}</strong>
              <small>{{ item.description }}</small>
            </span>
            <span class="work-action">{{ item.actionText }}</span>
          </button>
        </div>
        <div v-else class="empty-state">当前没有待处理事项。</div>
      </section>

      <section class="assistant-band" :class="{ disabled: !workbench.assistant.enabled }">
        <div>
          <div class="section-title compact">
            <Bot :size="20" />
            <h2>员工服务助手</h2>
          </div>
          <p>{{ workbench.assistant.message }}</p>
        </div>
        <button
          type="button"
          :disabled="!workbench.assistant.enabled"
          @click="workbench.assistant.route && router.push(workbench.assistant.route)"
        >
          打开助手
        </button>
      </section>
    </div>

    <div v-else class="loading-state">正在加载员工工作台...</div>
  </section>
</template>

<style scoped>
.employee-workbench {
  display: grid;
  gap: 18px;
  padding-bottom: 32px;
}

.employee-head,
.profile-band,
.assistant-band,
.profile-action-band,
.work-row,
.notice {
  display: flex;
  align-items: center;
}

.employee-head {
  justify-content: space-between;
  gap: 18px;
}

.section-kicker {
  margin: 0 0 4px;
  color: var(--ds-primary);
  font-size: 13px;
  font-weight: 700;
}

.employee-head h1 {
  margin: 0;
  color: var(--ds-ink);
  font-size: 28px;
  line-height: 1.2;
}

.employee-subtitle {
  margin: 6px 0 0;
  color: var(--ds-secondary);
  font-size: 15px;
}

.ghost-button,
.assistant-band button,
.work-action {
  display: inline-flex;
  min-height: 38px;
  align-items: center;
  justify-content: center;
  border-radius: 6px;
  font-weight: 700;
}

.ghost-button {
  gap: 8px;
  padding: 0 14px;
  border: 1px solid var(--ds-line);
  background: #fff;
  color: var(--ds-secondary);
}

.spinning {
  animation: spin 0.8s linear infinite;
}

.notice {
  gap: 10px;
  padding: 14px 16px;
  border: 1px solid #f2d391;
  border-radius: 7px;
  background: #fff7e8;
  color: #855b10;
}

.employee-content {
  display: grid;
  gap: 16px;
}

.profile-band,
.profile-action-band,
.assistant-band,
.work-section,
.loading-state {
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: #fff;
}

.profile-band {
  justify-content: space-between;
  gap: 16px;
  padding: 18px;
}

.profile-action-band {
  justify-content: space-between;
  gap: 16px;
  padding: 16px 18px;
}

.profile-line {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
}

.profile-icon,
.work-icon {
  display: inline-flex;
  width: 42px;
  height: 42px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 8px;
  background: var(--ds-primary-soft);
  color: var(--ds-primary-hover);
}

.profile-line div,
.work-copy {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.profile-label,
.summary-tile span,
.work-copy small,
.assistant-band p {
  color: var(--ds-secondary);
}

.profile-line strong,
.work-copy strong {
  color: var(--ds-ink);
}

.profile-line small {
  color: var(--ds-muted);
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.summary-tile {
  display: grid;
  gap: 8px;
  min-height: 92px;
  padding: 16px;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: #fff;
}

.summary-tile strong {
  color: var(--ds-ink);
  font-size: 28px;
  line-height: 1;
}

.work-section {
  display: grid;
  gap: 0;
  overflow: hidden;
}

.section-title {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 16px 18px;
  border-bottom: 1px solid var(--ds-line);
  color: var(--ds-ink);
}

.section-title.compact {
  padding: 0;
  border-bottom: 0;
}

.section-title.compact p {
  margin: 5px 0 0;
  color: var(--ds-secondary);
  font-size: 13px;
}

.section-title h2 {
  margin: 0;
  font-size: 18px;
}

.work-list {
  display: grid;
}

.work-row {
  width: 100%;
  gap: 13px;
  padding: 14px 18px;
  border: 0;
  border-top: 1px solid var(--ds-line);
  background: #fff;
  color: inherit;
  text-align: left;
}

.work-row:first-child {
  border-top: 0;
}

.work-row:hover {
  background: var(--ds-surface-muted);
}

.work-icon.urgent {
  background: #fff1db;
  color: #b26706;
}

.work-copy {
  flex: 1;
}

.work-action {
  min-width: 82px;
  padding: 0 12px;
  background: var(--ds-primary);
  color: #fff;
  font-size: 14px;
}

.empty-state,
.loading-state {
  padding: 24px;
  color: var(--ds-secondary);
}

.assistant-band {
  justify-content: space-between;
  gap: 16px;
  padding: 18px;
}

.assistant-band p {
  margin: 8px 0 0;
}

.assistant-band button {
  min-width: 112px;
  padding: 0 16px;
  border: 0;
  background: var(--ds-primary);
  color: #fff;
}

.profile-action-band button {
  min-width: 112px;
  min-height: 38px;
  border: 0;
  border-radius: 6px;
  background: var(--ds-primary);
  color: #fff;
  font-weight: 800;
}

.assistant-band.disabled button {
  cursor: not-allowed;
  background: var(--ds-disabled, #cfd8d6);
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 768px) {
  .employee-workbench {
    gap: 14px;
  }

  .employee-head,
  .profile-band,
  .profile-action-band,
  .assistant-band {
    align-items: stretch;
    flex-direction: column;
  }

  .employee-head h1 {
    font-size: 24px;
  }

  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .summary-tile {
    min-height: 82px;
  }

  .work-row {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .work-action {
    width: 100%;
  }
}
</style>
