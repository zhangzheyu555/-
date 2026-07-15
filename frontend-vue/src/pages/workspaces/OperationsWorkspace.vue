<script setup lang="ts">
import { computed } from 'vue'
import { ClipboardCheck, GraduationCap, Settings2 } from 'lucide-vue-next'
import PageHeader from '../../components/common/PageHeader.vue'
import { PERMISSIONS } from '../../permissions/permissions'
import { useAuthStore } from '../../stores/auth'

const auth = useAuthStore()
const modules = computed(() => [
  {
    label: '督导巡店',
    description: '发起巡检、记录问题并完成整改复核。',
    to: '/operations/inspection',
    icon: ClipboardCheck,
    permission: PERMISSIONS.INSPECTION_READ,
  },
  {
    label: '整改复核',
    description: '查看店长已提交的整改证据，填写备注后通过或驳回。',
    to: '/operations/inspection/reviews',
    icon: ClipboardCheck,
    permission: PERMISSIONS.INSPECTION_MANAGE,
  },
  {
    label: '培训考试',
    description: '维护课程、题库、考试发布和成绩报表。',
    to: '/operations/exams',
    icon: GraduationCap,
    permission: PERMISSIONS.EXAM_MANAGE,
  },
  {
    label: '平台配置',
    description: '查看门店平台接入状态和授权配置。',
    to: '/platform-login',
    icon: Settings2,
    permission: PERMISSIONS.PLATFORM_READ,
  },
].filter((item) => auth.hasPermission(item.permission)))
</script>

<template>
  <section class="page-panel operations-workspace">
    <PageHeader subtitle="按当前账号的有效权限进入运营模块。" />
    <nav v-if="modules.length" class="workspace-links" aria-label="运营业务入口">
      <RouterLink v-for="item in modules" :key="item.to" :to="item.to">
        <component :is="item.icon" :size="20" aria-hidden="true" />
        <span><b>{{ item.label }}</b><small>{{ item.description }}</small></span>
        <span aria-hidden="true">进入</span>
      </RouterLink>
    </nav>
    <div v-else class="empty-state">当前账号尚未获得运营模块权限，请联系老板配置。</div>
  </section>
</template>

<style scoped>
.operations-workspace {
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
