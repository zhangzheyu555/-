import type { InspectionStandardResponse } from '../api/inspection'

export interface InspectionStandardRedline {
  id: string
  code?: string
  item: string
  method?: string
  redLine: true
}

export interface InspectionStandardClause {
  id: string
  code?: string
  item: string
  score: number
  method?: string
  redLine: false
}

export interface InspectionStandardGroup {
  dim: string
  items: InspectionStandardClause[]
}

export interface InspectionStandardSet {
  title: string
  fullScore: number
  version: string
  redlines: InspectionStandardRedline[]
  groups: InspectionStandardGroup[]
}

export const emptyInspectionStandard: InspectionStandardSet = {
  title: '全门店通用标准',
  fullScore: 100,
  version: '',
  redlines: [],
  groups: [],
}

export const genericInspectionDimensions = ['食品安全', '门店卫生', '服务规范', '出品标准', '设备使用', '平台运营', '证件合规', '其他问题']

export function toInspectionStandardSet(response?: InspectionStandardResponse | null): InspectionStandardSet {
  if (!response) return emptyInspectionStandard
  const redlines = response.items
    .filter((item) => item.redLine)
    .map((item) => ({
      id: String(item.id),
      code: item.code,
      item: item.title,
      method: item.description,
      redLine: true as const,
    }))
  const grouped = new Map<string, InspectionStandardClause[]>()
  for (const item of response.items) {
    if (item.redLine) continue
    const dimension = item.dimension || '其他问题'
    const rows = grouped.get(dimension) || []
    rows.push({
      id: String(item.id),
      code: item.code,
      item: item.title,
      score: Number(item.suggestedScore || 0),
      method: item.description,
      redLine: false,
    })
    grouped.set(dimension, rows)
  }
  return {
    title: response.title || '全门店通用标准',
    fullScore: Number(response.fullScore || 100),
    version: response.version || '',
    redlines,
    groups: Array.from(grouped.entries()).map(([dim, items]) => ({ dim, items })),
  }
}

export function getInspectionDimensions(standard: InspectionStandardSet) {
  if (standard.groups.length) return standard.groups.map((group) => group.dim)
  return genericInspectionDimensions
}

export function inspectionStandardStats(standard: InspectionStandardSet) {
  const redlineCount = standard.redlines.length
  const clauseCount = standard.groups.reduce((sum, group) => sum + group.items.length, 0)
  return {
    redlineCount,
    clauseCount,
    fullScore: standard.fullScore,
  }
}
