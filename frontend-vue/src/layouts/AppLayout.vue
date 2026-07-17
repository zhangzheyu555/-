<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Bell, CalendarDays, ChevronDown, Menu, Search, X } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { getStores, type StoreInfo } from '../api/operations'
import SearchInput from '../components/common/SearchInput.vue'
import AppSidebar from '../components/sidebar/AppSidebar.vue'
import { useBusinessScope } from '../composables/useBusinessScope'
import { PERMISSIONS } from '../permissions/permissions'
import { useAuthStore } from '../stores/auth'
import { useWarehouseStore } from '../stores/warehouse'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const businessScope = useBusinessScope()
const warehouse = useWarehouseStore()
const mobileNavOpen = ref(false)
const mobileMenuButton = ref<HTMLButtonElement | null>(null)
const mobileNavDrawer = ref<HTMLElement | null>(null)
const mobileDrawerCloseButton = ref<HTMLButtonElement | null>(null)
const searchText = ref('')
const searchOpen = ref(false)
const searchInput = ref<InstanceType<typeof SearchInput> | null>(null)
const globalStores = ref<StoreInfo[]>([])
const storesLoading = ref(false)
const storesLoaded = ref(false)
const canUseAssistant = computed(() => auth.hasPermission(PERMISSIONS.ASSISTANT_USE))
const canReadTodos = computed(() => auth.hasPermission(PERMISSIONS.TODO_READ))
const hideTopbarStoreSelector = computed(() =>
  auth.role === 'EMPLOYEE' || (businessScope.isBoss.value && route.path === '/assistant')
)

const updatedAt = computed(() => {
  const now = new Date()
  return `${now.getFullYear()}年${String(now.getMonth() + 1).padStart(2, '0')}月${String(now.getDate()).padStart(2, '0')}日`
})

const selectedStoreId = computed(() => {
  const value = route.query.storeId
  return Array.isArray(value) ? String(value[0] || '') : String(value || '')
})

const topbarStores = computed(() => {
  const stores = new Map<string, { id: string; name: string }>()
  for (const store of globalStores.value) stores.set(store.id, { id: store.id, name: store.name || store.code || store.id })
  for (const id of auth.storeScope) {
    if (id !== 'all' && !stores.has(id)) stores.set(id, { id, name: `门店 ${id}` })
  }
  if (selectedStoreId.value && !stores.has(selectedStoreId.value)) {
    stores.set(selectedStoreId.value, { id: selectedStoreId.value, name: `门店 ${selectedStoreId.value}` })
  }
  return Array.from(stores.values())
})

async function logout() {
  if (auth.loggingOut) return
  closeMobileNav({ restoreFocus: false })
  searchText.value = ''
  try {
    await auth.logout()
  } finally {
    warehouse.clear()
    await router.replace({ name: 'login' })
  }
}

function submitSearch() {
  const question = searchText.value.trim()
  if (!question) return
  searchOpen.value = false
  searchText.value = ''
  void router.push({ path: '/assistant', query: { q: question } })
}

async function openSearch() {
  searchOpen.value = true
  await nextTick()
  searchInput.value?.focus()
}

function closeSearch() {
  searchOpen.value = false
  searchText.value = ''
}

async function ensureStoresLoaded() {
  if (storesLoaded.value || storesLoading.value) return
  storesLoading.value = true
  try {
    globalStores.value = await getStores()
    storesLoaded.value = true
  } catch (error) {
    console.warn('[Topbar] 门店列表加载失败', error)
  } finally {
    storesLoading.value = false
  }
}

function selectGlobalStore(event: Event) {
  const storeId = (event.target as HTMLSelectElement).value
  const query = { ...route.query }
  if (storeId) query.storeId = storeId
  else delete query.storeId
  void router.push({ path: route.path, query })
}

function focusDrawerCloseButton() {
  mobileDrawerCloseButton.value?.focus()
}

function keepFocusInsideMobileNav(event: FocusEvent) {
  if (!mobileNavOpen.value) return
  const drawer = mobileNavDrawer.value
  const target = event.target
  if (!drawer || !(target instanceof Node) || drawer.contains(target)) return
  focusDrawerCloseButton()
}

