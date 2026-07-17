import { ApiError, apiGet, apiPost } from './http'

// Backend analysis budget is 30s. Keep a small transport safety margin without leaving the UI hanging.
export const ASSISTANT_CHAT_TIMEOUT_MS = 40_000

/** Decimal values are serialized by the backend as strings; the browser formats but never calculates them. */
export type DecimalString = string

export interface AssistantChatRequest {
  message: string
  history?: Array<{
    role: 'user' | 'assistant'
    content: string
  }>
  dataContext?: string
  mode?: 'AUTO' | 'LOCAL' | 'AI'
  storeId?: string
  month?: string
  /** The page must pass the currently displayed read-only snapshot for a coherent answer. */
  snapshotId?: string
}

export interface AssistantChatResponse {
  question: string
  selectedMode: 'LOCAL' | 'AI'
  selectionReason: string
  localData: AssistantLocalData
  aiAnalysis: AssistantAiAnalysis
  fallbackUsed: boolean
  error: AssistantError | null
}

export interface AssistantLocalMetric {
  key: string
  label: string
  value: DecimalString | number
  unit: 'CNY' | 'PERCENT' | string
  displayValue: string
  changeRate: DecimalString | number | null
  comparison: string
}

export interface AssistantLocalData {
  summary: string
  metrics: AssistantLocalMetric[]
  dataPeriod: string
  dataScope: string
  source: string
  dataVersion: string
  calculationVersion: string
  updatedAt: string
  snapshotId?: string
  operatingSnapshot?: OperatingSnapshot | null
  insufficientData?: AssistantInsufficientData | null
  /** Whether this response actually invoked a model, used cache, or stayed local. */
  aiInvocation?: 'LIVE' | 'CACHE_HIT' | 'NOT_REQUESTED' | 'NOT_CALLED_INSUFFICIENT' | 'NOT_CALLED_UNAVAILABLE' | 'NOT_CALLED_SNAPSHOT_EXPIRED' | 'FAILED' | string
}

export interface OperatingSnapshot {
  snapshotId: string
  generatedAt: string
  /** Null means the current monthly source does not contain a verifiable business cutoff. */
  asOf: string | null
  periodStart: string
  periodEnd: string
  isMTD: boolean
  storeScope: OperatingStoreScope
  storeCoverage: OperatingStoreCoverage
  revenue: DecimalString
  costOfSales: DecimalString
  operatingExpense: DecimalString
  otherIncomeExpense: DecimalString | null
  tax: DecimalString | null
  netProfit: DecimalString
  netMargin: DecimalString
  previousComparablePeriod: PreviousComparablePeriod
  comparisonBasis: ComparisonBasis
  profitBridge: ProfitBridge
  capabilities: OperatingCapabilities
  dataQuality: OperatingDataQuality
  missingFields: string[]
  dataSourceVersion: string
}

export interface OperatingStoreScope {
  label: string
  storeIds: string[]
  storeNames: string[]
}

export interface OperatingStoreCoverage {
  expectedStoreCount: number
  reportedStoreCount: number
  missingStoreIds: string[]
  missingStoreNames: string[]
  missingDates: string[]
  missingDatesKnown: boolean
  coverageRate: DecimalString
}

export interface PreviousComparablePeriod {
  available: boolean
  periodStart: string | null
  periodEnd: string | null
  storeIds: string[]
  revenue: DecimalString | null
  netProfit: DecimalString | null
  netMargin: DecimalString | null
  unavailableReason: string
}

export interface ComparisonBasis {
  available: boolean
  sameStoreScope: boolean
  sameAccountingBasis: boolean
  sameOperatingDays: boolean
  sameDayCount: boolean
  explanation: string
}

export interface ProfitBridge {
  accountingScope: string
  grossSales: DecimalString
  refunds: DecimalString
  discounts: DecimalString
  revenue: DecimalString
  costOfSales: DecimalString
  operatingExpense: DecimalString
  otherIncomeExpense: DecimalString | null
  tax: DecimalString | null
  unclassifiedDifference: DecimalString
  netProfit: DecimalString
}

