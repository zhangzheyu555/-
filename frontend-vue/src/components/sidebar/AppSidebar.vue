<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { AlertTriangle, BarChart3, Bot, ClipboardList, ClipboardPenLine, Database, Download, GraduationCap, Home, LogOut, Package, ReceiptText, Route, ShieldCheck, Truck, Undo2, UserRound, UserRoundCog, Warehouse } from 'lucide-vue-next'
import { useAuthStore } from '../../stores/auth'

interface MenuItem {
  label: string
  to: string
  icon: unknown
}

interface MenuGroup {
  title?: string
  items: MenuItem[]
}

const auth = useAuthStore()
const route = useRoute()
withDefaults(defineProps<{
  mode?: 'desktop' | 'mobile'
}>(), {
  mode: 'desktop',
})
const emit = defineEmits<{
  navigate: []
  logout: []
}>()

function bossMenuGroups(): MenuGroup[] {
  return [
    {
      title: '经营分析',
      items: [
        { label: '今日待办', to: '/todos', icon: ClipboardList },
        { label: '利润概览', to: '/profit', icon: BarChart3 },
        { label: '利润表', to: '/profit-table', icon: BarChart3 },
        { label: '门店详情', to: '/store-detail', icon: Home },
      ],
    },
    {
      title: '录入与审核',
      items: [
        { label: '数据录入', to: '/data-entry', icon: Database },
        { label: '报销栏', to: '/expenses', icon: ReceiptText },
        { label: '员工工资', to: '/salary', icon: ClipboardPenLine },
        { label: '数据导出', to: '/export', icon: Download },
      ],
    },
    {
      title: '门店运营',
      items: [
        { label: '督导巡店', to: '/inspection', icon: ClipboardPenLine },
        { label: '仓库中心', to: '/warehouse', icon: Warehouse },
        { label: '培训考试', to: '/exam-center', icon: GraduationCap },
        { label: '门店管理', to: '/stores', icon: Home },
      ],
    },
    {
      title: '系统记录',
      items: [
        { label: '操作日志', to: '/logs', icon: ClipboardPenLine },
      ],
    },
  ]
}

function adminMenuGroups(): MenuGroup[] {
  return [
    {
      title: '工作台',
      items: [
        { label: '今日待办', to: '/todos', icon: ClipboardList },
      ],
    },
    {
      title: '管理中心',
      items: [
        { label: '门店管理', to: '/stores', icon: Home },
        { label: '账号权限', to: '/users', icon: UserRoundCog },
        { label: '平台配置', to: '/platform-login', icon: UserRoundCog },
        { label: '培训考试', to: '/exam-center', icon: GraduationCap },
        { label: '操作日志', to: '/logs', icon: ClipboardPenLine },
      ],
    },
  ]
}

const menuGroups = computed<MenuGroup[]>(() => {
  if (auth.role === 'ADMIN') {
    return adminMenuGroups()
  }
  if (auth.role === 'BOSS' || auth.role === 'OWNER') {
    return bossMenuGroups()
  }
  const byRole: Record<string, MenuGroup[]> = {
    STORE_MANAGER: [
      {
        title: '门店日常',
        items: [
          { label: '今日待办', to: '/todos', icon: ClipboardList },
          { label: '本店库存', to: '/warehouse', icon: Package },
          { label: '向公司仓库叫货', to: '/warehouse?section=requisition', icon: ClipboardList },
          { label: '我的叫货单', to: '/warehouse?section=orders', icon: ReceiptText },
          { label: '确认收货', to: '/warehouse?section=receipts', icon: Truck },
          { label: '配送退货', to: '/warehouse?section=returns', icon: Undo2 },
        ],
      },
      {
        title: '本店数据',
        items: [
          { label: '数据录入', to: '/data-entry', icon: Database },
          { label: '报销栏', to: '/expenses', icon: ReceiptText },
          { label: '门店详情', to: '/store-detail', icon: Home },
          { label: '员工工资', to: '/salary', icon: ClipboardPenLine },
          { label: '培训考试', to: '/exam-center', icon: GraduationCap },
        ],
      },
    ],
    WAREHOUSE: [
      {
        title: '仓库作业',
        items: [
          { label: '今日待办', to: '/todos', icon: ClipboardList },
          { label: '仓库中心', to: '/warehouse', icon: Warehouse },
          { label: '采购入库', to: '/warehouse/purchase', icon: Truck },
          { label: '配送退货单', to: '/warehouse/returns', icon: Undo2 },
          { label: '库存预警', to: '/warehouse/alerts', icon: AlertTriangle },
        ],
      },
      {
        title: '库存资料',
        items: [
          { label: '商品档案', to: '/warehouse/items', icon: Package },
          { label: '出入库记录', to: '/warehouse/movements', icon: ReceiptText },
          { label: '入库记录', to: '/warehouse/receipts', icon: ReceiptText },
        ],
      },
    ],
    FINANCE: [
      {
        title: '财务处理',
        items: [
          { label: '今日待办', to: '/todos', icon: ClipboardList },
          { label: '数据录入', to: '/data-entry', icon: Database },
          { label: '报销栏', to: '/expenses', icon: ReceiptText },
          { label: '利润表', to: '/profit-table', icon: BarChart3 },
          { label: '员工工资', to: '/salary', icon: ClipboardPenLine },
          { label: '数据导出', to: '/export', icon: Download },
        ],
      },
    ],
    SUPERVISOR: [
      {
        title: '督导巡店',
        items: [
          { label: '今日待办', to: '/todos', icon: ClipboardList },
          { label: '督导巡店', to: '/inspection', icon: ClipboardPenLine },
          { label: '巡检记录', to: '/inspection/records', icon: Route },
          { label: '发起巡检', to: '/inspection/tasks', icon: ClipboardList },
          { label: '稽核标准', to: '/inspection/rules', icon: ShieldCheck },
        ],
      },
    ],
    OPERATIONS: [
      {
        title: '运营工作',
        items: [
          { label: '今日待办', to: '/todos', icon: ClipboardList },
          { label: '运营中心', to: '/operations', icon: Route },
          { label: '数据分析', to: '/operations/analysis', icon: BarChart3 },
          { label: '店铺盘存', to: '/operations/inventory-check', icon: Package },
          { label: '饿了么订单', to: '/operations/eleme', icon: ReceiptText },
        ],
      },
      {
        title: '运营配置',
        items: [
          { label: '新人培训', to: '/operations/training', icon: ClipboardPenLine },
          { label: '培训考试', to: '/exam-center', icon: GraduationCap },
          { label: '数据健康', to: '/operations/data-health', icon: ShieldCheck },
          { label: '平台账号', to: '/operations/platform', icon: UserRoundCog },
        ],
      },
    ],
    OPS: [
      {
        title: '运营工作',
        items: [
          { label: '运营中心', to: '/operations', icon: Route },
          { label: '数据分析', to: '/operations/analysis', icon: BarChart3 },
          { label: '店铺盘存', to: '/operations/inventory-check', icon: Package },
          { label: '饿了么订单', to: '/operations/eleme', icon: ReceiptText },
        ],
      },
      {
        title: '运营配置',
        items: [
          { label: '新人培训', to: '/operations/training', icon: ClipboardPenLine },
          { label: '培训考试', to: '/exam-center', icon: GraduationCap },
          { label: '数据健康', to: '/operations/data-health', icon: ShieldCheck },
          { label: '平台账号', to: '/operations/platform', icon: UserRoundCog },
        ],
      },
    ],
    EMPLOYEE: [
      {
        title: '学习任务',
        items: [
          { label: '我的考试', to: '/exam-center', icon: GraduationCap },
        ],
      },
    ],
  }
  return byRole[auth.role] || []
})

