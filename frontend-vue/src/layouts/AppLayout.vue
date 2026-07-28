<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { CalendarDays, Menu, X } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import AppSidebar from '../components/sidebar/AppSidebar.vue'
import { useAuthStore } from '../stores/auth'
import { useWarehouseStore } from '../stores/warehouse'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const warehouse = useWarehouseStore()
const mobileNavOpen = ref(false)
const mobileMenuButton = ref<HTMLButtonElement | null>(null)
const mobileNavDrawer = ref<HTMLElement | null>(null)
const mobileDrawerCloseButton = ref<HTMLButtonElement | null>(null)

const updatedAt = computed(() => {
  const now = new Date()
  return `${now.getFullYear()}年${String(now.getMonth() + 1).padStart(2, '0')}月${String(now.getDate()).padStart(2, '0')}日`
})

async function logout() {
  if (auth.loggingOut) return
  closeMobileNav({ restoreFocus: false })
  try {
    await auth.logout()
  } finally {
    warehouse.clear()
    await router.replace({ name: 'login' })
  }
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
      <header class="topbar app-topbar" :class="{ 'daily-loss-topbar': route.path === '/daily-loss' }">
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
          <div v-if="route.path !== '/daily-loss'" class="topbar-context">
            <span class="date-display"><CalendarDays :size="16" />{{ updatedAt }}</span>
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

.daily-loss-topbar {
  display: none;
}

@media (max-width: 1024px) {
  .daily-loss-topbar {
    display: grid;
    min-height: 0;
  }
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
.date-display {
  display: flex;
  align-items: center;
}

.topbar-primary-row {
  justify-content: flex-start;
  gap: 20px;
}

.topbar-context {
  flex: none;
  gap: 10px;
}

.date-display {
  min-height: 34px;
  gap: 7px;
  color: var(--ds-secondary);
  font-size: 14px;
  font-weight: 500;
}

@media (max-width: 768px) {
  .topbar-primary-row {
    width: 100%;
    min-width: 0;
  }

  .topbar-context {
    flex: 1 1 auto;
    min-width: 0;
    gap: 8px;
  }
}

@media (max-width: 520px) {
  .date-display {
    display: none;
  }
}
</style>
