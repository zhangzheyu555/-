import type {
  InspectionCategoryCode,
  InspectionRiskLevel,
  InspectionStandardItem,
  InspectionStandardResponse,
} from '../api/inspection'
import { INSPECTION_MAX_SCORE, INSPECTION_PASS_SCORE } from '../utils/inspectionScore'

export const INSPECTION_CATEGORY_RULES: ReadonlyArray<{
  code: InspectionCategoryCode
  name: string
  itemCount: number
  fullScore: number
}> = [
  { code: 'MATERIAL', name: '物料', itemCount: 40, fullScore: 37 },
  { code: 'HYGIENE', name: '卫生', itemCount: 47, fullScore: 63 },
  { code: 'SERVICE', name: '服务', itemCount: 18, fullScore: 100 },
]

export const INSPECTION_STANDARD_EXPECTATIONS = {
  clauseCount: 105,
  fullScore: INSPECTION_MAX_SCORE,
  passScore: INSPECTION_PASS_SCORE,
  redLineCount: 21,
  yellowLineCount: 9,
} as const

export interface InspectionStandardRedline {
  id: number
  code?: string
  item: string
  method?: string
  redLine: true
  riskLevel: 'RED'
}

export interface InspectionStandardClause {
  id: number
  categoryCode: InspectionCategoryCode
  categoryName: string
  code?: string
  item: string
  score: number
  method?: string
  description?: string
  redLine: boolean
  riskLevel: InspectionRiskLevel
  sortOrder: number
}

export interface InspectionStandardGroup {
  categoryCode: InspectionCategoryCode
  dim: string
  expectedItemCount: number
  fullScore: number
  items: InspectionStandardClause[]
}

export interface InspectionStandardDiagnostic {
  categoryCode?: InspectionCategoryCode
  categoryName: string
  expectedCount?: number
  actualCount?: number
  expectedScore?: number
  actualScore?: number
  message: string
  valid: boolean
}

export interface InspectionStandardSet {
  standardVersionId?: number
  title: string
  fullScore: number
  passScore: number
  version: string
  effectiveDate?: string
  redlines: InspectionStandardRedline[]
  groups: InspectionStandardGroup[]
  valid: boolean
  saveAllowed: boolean
  diagnostics: InspectionStandardDiagnostic[]
  validationError?: string
}

export const emptyInspectionStandard: InspectionStandardSet = {
  title: '茹菓门店品质稽核标准',
  fullScore: INSPECTION_MAX_SCORE,
  passScore: INSPECTION_PASS_SCORE,
  version: '',
  redlines: [],
  groups: [],
  valid: false,
  saveAllowed: false,
  diagnostics: [],
}

