/// <reference types="vite/client" />

import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
    permission?: string
    alternativePermissions?: string[]
    allowedRoles?: string[]
    bossOnly?: boolean
    menuKey?: string
    moduleKey?: string
    roles?: string[]
    requiresAuth?: boolean
    title?: string
    warehouseTab?: string
    storeWarehouseTab?: string
    inspectionTab?: string
  }
}