export interface OperatingCapabilities {
  canComputeKPI: boolean
  canCompare: boolean
  canAttributeCause: boolean
  canUseAI: boolean
}

export interface OperatingDataQuality {
  level: 'COMPLETE' | 'PARTIAL' | 'INSUFFICIENT' | string
  notices: string[]
  dailyCoverageKnown: boolean
  asOfKnown: boolean
}

export interface AssistantInsufficientData {
  kind: 'INSUFFICIENT_DATA' | string
  verifiedFacts: string[]
  cannotDetermine: string[]
  missingItems: string[]
  nextSteps: string[]
  modelInvoked: boolean
}

export interface AssistantRisk {
  title: string
  evidence: string
  severity: 'HIGH' | 'MEDIUM' | 'LOW'
}

export interface AssistantPossibleCause {
  cause: string
  confidence: 'HIGH' | 'MEDIUM' | 'LOW'
  basis: string
}

export interface AssistantAction {
  action: string
  ownerRole: string
  deadline: string
  expectedImpact: string
  verificationMetric: string
}

export interface AssistantAiAnalysis {
  available: boolean
  /**
   * FULL means the model could make a complete, evidence-checked operating analysis.
   * DATA_LIMITED is still a valid response, but only contains conclusions that the
   * available operating data can support plus concrete data-completion actions.
   */
  analysisType?: 'FULL' | 'DATA_LIMITED'
  provider: string
  model: string
  requestId: string
  latencyMs: number
  summary: string
  findings: string[]
  risks: AssistantRisk[]
  possibleCauses: AssistantPossibleCause[]
  actions: AssistantAction[]
  confidence: 'HIGH' | 'MEDIUM' | 'LOW' | ''
  limitations: string[]
}

export interface AssistantError {
  code: string
  message: string
}

export interface AssistantStatus {
  enabled: boolean
  configured: boolean
  /**
   * Configuration and proven analysis health are deliberately different.  Older
   * backend packages do not include this field; callers must treat a missing value
   * as configured-only rather than claiming the analysis service is healthy.
   */
  state?: 'NOT_CONFIGURED' | 'CONFIGURED' | 'READY' | 'RESPONSE_REJECTED' | 'UPSTREAM_ERROR'
  provider: string
  model: string
  baseUrlHost: string
  timeout: number
  lastSuccessAt: string | null
  lastErrorCode: string | null
}

export function askAssistant(payload: AssistantChatRequest) {
  return apiPost<AssistantChatResponse, AssistantChatRequest>('/api/assistant/chat', payload, {
    timeout: ASSISTANT_CHAT_TIMEOUT_MS,
  })
}

export function getAssistantStatus() {
  return apiGet<AssistantStatus>('/api/assistant/status')
}

export async function getOperatingSnapshot(
  params: { storeId?: string; month?: string } = {},
  options: { signal?: AbortSignal } = {},
) {
  const query = new URLSearchParams()
  if (params.storeId?.trim()) query.set('storeId', params.storeId.trim())
  if (params.month?.trim()) query.set('month', params.month.trim())
  const suffix = query.toString()
  const response = await apiGet<unknown>(
    `/api/assistant/operating-snapshot${suffix ? `?${suffix}` : ''}`,
    { signal: options.signal },
  )
  if (!isOperatingSnapshot(response)) {
    throw new ApiError('经营快照服务返回的数据版本不兼容，请更新并重启后端后再试。', 409, 'OPERATING_SNAPSHOT_UNSUPPORTED')
  }
  return response
}