export function toInspectionStandardSet(response?: InspectionStandardResponse | null): InspectionStandardSet {
  if (!response || !Array.isArray(response.items)) return { ...emptyInspectionStandard }

  const groups = INSPECTION_CATEGORY_RULES.map((category) => ({
    categoryCode: category.code,
    dim: category.name,
    expectedItemCount: category.itemCount,
    fullScore: category.fullScore,
    items: [] as InspectionStandardClause[],
  }))
  const groupByCode = new Map(groups.map((group) => [group.categoryCode, group]))
  const unclassified: string[] = []

  response.items
    .filter((item) => item.enabled !== false)
    .sort((a, b) => Number(a.sortOrder || 0) - Number(b.sortOrder || 0) || Number(a.id) - Number(b.id))
    .forEach((item) => {
      const categoryCode = resolveInspectionCategory(item)
      if (!categoryCode) {
        unclassified.push(item.code || item.title || String(item.id))
        return
      }
      const group = groupByCode.get(categoryCode)
      if (!group) return
      const riskLevel = normalizeRiskLevel(item)
      group.items.push({
        id: Number(item.id),
        categoryCode,
        categoryName: group.dim,
        code: item.code,
        item: item.title,
        score: roundScore(item.suggestedScore),
        method: item.checkMethod || item.description,
        description: item.description,
        redLine: riskLevel === 'RED',
        riskLevel,
        sortOrder: Number(item.sortOrder || 0),
      })
    })

  const diagnostics: InspectionStandardDiagnostic[] = []
  if (unclassified.length) {
    diagnostics.push({
      categoryName: '未分类',
      actualCount: unclassified.length,
      message: `有 ${unclassified.length} 条条款未归入物料、卫生或服务：${unclassified.slice(0, 3).join('、')}`,
      valid: false,
    })
  }
  for (const group of groups) {
    const actualScore = roundScore(group.items.reduce((sum, item) => sum + item.score, 0))
    const valid = group.items.length === group.expectedItemCount && actualScore === group.fullScore
    diagnostics.push({
      categoryCode: group.categoryCode,
      categoryName: group.dim,
      expectedCount: group.expectedItemCount,
      actualCount: group.items.length,
      expectedScore: group.fullScore,
      actualScore,
      message: `${group.dim}应为 ${group.expectedItemCount} 条 / ${group.fullScore} 分，当前 ${group.items.length} 条 / ${actualScore} 分`,
      valid,
    })
  }

  const fullScore = roundScore(response.fullScore)
  const passScore = roundScore(response.passScore ?? INSPECTION_PASS_SCORE)
  const allItems = groups.flatMap((group) => group.items)
  if (allItems.length !== INSPECTION_STANDARD_EXPECTATIONS.clauseCount) {
    diagnostics.push({
      categoryName: '总条款',
      expectedCount: INSPECTION_STANDARD_EXPECTATIONS.clauseCount,
      actualCount: allItems.length,
      message: `总条款应为 ${INSPECTION_STANDARD_EXPECTATIONS.clauseCount} 条，当前 ${allItems.length} 条`,
      valid: false,
    })
  }
  if (fullScore !== INSPECTION_STANDARD_EXPECTATIONS.fullScore) {
    diagnostics.push({ categoryName: '总分', expectedScore: INSPECTION_MAX_SCORE, actualScore: fullScore, message: `总分应为 ${INSPECTION_MAX_SCORE} 分，当前为 ${fullScore} 分`, valid: false })
  }
  if (passScore !== INSPECTION_STANDARD_EXPECTATIONS.passScore) {
    diagnostics.push({ categoryName: '合格线', expectedScore: INSPECTION_PASS_SCORE, actualScore: passScore, message: `合格线应为 ${INSPECTION_PASS_SCORE} 分，当前为 ${passScore} 分`, valid: false })
  }

  const redlines = allItems
    .filter((item) => item.riskLevel === 'RED')
    .map((item) => ({
      id: item.id,
      code: item.code,
      item: item.item,
      method: item.method,
      redLine: true as const,
      riskLevel: 'RED' as const,
    }))
  const yellowLineCount = allItems.filter((item) => item.riskLevel === 'YELLOW').length
  if (redlines.length !== INSPECTION_STANDARD_EXPECTATIONS.redLineCount) {
    diagnostics.push({ categoryName: '红线', expectedCount: 21, actualCount: redlines.length, message: `红线应为 21 条，当前 ${redlines.length} 条`, valid: false })
  }
  if (yellowLineCount !== INSPECTION_STANDARD_EXPECTATIONS.yellowLineCount) {
    diagnostics.push({ categoryName: '黄线', expectedCount: 9, actualCount: yellowLineCount, message: `黄线应为 9 条，当前 ${yellowLineCount} 条`, valid: false })
  }

  const backendDiagnostics = (response.diagnostics || []).map((item) => ({
    categoryCode: item.categoryCode,
    categoryName: item.categoryName || categoryNameForCode(item.categoryCode) || '标准校验',
    expectedCount: item.expectedCount,
    actualCount: item.actualCount,
    expectedScore: item.expectedScore,
    actualScore: item.actualScore,
    message: item.message || diagnosticMessage(item),
    valid: isDiagnosticValid(item),
  }))
  const mergedDiagnostics = uniqueDiagnostics([...diagnostics, ...backendDiagnostics])
  const backendValid = response.valid ?? response.validFlag
  const locallyValid = mergedDiagnostics.every((item) => item.valid)
  const valid = backendValid !== false && locallyValid
  const saveAllowed = response.saveAllowed !== false && valid
  const validationMessages = [response.validationError, ...mergedDiagnostics.filter((item) => !item.valid).map((item) => item.message)].filter(Boolean)

  return {
    standardVersionId: response.id,
    title: response.title || emptyInspectionStandard.title,
    fullScore,
    passScore,
    version: response.version || '',
    effectiveDate: response.effectiveDate,
    redlines,
    groups,
    valid,
    saveAllowed,
    diagnostics: mergedDiagnostics,
    validationError: validationMessages.length ? Array.from(new Set(validationMessages)).join('；') : undefined,
  }
}