const assistantRoles = new Set(['ADMIN', 'BOSS', 'OWNER', 'FINANCE', 'STORE_MANAGER', 'SUPERVISOR', 'WAREHOUSE', 'OPERATIONS', 'OPS'])
const utilityMenuItems = computed<MenuItem[]>(() => assistantRoles.has(auth.role)
  ? [{ label: '门店经营助手', to: '/assistant', icon: Bot }]
  : [])

function isMenuActive(to: string) {
  const [path, queryText = ''] = to.split('?')
  const activeMenu = typeof route.meta.activeMenu === 'string' ? route.meta.activeMenu : ''
  if (activeMenu) {
    return path === activeMenu
  }
  if (path === '/inspection') {
    return route.path === '/inspection' || route.path.startsWith('/inspection/')
  }
  if (['/warehouse', '/operations'].includes(path)) {
    const params = new URLSearchParams(queryText)
    const menuTab = params.get('tab') || ''
    const menuSection = params.get('section') || ''
    const currentTab = String(route.query.tab || '')
    const currentSection = String(route.query.section || '')
    return route.path === path && menuTab === currentTab && menuSection === currentSection
  }
  return route.path === path || route.path.startsWith(`${path}/`)
}
</script>

<template>
  <aside class="sidebar" :class="`sidebar-${mode}`">
    <header class="brand sidebar-brand">
      <span class="brand-mark" />
      <span>门店<b>经营</b></span>
    </header>

    <div class="sidebar-scroll nav-list">
      <nav class="primary-navigation" aria-label="主菜单">
        <div v-for="group in menuGroups" :key="group.title || 'default'" class="nav-group">
          <div v-if="group.title" class="nav-group-title">{{ group.title }}</div>
          <RouterLink v-for="item in group.items" :key="item.to" v-slot="{ href, navigate }" custom :to="item.to">
            <a class="nav-link" :class="{ 'router-link-active': isMenuActive(item.to) }" :href="href" @click="emit('navigate'); navigate($event)">
              <component :is="item.icon" :size="18" />
              <span>{{ item.label }}</span>
            </a>
          </RouterLink>
        </div>
      </nav>

      <nav v-if="utilityMenuItems.length" class="utility-navigation" aria-label="辅助工具">
        <div class="nav-group">
          <div class="nav-group-title">辅助工具</div>
          <RouterLink v-for="item in utilityMenuItems" :key="item.to" v-slot="{ href, navigate }" custom :to="item.to">
            <a class="nav-link" :class="{ 'router-link-active': isMenuActive(item.to) }" :href="href" @click="emit('navigate'); navigate($event)">
              <component :is="item.icon" :size="18" />
              <span>{{ item.label }}</span>
            </a>
          </RouterLink>
        </div>
      </nav>
    </div>

    <footer class="sidebar-footer">
      <div class="current-user">
        <span class="current-user-avatar" aria-hidden="true"><UserRound :size="18" /></span>
        <span class="current-user-copy">
          <strong :title="auth.user?.displayName || '当前用户'">{{ auth.user?.displayName || '当前用户' }}</strong>
          <span>{{ auth.roleLabel }}</span>
        </span>
      </div>
      <button class="logout-button" type="button" :disabled="auth.loggingOut" @click="emit('navigate'); emit('logout')">
        <LogOut :size="17" />
        <span>{{ auth.loggingOut ? '正在退出' : '退出登录' }}</span>
      </button>
    </footer>
  </aside>
</template>
