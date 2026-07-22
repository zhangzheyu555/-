<script setup lang="ts">
import { computed } from 'vue'
import { BarChart3, ClipboardPenLine, Download, FileSpreadsheet, ReceiptText } from 'lucide-vue-next'
import PageHeader from '../../components/common/PageHeader.vue'
import { canUseFinanceProfitImport, PERMISSIONS } from '../../permissions/permissions'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const canImportMonthlySummary = computed(() => canUseFinanceProfitImport(auth.role, auth.permissions))
const modules = computed(() => [
  { label: '利润分析', description: '查看授权门店的利润概览和月度利润表。', to: '/profit', icon: BarChart3, permission: PERMISSIONS.FINANCE_PROFIT_READ },
  { label: '经营数据录入', description: '录入授权门店的月度经营数据。', to: '/data-entry', icon: ClipboardPenLine, permission: PERMISSIONS.FINANCE_PROFIT_WRITE },
  { label: '导入月度汇总', description: '上传单店单月汇总，校验并确认后写入经营数据。', to: '/finance/import', icon: FileSpreadsheet, permission: PERMISSIONS.FINANCE_PROFIT_IMPORT, financeImportOnly: true },
  { label: '报销审核', description: '查看报销记录并处理审核流程。', to: '/expenses', icon: ReceiptText, permission: PERMISSIONS.EXPENSE_READ },
  { label: '员工工资', description: '核对、审核并按权限处理工资。', to: '/finance/salary', icon: ClipboardPenLine, permission: PERMISSIONS.SALARY_READ },
  { label: '数据导出', description: '导出授权范围内的经营数据。', to: '/export', icon: Download, permission: PERMISSIONS.FINANCE_EXPORT },
].filter((item) => (
  item.financeImportOnly ? canImportMonthlySummary.value : auth.hasPermission(item.permission)
)))
</script>

<template>
  <section class="page-panel finance-workspace">
    <PageHeader />
    <nav v-if="modules.length" class="workspace-links" aria-label="财务业务入口">
      <RouterLink v-for="item in modules" :key="item.to" :to="item.to">
        <component :is="item.icon" :size="20" aria-hidden="true" />
        <span><b>{{ item.label }}</b><small>{{ item.description }}</small></span>
        <span aria-hidden="true">进入</span>
      </RouterLink>
    </nav>
    <div v-else class="empty-state">当前账号尚未获得财务模块权限，请联系老板配置。</div>
  </section>
</template>

<style scoped>
.finance-workspace {
  display: grid;
  gap: 18px;
}

.workspace-links {
  display: grid;
  overflow: hidden;
  border: 1px solid var(--ds-line);
  border-radius: 8px;
  background: #fff;
}

.workspace-links a {
  display: grid;
  min-height: 68px;
  grid-template-columns: 30px minmax(0, 1fr) auto;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid var(--ds-line);
  color: var(--ds-secondary);
  text-decoration: none;
}

.workspace-links a:last-child {
  border-bottom: 0;
}

.workspace-links a:hover {
  background: var(--ds-surface-muted);
  color: var(--ds-primary-hover);
}

.workspace-links a > span:nth-child(2) {
  display: grid;
  gap: 3px;
}

.workspace-links b {
  color: var(--ds-ink);
  font-size: 15px;
}

.workspace-links small {
  color: var(--ds-muted);
  font-size: 13px;
}
</style>
