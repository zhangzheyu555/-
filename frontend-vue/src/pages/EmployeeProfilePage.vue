<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  AlertTriangle,
  Bot,
  CheckCircle2,
  ClipboardList,
  GraduationCap,
  IdCard,
  RefreshCw,
  Store,
  UserRound,
  WalletCards,
} from 'lucide-vue-next'
import { ApiError } from '../api/http'
import { getEmployeeProfile, type EmployeeProfile, type EmployeeProfileChecklistItem } from '../api/employeeWorkbench'

const router = useRouter()
const loading = ref(false)
const errorMessage = ref('')
const profile = ref<EmployeeProfile | null>(null)

const salaryHighlights = computed(() => {
  const salary = profile.value?.salary
  return [
    { label: '基本工资', value: formatMoney(salary?.base), muted: !salary?.base },
    { label: '应发工资', value: formatMoney(salary?.gross), muted: !salary?.gross },
    { label: '实发工资', value: formatMoney(salary?.netPay), muted: !salary?.netPay },
    { label: '提成/绩效', value: formatMoney((salary?.commission || 0) + (salary?.performance || 0)), muted: !salary?.available },
  ]
})

async function loadProfile() {
  loading.value = true
  errorMessage.value = ''
  try {
    profile.value = await getEmployeeProfile()
  } catch (error) {
    errorMessage.value = error instanceof ApiError
      ? error.message
      : '我的资料加载失败，请稍后重试。'
  } finally {
    loading.value = false
  }
}

function formatMoney(value?: number | null) {
  if (value === null || value === undefined) return '未生成'
  return `¥${Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`
}

function display(value?: string | number | null, fallback = '未填写') {
  if (value === null || value === undefined || String(value).trim() === '') return fallback
  return String(value)
}

function checklistClass(item: EmployeeProfileChecklistItem) {
  return {
    high: item.severity === 'HIGH',
    normal: item.severity === 'NORMAL',
    low: item.severity === 'LOW',
  }
}

onMounted(() => {
  void loadProfile()
})
</script>

