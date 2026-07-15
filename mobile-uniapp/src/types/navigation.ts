export type MobileMenuTone = 'green' | 'blue' | 'orange' | 'slate'

export interface MobileMenuItem {
  key: string
  label: string
  description: string
  path: string
  tone: MobileMenuTone
  badge?: string
  desktopOnly?: boolean
}

export interface MobileMenuGroup {
  key: string
  title: string
  items: MobileMenuItem[]
}