function handleDocumentKeydown(event: KeyboardEvent) {
  if (event.key !== 'Escape' || !mobileNavOpen.value) return
  event.preventDefault()
  closeMobileNav()
}

async function openMobileNav() {
  if (mobileNavOpen.value) return
  mobileNavOpen.value = true
  document.body.classList.add('drawer-open')
  await nextTick()
  focusDrawerCloseButton()
}

function closeMobileNav(options: { restoreFocus?: boolean } = {}) {
  const { restoreFocus = true } = options
  if (!mobileNavOpen.value) return
  mobileNavOpen.value = false
  document.body.classList.remove('drawer-open')
  if (restoreFocus) {
    void nextTick(() => mobileMenuButton.value?.focus())
  }
}

function closeMobileNavForNavigation() {
  closeMobileNav({ restoreFocus: false })
}

function drawerFocusableElements() {
  const drawer = mobileNavDrawer.value
  if (!drawer) return []
  return Array.from(drawer.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )).filter((element) => !element.hasAttribute('hidden') && element.getClientRects().length > 0)
}

function handleDrawerKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.preventDefault()
    closeMobileNav()
    return
  }
  if (event.key !== 'Tab') return

  const focusable = drawerFocusableElements()
  if (!focusable.length) {
    event.preventDefault()
    mobileNavDrawer.value?.focus()
    return
  }

  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  const activeElement = document.activeElement
  if (event.shiftKey && activeElement === first) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && activeElement === last) {
    event.preventDefault()
    first.focus()
  }
}

watch(
  () => route.fullPath,
  () => {
    closeMobileNav({ restoreFocus: false })
    searchOpen.value = false
    document.body.classList.remove('modal-open', 'drawer-open', 'menu-open')
  },
)

onBeforeUnmount(() => {
  document.removeEventListener('focusin', keepFocusInsideMobileNav)
  document.removeEventListener('keydown', handleDocumentKeydown)
  document.body.classList.remove('drawer-open')
})

onMounted(() => {
  document.addEventListener('focusin', keepFocusInsideMobileNav)
  document.addEventListener('keydown', handleDocumentKeydown)
})
</script>

<template>
  <div class="app-shell">
    <AppSidebar v-if="!mobileNavOpen" mode="desktop" @logout="logout" />
    <div v-if="mobileNavOpen" class="mobile-nav-backdrop" aria-hidden="true" @click="closeMobileNav()" />
    <div
      v-if="mobileNavOpen"
      id="mobile-navigation-drawer"
      ref="mobileNavDrawer"
      class="mobile-nav-drawer open"
      role="dialog"
      aria-modal="true"
      aria-labelledby="mobile-navigation-title"
      tabindex="-1"
      @keydown="handleDrawerKeydown"
    >
      <div class="mobile-nav-head">
        <b id="mobile-navigation-title">菜单</b>
        <button ref="mobileDrawerCloseButton" class="icon-button" type="button" aria-label="关闭菜单" @click="closeMobileNav()">
          <X :size="18" />
        </button>
      </div>
      <AppSidebar mode="mobile" @navigate="closeMobileNavForNavigation" @logout="logout" />
    </div>

    <main
      class="app-main"
      :class="{ 'assistant-route': route.path === '/assistant' }"
      :inert="mobileNavOpen || undefined"
      :aria-hidden="mobileNavOpen || undefined"
    >
      <header class="topbar app-topbar">
        <div class="topbar-primary-row">
          <button
            ref="mobileMenuButton"
            class="mobile-menu-button"
            type="button"
            aria-label="打开菜单"
            aria-controls="mobile-navigation-drawer"
            :aria-expanded="mobileNavOpen"
            @click="openMobileNav"
          >
            <Menu :size="20" />
          </button>
          <div class="topbar-context">
            <span class="date-display"><CalendarDays :size="16" />{{ updatedAt }}</span>
            <label v-if="!businessScope.isStoreManager.value && !hideTopbarStoreSelector" class="scope-display" :title="auth.scopeText">
              <select
                :value="selectedStoreId"
                aria-label="全局门店"
                :disabled="storesLoading"
                @focus="ensureStoresLoaded"
                @change="selectGlobalStore"
              >
                <option value="">{{ storesLoading ? '正在加载门店' : '全部门店' }}</option>
                <option v-for="store in topbarStores" :key="store.id" :value="store.id">{{ store.name }}</option>
              </select>
              <ChevronDown :size="15" />
            </label>
            <form
              v-if="canUseAssistant && searchOpen"
              class="global-search expanded"
              role="search"
              @submit.prevent="submitSearch"
              @keydown.esc.prevent="closeSearch"
            >
              <SearchInput
                ref="searchInput"
                v-model="searchText"
                class="global-search-field"
                aria-label="搜索经营数据"
                placeholder="搜索门店、待办或经营数据"
              />
              <button class="search-close" type="button" aria-label="关闭搜索" @click="closeSearch">
                <X :size="15" />
              </button>
            </form>
            <button
              v-else-if="canUseAssistant"
              class="search-trigger"
              type="button"
              aria-label="打开全局搜索"
              :aria-expanded="searchOpen"
              @click="openSearch"
            >
              <Search :size="18" />
            </button>
            <button v-if="canReadTodos" class="notification-button" type="button" aria-label="消息提醒">
              <Bell :size="18" />
            </button>
          </div>
        </div>
      </header>
      <RouterView :key="route.path" />
    </main>
  </div>
