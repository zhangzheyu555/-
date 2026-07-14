import type { InspectionRecord, InspectionResultCode } from '../api/inspection'

export const INSPECTION_MAX_SCORE = 200
export const INSPECTION_PASS_SCORE = 180

export interface InspectionScoreView {
  valid: boolean
  score: number | null
  maxScore: number | null
  passScore: number | null
  passed: boolean | null
  resultCode?: InspectionResultCode
  scoreText: string
  resultText: string
  tone: 'ok' | 'bad' | 'review'
  error?: string
}

/**
 * Normalizes the API scoring contract for presentation only.
 *
 * Historical conversion and pass/fail calculation belong to the backend. If
 * any authoritative field is absent or uses the wrong scale, the UI exposes a
 * repair state instead of guessing, multiplying, or silently falling back to
 * legacy fields.
 */
export function inspectionScoreView(record?: Pick<
  InspectionRecord,
  'score' | 'maxScore' | 'passScore' | 'passed' | 'resultCode'
> | null): InspectionScoreView {
  const score = finiteNumber(record?.score)
  const maxScore = finiteNumber(record?.maxScore)
  const passScore = finiteNumber(record?.passScore)
  const passed = typeof record?.passed === 'boolean' ? record.passed : null
  const resultCode = record?.resultCode

  const missing: string[] = []
  if (score === null) missing.push('得分')
  if (maxScore === null) missing.push('满分')
  if (passScore === null) missing.push('合格线')
  if (passed === null) missing.push('合格结果')

  let error = missing.length ? `缺少${missing.join('、')}` : ''
  if (!error && maxScore !== INSPECTION_MAX_SCORE) error = `满分应为 ${INSPECTION_MAX_SCORE} 分`
  if (!error && passScore !== INSPECTION_PASS_SCORE) error = `合格线应为 ${INSPECTION_PASS_SCORE} 分`
  if (!error && score !== null && maxScore !== null && (score < 0 || score > maxScore)) error = '得分超出有效范围'

  if (error) {
    return {
      valid: false,
      score,
      maxScore,
      passScore,
      passed,
      resultCode,
      scoreText: '评分数据待修复',
      resultText: '评分数据待修复',
      tone: 'review',
      error,
    }
  }

  const resultText = resultCode === 'RED_LINE_FAILED'
    ? '红线不合格'
    : resultCode === 'MANUAL_REVIEW'
      ? '待人工复核'
      : passed
        ? '合格'
        : '不合格'

  return {
    valid: true,
    score,
    maxScore,
    passScore,
    passed,
    resultCode,
    scoreText: `${formatScore(score)} / ${formatScore(maxScore)}`,
    resultText,
    tone: resultText === '合格' ? 'ok' : resultText === '待人工复核' ? 'review' : 'bad',
  }
}

export function formatScore(value: number | null | undefined) {
  if (value === null || value === undefined || !Number.isFinite(value)) return '—'
  return Number.isInteger(value) ? String(value) : String(Math.round(value * 100) / 100)
}

function finiteNumber(value: unknown) {
  const numberValue = Number(value)
  return value !== null && value !== undefined && value !== '' && Number.isFinite(numberValue) ? numberValue : null
}