<template>
  <section class="employee-profile page-container">
    <div class="employee-head">
      <div>
        <p class="section-kicker">我的资料</p>
        <h1>{{ profile?.profile.displayName || '当前员工' }}</h1>
        <p class="employee-subtitle">
          {{ profile?.store.storeName || '所属门店加载中' }} · {{ profile?.profile.username || '账号加载中' }}
        </p>
      </div>
      <button class="ghost-button" type="button" :disabled="loading" @click="loadProfile">
        <RefreshCw :size="17" :class="{ spinning: loading }" />
        刷新
      </button>
    </div>

    <div v-if="errorMessage" class="notice warning">
      <AlertTriangle :size="19" />
      <span>{{ errorMessage }}</span>
    </div>

    <div v-else-if="profile" class="profile-grid">
      <section class="identity-panel">
        <header>
          <div>
            <p>身份档案</p>
            <h2>{{ profile.archive.name || profile.profile.displayName }}</h2>
          </div>
          <span :class="['status-pill', profile.archive.linked ? 'ok' : 'warn']">
            {{ profile.archive.linked ? '已关联档案' : '待补档案' }}
          </span>
        </header>
        <div class="identity-main">
          <div class="avatar"><UserRound :size="28" /></div>
          <div class="identity-fields">
            <div><span>登录账号</span><strong>{{ profile.profile.username }}</strong></div>
            <div><span>员工编号</span><strong>{{ display(profile.archive.employeeId, '待匹配') }}</strong></div>
            <div><span>岗位</span><strong>{{ display(profile.archive.position, '待填写') }}</strong></div>
            <div><span>入职日期</span><strong>{{ display(profile.archive.hireDate, '待填写') }}</strong></div>
          </div>
        </div>
        <p class="panel-message">{{ profile.archive.message }}</p>
      </section>

      <section class="store-panel">
        <span class="panel-icon"><Store :size="22" /></span>
        <div>
          <p>所属门店</p>
          <h2>{{ profile.store.storeName }}</h2>
          <span>{{ display(profile.store.brandName, '品牌未配置') }} · {{ profile.store.storeId }}</span>
        </div>
      </section>

      <section class="salary-panel">
        <header>
          <div class="section-title compact">
            <WalletCards :size="22" />
            <div>
              <h2>工资信息</h2>
              <span>{{ profile.salary.month || '当前暂无工资月份' }}</span>
            </div>
          </div>
          <span :class="['status-pill', profile.salary.available ? 'ok' : 'warn']">
            {{ profile.salary.statusLabel }}
          </span>
        </header>

        <div class="salary-total">
          <span>{{ profile.salary.available ? '最近一笔工资' : '工资状态' }}</span>
          <strong>{{ profile.salary.available ? formatMoney(profile.salary.netPay || profile.salary.gross) : '未生成' }}</strong>
          <p>{{ profile.salary.message }}</p>
        </div>

        <div class="salary-grid">
          <div v-for="item in salaryHighlights" :key="item.label" :class="{ muted: item.muted }">
            <span>{{ item.label }}</span>
            <strong>{{ item.value }}</strong>
          </div>
        </div>

        <div class="salary-details">
          <div><span>出勤</span><strong>{{ display(profile.salary.attendance, '待录入') }}</strong></div>
          <div><span>加班工资</span><strong>{{ formatMoney(profile.salary.overtime) }}</strong></div>
          <div><span>工服扣/返</span><strong>{{ formatMoney(profile.salary.deductUniform) }} / {{ formatMoney(profile.salary.returnUniform) }}</strong></div>
          <div><span>剩余休假</span><strong>{{ profile.salary.vacationLeft ?? '未生成' }}</strong></div>
          <div><span>审核时间</span><strong>{{ display(profile.salary.reviewedAt, '未审核') }}</strong></div>
          <div><span>发放时间</span><strong>{{ display(profile.salary.paidAt, '未发放') }}</strong></div>
        </div>
      </section>

      <section class="checklist-panel">
        <div class="section-title">
          <ClipboardList :size="21" />
          <h2>还缺什么</h2>
        </div>
        <div class="check-list">
          <div v-for="item in profile.checklist" :key="item.key" class="check-row" :class="checklistClass(item)">
            <span class="check-icon">
              <CheckCircle2 v-if="item.severity === 'LOW'" :size="19" />
              <AlertTriangle v-else :size="19" />
            </span>
            <div>
              <strong>{{ item.title }}</strong>
              <small>{{ item.description }}</small>
            </div>
            <em>{{ item.state }}</em>
          </div>
        </div>
      </section>

      <section class="quick-panel">
        <button type="button" @click="router.push('/employee')">
          <IdCard :size="20" />
          回员工工作台
        </button>
        <button type="button" @click="router.push('/employee/exams')">
          <GraduationCap :size="20" />
          培训考试
        </button>
        <button type="button" @click="router.push('/employee-assistant')">
          <Bot :size="20" />
          员工服务助手
        </button>
      </section>
    </div>

    <div v-else class="loading-state">正在加载我的资料...</div>
  </section>
</template>

<style scoped>
.employee-profile {
  display: grid;
  gap: 18px;
  padding-bottom: 32px;
}

.employee-head,
.identity-panel header,
.salary-panel header,
.store-panel,
.section-title,
.ghost-button,
.notice,
.quick-panel button,
.check-row {
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

.ghost-button {
  gap: 8px;
  min-height: 38px;
  justify-content: center;
  padding: 0 14px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: #fff;
  color: var(--ds-secondary);
  font-weight: 700;
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

.profile-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(280px, 0.6fr);
  gap: 16px;
}

.identity-panel,
.store-panel,
.salary-panel,
.checklist-panel,
.quick-panel,
.loading-state {
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: #fff;
}

.identity-panel,
.salary-panel,
.checklist-panel {
  display: grid;
  gap: 16px;
  padding: 18px;
}

.identity-panel header,
.salary-panel header {
  justify-content: space-between;
  gap: 14px;
}

.identity-panel p,
.store-panel p {
  margin: 0;
  color: var(--ds-secondary);
  font-size: 13px;
}

.identity-panel h2,
.store-panel h2,
.salary-panel h2 {
  margin: 3px 0 0;
  color: var(--ds-ink);
  font-size: 20px;
}

.status-pill {
  display: inline-flex;
  min-height: 28px;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  padding: 0 10px;
  font-size: 13px;
  font-weight: 800;
}

.status-pill.ok {
  border: 1px solid #a8dec8;
  background: #eefbf4;
  color: #167849;
}

.status-pill.warn {
  border: 1px solid #efd9a7;
  background: #fff8e7;
  color: #946315;
}

.identity-main {
  display: grid;
  grid-template-columns: 58px minmax(0, 1fr);
  gap: 16px;
}

.avatar,
.panel-icon,
.check-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  background: var(--ds-primary-soft);
  color: var(--ds-primary-hover);
}