export function getInspectionDimensions(standard: InspectionStandardSet) {
  return standard.groups.map((group) => group.dim)
}

export function inspectionStandardStats(standard: InspectionStandardSet) {
  const allItems = standard.groups.flatMap((group) => group.items)
  return {
    redlineCount: allItems.filter((item) => item.riskLevel === 'RED').length,
    yellowLineCount: allItems.filter((item) => item.riskLevel === 'YELLOW').length,
    clauseCount: allItems.length,
    fullScore: standard.fullScore,
    passScore: standard.passScore,
    categoryStats: standard.groups.map((group) => ({
      categoryCode: group.categoryCode,
      categoryName: group.dim,
      expectedCount: group.expectedItemCount,
      actualCount: group.items.length,
      expectedScore: group.fullScore,
      actualScore: roundScore(group.items.reduce((sum, item) => sum + item.score, 0)),
    })),
  }
}

function categoryNameForCode(code?: InspectionCategoryCode) {
  return INSPECTION_CATEGORY_RULES.find((item) => item.code === code)?.name
}

function diagnosticMessage(item: {
  categoryName?: string
  expectedCount?: number
  actualCount?: number
  expectedScore?: number
  actualScore?: number
}) {
  const name = item.categoryName || '标准'
  const count = item.expectedCount !== undefined || item.actualCount !== undefined
    ? `${item.expectedCount ?? '—'} 条 / 当前 ${item.actualCount ?? '—'} 条`
    : ''
  const score = item.expectedScore !== undefined || item.actualScore !== undefined
    ? `${item.expectedScore ?? '—'} 分 / 当前 ${item.actualScore ?? '—'} 分`
    : ''
  return `${name}校验：${[count, score].filter(Boolean).join('，')}`
}

function isDiagnosticValid(item: {
  expectedCount?: number
  actualCount?: number
  expectedScore?: number
  actualScore?: number
}) {
  const countValid = item.expectedCount === undefined || item.actualCount === undefined || item.expectedCount === item.actualCount
  const scoreValid = item.expectedScore === undefined || item.actualScore === undefined || roundScore(item.expectedScore) === roundScore(item.actualScore)
  return countValid && scoreValid
}

function uniqueDiagnostics(items: InspectionStandardDiagnostic[]) {
  const seen = new Set<string>()
  return items.filter((item) => {
    const key = `${item.categoryCode || item.categoryName}:${item.expectedCount ?? ''}:${item.actualCount ?? ''}:${item.expectedScore ?? ''}:${item.actualScore ?? ''}:${item.message}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}

export function resolveInspectionCategory(item: Pick<InspectionStandardItem, 'dimension' | 'code' | 'title'>): InspectionCategoryCode | undefined {
  // The database dimension is authoritative.  Mixing it with the clause title
  // made service clauses containing words such as “产品” look like material
  // clauses (40/47/18 was rendered as 43/47/15).
  const dimension = String(item.dimension || '').trim().toUpperCase()
  if (/MATERIAL|物料/.test(dimension)) return 'MATERIAL'
  if (/HYGIENE|卫生/.test(dimension)) return 'HYGIENE'
  if (/SERVICE|服务/.test(dimension)) return 'SERVICE'

  const code = String(item.code || '').trim().toUpperCase()
  if (/^M(?:-|$)/.test(code)) return 'MATERIAL'
  if (/^H(?:-|$)/.test(code)) return 'HYGIENE'
  if (/^S(?:-|$)/.test(code)) return 'SERVICE'

  const title = String(item.title || '').trim().toUpperCase()
  if (/物料|产品|出品|食品|原料|效期|陈列/.test(title)) return 'MATERIAL'
  if (/卫生|清洁|消毒|环境|设备/.test(title)) return 'HYGIENE'
  if (/服务|顾客|礼仪|运营|证件/.test(title)) return 'SERVICE'
  return undefined
}

function normalizeRiskLevel(item: InspectionStandardItem): InspectionRiskLevel {
  const value = String(item.riskLevel || '').trim().toUpperCase()
  if (value === 'RED' || item.redLine) return 'RED'
  if (value === 'YELLOW') return 'YELLOW'
  return 'NORMAL'
}

function roundScore(value: unknown) {
  const numberValue = Number(value)
  if (!Number.isFinite(numberValue)) return 0
  return Math.round(numberValue * 100) / 100
}