</template>

<style scoped>
.app-main {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  position: relative;
  z-index: 0;
  isolation: isolate;
}

.app-main > :deep(*) {
  min-width: 0;
}

.app-main.assistant-route {
  overflow: hidden;
}

.app-main.assistant-route > :deep(.store-assistant-page) {
  height: 100%;
  min-height: 0;
  flex: 1 1 auto;
}

.app-topbar {
  min-height: 60px;
  padding-top: 10px;
  padding-bottom: 8px;
}

.topbar-primary-row,
.topbar-context,
.date-display,
.scope-display,
.global-search {
  display: flex;
  align-items: center;
}

.topbar-primary-row {
  justify-content: flex-end;
  gap: 20px;
}

.topbar-context {
  flex: none;
  gap: 10px;
}

.date-display,
.scope-display {
  min-height: 34px;
  gap: 7px;
  color: var(--ds-secondary);
  font-size: 14px;
  font-weight: 500;
}

.scope-display {
  position: relative;
  max-width: 220px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: #fff;
}

.scope-display select {
  width: 100%;
  min-width: 128px;
  max-width: 200px;
  height: 32px;
  padding: 0 30px 0 10px;
  border: 0;
  background: transparent;
  color: var(--ds-secondary);
  font: inherit;
  appearance: none;
  outline: none;
}

.scope-display svg {
  position: absolute;
  right: 9px;
  pointer-events: none;
}

.notification-button,
.search-trigger,
.search-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--ds-secondary);
}

.notification-button,
.search-trigger {
  width: 34px;
  height: 34px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: #fff;
}

.notification-button:hover,
.search-trigger:hover {
  border-color: var(--ds-primary);
  color: var(--ds-primary-hover);
}

.global-search {
  width: min(360px, 32vw);
  height: 44px;
  gap: 6px;
  padding: 0;
  border: 0;
  background: transparent;
}

.global-search > .global-search-field {
  flex: 1;
}

.search-close {
  width: 24px;
  height: 24px;
  flex: none;
  border: 0;
  background: transparent;
}

@media (max-width: 768px) {
  .topbar-primary-row {
    width: 100%;
    min-width: 0;
  }

  .notification-button,
  .search-trigger,
  .search-close {
    width: 44px;
    height: 44px;
  }

  .topbar-context {
    flex: 1 1 auto;
    min-width: 0;
    gap: 8px;
  }

  .scope-display {
    flex: 1 1 auto;
    min-width: 0;
  }

  .scope-display select {
    min-width: 0;
    max-width: 100%;
  }
}

@media (max-width: 520px) {
  .date-display {
    display: none;
  }
}
</style>