.avatar {
  width: 58px;
  height: 58px;
  border-radius: 12px;
}

.identity-fields,
.salary-details {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}

.identity-fields div,
.salary-details div {
  display: grid;
  gap: 4px;
  min-width: 0;
}

.identity-fields span,
.salary-details span,
.salary-grid span,
.salary-total span,
.section-title span {
  color: var(--ds-secondary);
  font-size: 13px;
}

.identity-fields strong,
.salary-details strong,
.salary-grid strong {
  min-width: 0;
  color: var(--ds-ink);
  font-size: 15px;
  overflow-wrap: anywhere;
}

.panel-message {
  padding: 12px;
  border-radius: 7px;
  background: var(--ds-surface-muted);
}

.store-panel {
  gap: 14px;
  padding: 18px;
  align-self: start;
}

.panel-icon {
  width: 52px;
  height: 52px;
  flex: none;
  border-radius: 10px;
}

.store-panel span {
  color: var(--ds-muted);
  font-size: 13px;
}

.salary-panel {
  grid-column: 1 / -1;
}

.section-title {
  gap: 9px;
  color: var(--ds-ink);
}

.section-title.compact {
  gap: 10px;
}

.section-title h2 {
  margin: 0;
  font-size: 18px;
}

.salary-total {
  display: grid;
  gap: 6px;
  padding: 16px;
  border-radius: 8px;
  background: #f6fbfa;
}

.salary-total strong {
  color: var(--ds-ink);
  font-size: 32px;
  line-height: 1.1;
}

.salary-total p {
  margin: 0;
  color: var(--ds-secondary);
}

.salary-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

.salary-grid div {
  display: grid;
  gap: 8px;
  min-height: 82px;
  padding: 14px;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
}

.salary-grid div.muted strong {
  color: var(--ds-muted);
}

.checklist-panel {
  grid-column: 1 / -1;
  padding: 0;
  overflow: hidden;
}

.checklist-panel .section-title {
  padding: 16px 18px;
  border-bottom: 1px solid var(--ds-line);
}

.check-list {
  display: grid;
}

.check-row {
  gap: 12px;
  padding: 14px 18px;
  border-top: 1px solid var(--ds-line);
}

.check-row:first-child {
  border-top: 0;
}

.check-icon {
  width: 38px;
  height: 38px;
  flex: none;
  border-radius: 8px;
}

.check-row.high .check-icon {
  background: #fff4df;
  color: #a8630a;
}

.check-row.normal .check-icon {
  background: #f1f4f8;
  color: #596979;
}

.check-row.low .check-icon {
  background: #edf8f2;
  color: #188657;
}

.check-row div {
  display: grid;
  flex: 1;
  gap: 3px;
}

.check-row strong {
  color: var(--ds-ink);
}

.check-row small {
  color: var(--ds-secondary);
}

.check-row em {
  flex: none;
  color: var(--ds-secondary);
  font-style: normal;
  font-weight: 800;
}

.quick-panel {
  grid-column: 1 / -1;
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  padding: 14px;
}

.quick-panel button {
  gap: 8px;
  min-height: 44px;
  justify-content: center;
  border: 1px solid var(--ds-line);
  border-radius: 7px;
  background: #fff;
  color: var(--ds-ink);
  font-weight: 800;
}

.quick-panel button:hover {
  background: var(--ds-surface-muted);
}

.loading-state {
  padding: 24px;
  color: var(--ds-secondary);
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

@media (max-width: 768px) {
  .employee-profile {
    gap: 14px;
  }

  .employee-head,
  .identity-panel header,
  .salary-panel header {
    align-items: stretch;
    flex-direction: column;
  }

  .employee-head h1 {
    font-size: 24px;
  }

  .profile-grid,
  .identity-main,
  .identity-fields,
  .salary-details,
  .salary-grid,
  .quick-panel {
    grid-template-columns: 1fr;
  }

  .salary-total strong {
    font-size: 28px;
  }

  .check-row {
    align-items: flex-start;
    flex-wrap: wrap;
  }

  .check-row em {
    width: 100%;
    padding-left: 50px;
  }
}
</style>
