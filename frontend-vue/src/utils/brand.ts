export interface BrandLike {
  value?: string | number
  id?: string | number
  brandId?: string | number
  code?: string
  brandCode?: string
  name?: string
  brandName?: string
  color?: string
  sortOrder?: number
}

export interface BrandTheme {
  key: 'ruguo' | 'bawang' | 'luckin' | 'unknown'
  name: string
  main: string
  dark: string
  soft: string
}

export const STANDARD_BRANDS: BrandTheme[] = [
  {
    key: 'ruguo',
    name: '茹菓',
    main: '#ef7d3c',
    dark: '#6b3b27',
    soft: '#fff2ea',
  },
  {
    key: 'bawang',
    name: '霸王茶姬',
    main: '#9c2f3e',
    dark: '#5a1c27',
    soft: '#fff0f2',
  },
  {
    key: 'luckin',
    name: '瑞幸咖啡',
    main: '#2458c7',
    dark: '#163b88',
    soft: '#eef4ff',
  },
]

const DEFAULT_THEME: BrandTheme = {
  key: 'unknown',
  name: '未分品牌',
  main: '#6b7280',
  dark: '#374151',
  soft: '#f3f4f6',
}

export function normalizeBrandName(name?: string | null) {
  const text = String(name || '').trim()
  if (!text) return ''
  if (/茹菓|茹果|苹果|ruguo|ru guo|rg/i.test(text)) return '茹菓'
  if (/霸王|茶姬|bawang/i.test(text)) return '霸王茶姬'
  if (/瑞幸|luckin/i.test(text)) return '瑞幸咖啡'
  return text
}

export function getBrandTheme(name?: string | null, fallbackColor?: string): BrandTheme {
  const normalized = normalizeBrandName(name)
  const theme = STANDARD_BRANDS.find((brand) => brand.name === normalized)
  if (theme) return theme
  return fallbackColor
    ? {
        ...DEFAULT_THEME,
        name: normalized || DEFAULT_THEME.name,
        main: fallbackColor,
      }
    : { ...DEFAULT_THEME, name: normalized || DEFAULT_THEME.name }
}

export function isSameBrand(a?: string | number | null, b?: string | number | null) {
  const left = String(a || '').trim()
  const right = String(b || '').trim()
  if (!left || !right) return false
  if (left === right) return true
  if (left.startsWith('name:') || right.startsWith('name:')) {
    return normalizeBrandName(left.replace(/^name:/, '')) === normalizeBrandName(right.replace(/^name:/, ''))
  }
  return normalizeBrandName(left) === normalizeBrandName(right)
}

export function getBrandIdLike(brand?: BrandLike | string | number | null) {
  if (brand === null || brand === undefined) return ''
  if (typeof brand === 'string' || typeof brand === 'number') {
    const raw = String(brand).trim()
    if (!raw) return ''
    if (/^\d+$/.test(raw) || raw.startsWith('name:')) return raw
    return `name:${normalizeBrandName(raw)}`
  }
  const id = brand.value ?? brand.brandId ?? brand.id
  if (id !== undefined && id !== null && String(id).trim()) return String(id)
  const name = normalizeBrandName(brand.name || brand.brandName || '')
  if (name) return `name:${name}`
  const code = brand.code || brand.brandCode || ''
  return code ? String(code) : ''
}

export function displayBrandName(brand?: BrandLike | string | number | null) {
  if (brand === null || brand === undefined) return ''
  if (typeof brand === 'string' || typeof brand === 'number') {
    return normalizeBrandName(String(brand).replace(/^name:/, ''))
  }
  return normalizeBrandName(brand.name || brand.brandName || '')
}
