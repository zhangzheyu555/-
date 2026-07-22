import { defineStore } from 'pinia'
import type { SessionUser } from '@/types/auth'
import type { MobileMenuGroup, MobileMenuItem, MobileMenuTone } from '@/types/navigation'
import { canUseMobileCapability, type MobileCapabilityKey } from './capabilities'

interface MenuRule {
  key: MobileCapabilityKey
  group: 'store' | 'finance' | 'warehouse' | 'inspection' | 'learning' | 'operations' | 'summary' | 'system'
  label: string
  description: string
  path: string
  tone: MobileMenuTone
  icon: string
  desktopOnly?: boolean
}

const RULES: MenuRule[] = [
  {
    key: 'inventory', group: 'store', label: '门店库存', description: '查看实时库存与预警', path: '/pkg-store/inventory/index', tone: 'green', icon: '▦',
  },
  {
    key: 'requisition', group: 'store', label: '叫货与收货', description: '修改数量、提交叫货、确认收货', path: '/pkg-store/requisition/index', tone: 'orange', icon: '+',
  },
  {
    key: 'business', group: 'store', label: '本店经营', description: '查看月度经营结果与异常提醒', path: '/pkg-store/business/index', tone: 'green', icon: '▥',
  },
  {
    key: 'warehouse', group: 'warehouse', label: '仓库作业中心', description: '叫货、采购、调拨、库存和单据', path: '/pkg-warehouse/operations/index', tone: 'orange', icon: '↗',
  },
  {
    key: 'inspection', group: 'inspection', label: '移动巡检', description: '执行任务、上传照片、确认建议', path: '/pkg-inspection/inspection/index', tone: 'blue', icon: '✓',
  },
  {
    key: 'rectification', group: 'inspection', label: '整改与复核', description: '提交现场证据或完成运营复核', path: '/pkg-inspection/rectification/index', tone: 'blue', icon: '↻',
  },
  {
    key: 'learning', group: 'learning', label: '培训视频', description: '继续学习与查看进度', path: '/pkg-learning/learning/index', tone: 'blue', icon: '▶',
  },
  {
    key: 'exam', group: 'learning', label: '考试中心', description: '答题、交卷、查看成绩', path: '/pkg-learning/exam/index', tone: 'orange', icon: '?',
  },
  {
    key: 'trainingProgress', group: 'learning', label: '培训进度', description: '查看本店员工学习完成情况', path: '/pkg-learning/progress/index', tone: 'blue', icon: '◷',
  },
  {
    key: 'assistant', group: 'learning', label: '员工服务助手', description: '查询制度、流程和培训问题', path: '/pkg-learning/assistant/index', tone: 'slate', icon: '✦',
  },
  {
    key: 'summary', group: 'summary', label: '经营只读摘要', description: '手机查看关键结果，复杂操作回桌面端', path: '/pkg-summary/index', tone: 'green', icon: '▥',
  },
  { key: 'expenses', group: 'finance', label: '报销', description: '申请、补件与审核报销', path: '/pkg-finance/expenses/index', tone: 'orange', icon: '¥' },
  { key: 'salary', group: 'finance', label: '工资', description: '查看权限范围内的工资记录', path: '/pkg-finance/salary/index', tone: 'green', icon: '￥' },
  { key: 'dailyLoss', group: 'store', label: '每日报损', description: '提交报损并跟踪审核', path: '/pkg-store/daily-loss/index', tone: 'orange', icon: '!' },
  { key: 'operations', group: 'operations', label: '运营与盘存', description: '查看盘存任务和运营事项', path: '/pkg-operations/index', tone: 'blue', icon: '✓' },
  { key: 'operationsMonitor', group: 'operations', label: '培训与平台监测', description: '查看学习进度和平台连接状态', path: '/pkg-operations/monitor/index', tone: 'green', icon: '▦' },
  { key: 'businessAssistant', group: 'operations', label: '经营助手', description: '查询经营问题与建议', path: '/pkg-operations/assistant/index', tone: 'slate', icon: '?' },
  { key: 'audit', group: 'system', label: '操作日志', description: '查看全局关键操作记录', path: '/pkg-boss/audit/index', tone: 'slate', icon: '≡' },
]

const GROUP_LABELS: Record<MenuRule['group'], string> = {
  store: '门店履约',
  finance: '经营财务',
  warehouse: '仓库处理',
  inspection: '巡检闭环',
  learning: '学习与服务',
  operations: '运营工具',
  summary: '经营概览',
  system: '系统审计',
}

export const useMenuStore = defineStore('mobile-menu', {
  state: () => ({ groups: [] as MobileMenuGroup[] }),
  actions: {
    rebuild(user: SessionUser | null): void {
      if (!user) {
        this.groups = []
        return
      }
      const visible = RULES.filter((rule) => canUseMobileCapability(user, rule.key))
      this.groups = (Object.keys(GROUP_LABELS) as MenuRule['group'][])
        .map((groupKey) => ({
          key: groupKey,
          title: GROUP_LABELS[groupKey],
          items: visible
            .filter((rule) => rule.group === groupKey)
            .map((rule): MobileMenuItem => ({
              key: rule.key,
              label: rule.label,
              description: rule.description,
              path: rule.path,
              tone: rule.tone,
              icon: rule.icon,
              desktopOnly: rule.desktopOnly,
            })),
        }))
        .filter((group) => group.items.length > 0)
    },
    clear(): void {
      this.groups = []
    },
  },
})
