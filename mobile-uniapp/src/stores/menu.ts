import { defineStore } from 'pinia'
import type { SessionUser } from '@/types/auth'
import type { MobileMenuGroup, MobileMenuItem, MobileMenuTone } from '@/types/navigation'
import { canUseMobileCapability, type MobileCapabilityKey } from './capabilities'

interface MenuRule {
  key: MobileCapabilityKey
  group: 'store' | 'warehouse' | 'inspection' | 'learning' | 'summary'
  label: string
  description: string
  path: string
  tone: MobileMenuTone
  desktopOnly?: boolean
}

const RULES: MenuRule[] = [
  {
    key: 'inventory', group: 'store', label: '门店库存', description: '查看实时库存与预警', path: '/pkg-store/inventory/index', tone: 'green',
  },
  {
    key: 'requisition', group: 'store', label: '叫货与收货', description: '修改数量、提交叫货、确认收货', path: '/pkg-store/requisition/index', tone: 'orange',
  },
  {
    key: 'warehouse', group: 'warehouse', label: '仓库待办', description: '处理叫货发货、预警和退货收货', path: '/pkg-warehouse/index', tone: 'orange',
  },
  {
    key: 'inspection', group: 'inspection', label: '移动巡检', description: '执行任务、上传照片、确认建议', path: '/pkg-inspection/inspection/index', tone: 'blue',
  },
  {
    key: 'rectification', group: 'inspection', label: '整改与复核', description: '提交现场证据或完成运营复核', path: '/pkg-inspection/rectification/index', tone: 'blue',
  },
  {
    key: 'learning', group: 'learning', label: '培训视频', description: '继续学习与查看进度', path: '/pkg-learning/learning/index', tone: 'blue',
  },
  {
    key: 'exam', group: 'learning', label: '考试中心', description: '答题、交卷、查看成绩', path: '/pkg-learning/exam/index', tone: 'orange',
  },
  {
    key: 'assistant', group: 'learning', label: '员工服务助手', description: '查询制度、流程和培训问题', path: '/pkg-learning/assistant/index', tone: 'slate',
  },
  {
    key: 'summary', group: 'summary', label: '经营只读摘要', description: '手机查看关键结果，复杂操作回桌面端', path: '/pkg-summary/index', tone: 'green',
  },
]

const GROUP_LABELS: Record<MenuRule['group'], string> = {
  store: '门店履约',
  warehouse: '仓库处理',
  inspection: '巡检闭环',
  learning: '学习与服务',
  summary: '经营概览',
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
