export interface SnapshotFixtureOptions {
  snapshotId?: string
  month?: string
  isMTD?: boolean
  canCompare?: boolean
  canAttributeCause?: boolean
  canUseAI?: boolean
  expectedStoreCount?: number
  reportedStoreCount?: number
  missingStoreNames?: string[]
}

export function operatingSnapshot(options: SnapshotFixtureOptions = {}) {
  const month = options.month || '2026-07'
  const isMTD = options.isMTD ?? true
  const canCompare = options.canCompare ?? false
  const canAttributeCause = options.canAttributeCause ?? false
  const canUseAI = options.canUseAI ?? false
  const expectedStoreCount = options.expectedStoreCount ?? 1
  const reportedStoreCount = options.reportedStoreCount ?? expectedStoreCount
  const missingStoreNames = options.missingStoreNames || []
  const coverageRate = expectedStoreCount ? String(reportedStoreCount / expectedStoreCount) : '0'
  const periodEnd = isMTD ? `${month}-16` : `${month}-31`
  return {
    snapshotId: options.snapshotId || `${month}-snapshot-28pct`,
    generatedAt: '2026-07-16T08:00:00Z',
    asOf: isMTD ? null : periodEnd,
    periodStart: `${month}-01`,
    periodEnd,
    isMTD,
    storeScope: { label: '测试门店', storeIds: ['store-1'], storeNames: ['测试门店'] },
    storeCoverage: {
      expectedStoreCount,
      reportedStoreCount,
      missingStoreIds: missingStoreNames.map((_, index) => `missing-${index + 1}`),
      missingStoreNames,
      missingDates: [],
      missingDatesKnown: false,
      coverageRate,
    },
    revenue: '100.00',
    costOfSales: '30.00',
    operatingExpense: '42.00',
    otherIncomeExpense: null,
    tax: null,
    netProfit: '28.00',
    netMargin: '0.2800',
    previousComparablePeriod: {
      available: canCompare,
      periodStart: canCompare ? '2025-12-01' : '2026-06-01',
      periodEnd: canCompare ? '2025-12-31' : '2026-06-30',
      storeIds: ['store-1'],
      revenue: canCompare ? '95.00' : null,
      netProfit: canCompare ? '20.00' : null,
      netMargin: canCompare ? '0.2105' : null,
      unavailableReason: canCompare ? '' : '当前月仅有月度汇总，未记录同营业日和业务截至日，不能与完整上月直接环比。',
    },
    comparisonBasis: {
      available: canCompare,
      sameStoreScope: canCompare,
      sameAccountingBasis: true,
      sameOperatingDays: canCompare,
      sameDayCount: canCompare,
      explanation: canCompare ? '同门店、同天数、同口径。' : '当前月仅有月度汇总，未记录同营业日和业务截至日，不能与完整上月直接环比。',
    },
    profitBridge: {
      accountingScope: 'MONTHLY_OPERATING_PROFIT_PRE_TAX',
      grossSales: '100.00', refunds: '0.00', discounts: '0.00', revenue: '100.00',
      costOfSales: '30.00', operatingExpense: '42.00', otherIncomeExpense: null, tax: null,
      unclassifiedDifference: '0.00', netProfit: '28.00',
    },
    capabilities: { canComputeKPI: true, canCompare, canAttributeCause, canUseAI },
    dataQuality: {
      level: canAttributeCause ? 'COMPLETE' : 'PARTIAL',
      notices: ['其他损益和税费尚未建模，不能当作零。'],
      dailyCoverageKnown: false,
      asOfKnown: !isMTD,
    },
    missingFields: isMTD ? ['businessAsOf', 'dailyOperatingCoverage', 'otherIncomeExpense', 'tax'] : ['otherIncomeExpense', 'tax'],
    dataSourceVersion: 'e2e-source-v1',
  }
}

export function withSnapshotLocalData(snapshot: ReturnType<typeof operatingSnapshot>, overrides: Record<string, unknown> = {}) {
  return {
    summary: '本月真实经营数据已查询完成。',
    metrics: [],
    dataPeriod: snapshot.periodStart.slice(0, 7),
    dataScope: snapshot.storeScope.label,
    source: '经营数据库',
    dataVersion: snapshot.dataSourceVersion,
    calculationVersion: 'operating-snapshot-v4',
    updatedAt: snapshot.generatedAt,
    snapshotId: snapshot.snapshotId,
    operatingSnapshot: snapshot,
    aiInvocation: 'NOT_REQUESTED',
    ...overrides,
  }
}
