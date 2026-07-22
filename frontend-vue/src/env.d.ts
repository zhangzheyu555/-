/// <reference types="vite/client" />

import 'vue-router'

declare module 'vue-router' {
  interface RouteMeta {
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