function isOperatingSnapshot(value: unknown): value is OperatingSnapshot {
  if (!isRecord(value)) return false
  return isNonBlankString(value.snapshotId)
    && isNonBlankString(value.generatedAt)
    && isNullableString(value.asOf)
    && isNonBlankString(value.periodStart)
    && isNonBlankString(value.periodEnd)
    && typeof value.isMTD === 'boolean'
    && isStoreScope(value.storeScope)
    && isStoreCoverage(value.storeCoverage)
    && isDecimal(value.revenue)
    && isDecimal(value.costOfSales)
    && isDecimal(value.operatingExpense)
    && isNullableDecimal(value.otherIncomeExpense)
    && isNullableDecimal(value.tax)
    && isDecimal(value.netProfit)
    && isDecimal(value.netMargin)
    && isComparison(value.previousComparablePeriod)
    && isComparisonBasis(value.comparisonBasis)
    && isProfitBridge(value.profitBridge)
    && isCapabilities(value.capabilities)
    && isDataQuality(value.dataQuality)
    && isStringArray(value.missingFields)
    && isNonBlankString(value.dataSourceVersion)
}

function isStoreScope(value: unknown) {
  return isRecord(value) && isNonBlankString(value.label) && isStringArray(value.storeIds) && isStringArray(value.storeNames)
}

function isStoreCoverage(value: unknown) {
  return isRecord(value)
    && isNonNegativeInteger(value.expectedStoreCount)
    && isNonNegativeInteger(value.reportedStoreCount)
    && isStringArray(value.missingStoreIds)
    && isStringArray(value.missingStoreNames)
    && isStringArray(value.missingDates)
    && typeof value.missingDatesKnown === 'boolean'
    && isDecimal(value.coverageRate)
}

function isComparison(value: unknown) {
  return isRecord(value)
    && typeof value.available === 'boolean'
    && isNullableString(value.periodStart)
    && isNullableString(value.periodEnd)
    && isStringArray(value.storeIds)
    && isNullableDecimal(value.revenue)
    && isNullableDecimal(value.netProfit)
    && isNullableDecimal(value.netMargin)
    && typeof value.unavailableReason === 'string'
}

function isComparisonBasis(value: unknown) {
  return isRecord(value)
    && typeof value.available === 'boolean'
    && typeof value.sameStoreScope === 'boolean'
    && typeof value.sameAccountingBasis === 'boolean'
    && typeof value.sameOperatingDays === 'boolean'
    && typeof value.sameDayCount === 'boolean'
    && typeof value.explanation === 'string'
}

function isProfitBridge(value: unknown) {
  return isRecord(value)
    && typeof value.accountingScope === 'string'
    && isDecimal(value.grossSales)
    && isDecimal(value.refunds)
    && isDecimal(value.discounts)
    && isDecimal(value.revenue)
    && isDecimal(value.costOfSales)
    && isDecimal(value.operatingExpense)
    && isNullableDecimal(value.otherIncomeExpense)
    && isNullableDecimal(value.tax)
    && isDecimal(value.unclassifiedDifference)
    && isDecimal(value.netProfit)
}

function isCapabilities(value: unknown) {
  return isRecord(value)
    && typeof value.canComputeKPI === 'boolean'
    && typeof value.canCompare === 'boolean'
    && typeof value.canAttributeCause === 'boolean'
    && typeof value.canUseAI === 'boolean'
}

function isDataQuality(value: unknown) {
  return isRecord(value)
    && typeof value.level === 'string'
    && isStringArray(value.notices)
    && typeof value.dailyCoverageKnown === 'boolean'
    && typeof value.asOfKnown === 'boolean'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function isNonBlankString(value: unknown): value is string {
  return typeof value === 'string' && value.trim().length > 0
}

function isNullableString(value: unknown): value is string | null {
  return value === null || typeof value === 'string'
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function isNonNegativeInteger(value: unknown) {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0
}

function isDecimal(value: unknown): value is DecimalString {
  return typeof value === 'string' && /^-?\d+(?:\.\d+)?$/.test(value)
}

function isNullableDecimal(value: unknown): value is DecimalString | null {
  return value === null || isDecimal(value)
}
