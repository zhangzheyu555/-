/**
 * Presentation-only decimal formatting. The operating snapshot arrives as BigDecimal strings;
 * this module deliberately does no floating-point conversion or business calculation.
 */
export type DecimalValue = string | number | null | undefined

interface Parts {
  negative: boolean
  integer: string
  fraction: string
}

function toParts(value: DecimalValue): Parts {
  const raw = String(value ?? '0').trim().replace(/,/g, '')
  const matched = /^(-?)(\d+)(?:\.(\d+))?$/.exec(raw)
  if (!matched) return { negative: false, integer: '0', fraction: '' }
  const integer = matched[2].replace(/^0+(?=\d)/, '') || '0'
  const hasValue = integer !== '0' || /[1-9]/.test(matched[3] || '')
  return { negative: matched[1] === '-' && hasValue, integer, fraction: matched[3] || '' }
}

function addOne(integer: string) {
  const chars = integer.split('')
  let carry = 1
  for (let index = chars.length - 1; index >= 0 && carry; index -= 1) {
    const next = chars[index].charCodeAt(0) - 48 + carry
    chars[index] = String(next % 10)
    carry = next >= 10 ? 1 : 0
  }
  return `${carry ? '1' : ''}${chars.join('')}`
}

function rounded(parts: Parts, fractionDigits: number): Parts {
  if (fractionDigits < 0) return parts
  const kept = parts.fraction.slice(0, fractionDigits).padEnd(fractionDigits, '0')
  const next = parts.fraction.charCodeAt(fractionDigits) - 48
  if (!(next >= 5)) return { ...parts, fraction: kept }
  if (fractionDigits === 0) return { ...parts, integer: addOne(parts.integer), fraction: '' }

  const digits = kept.split('')
  let carry = 1
  for (let index = digits.length - 1; index >= 0 && carry; index -= 1) {
    const value = digits[index].charCodeAt(0) - 48 + carry
    digits[index] = String(value % 10)
    carry = value >= 10 ? 1 : 0
  }
  return { ...parts, integer: carry ? addOne(parts.integer) : parts.integer, fraction: digits.join('') }
}

function grouped(integer: string) {
  return integer.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
}

export function formatDecimal(value: DecimalValue, fractionDigits = 2) {
  const parts = rounded(toParts(value), fractionDigits)
  const sign = parts.negative ? '-' : ''
  return `${sign}${grouped(parts.integer)}${fractionDigits ? `.${parts.fraction}` : ''}`
}

export function formatCny(value: DecimalValue, fractionDigits = 2) {
  return `¥${formatDecimal(value, fractionDigits)}`
}

function shiftRight(value: DecimalValue, places: number) {
  const parts = toParts(value)
  // Keep the leading integer zero while moving the decimal point. Removing it first would turn
  // 0.2800 into 280.0% instead of 28.0%.
  const digits = `${parts.integer}${parts.fraction}` || '0'
  const decimalAt = parts.integer.length + places
  const padded = decimalAt > digits.length ? digits.padEnd(decimalAt, '0') : digits
  const integer = padded.slice(0, decimalAt) || '0'
  const fraction = padded.slice(decimalAt)
  return `${parts.negative ? '-' : ''}${integer}${fraction ? `.${fraction}` : ''}`
}

export function formatPercent(value: DecimalValue, fractionDigits = 1) {
  return `${formatDecimal(shiftRight(value, 2), fractionDigits)}%`
}
