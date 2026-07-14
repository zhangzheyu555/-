<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { BarChart3, Bot, Building2, ClipboardCheck, ClipboardPenLine, Download, GraduationCap, Home, LogOut, Package, ReceiptText, ShieldCheck, UserRound, UserRoundCog, Warehouse } from 'lucide-vue-next'
import { useAuthStore } from '../../stores/auth'
import {
  resolveMenuGroups,
  resolveUtilityMenuItems,
  type MenuIconKey,
  type PermissionMenuItem,
} from '../../permissions/menu'

const auth = useAuthStore()
const route = useRoute()
const menuScrollRef = ref<HTMLElement | null>(null)
withDefaults(defineProps<{
  mode?: 'desktop' | 'mobile'
}>(), {
  mode: 'desktop',
})
const emit = defineEmits<{
  navigate: []
  logout: []
}>()

const menuSubject = computed(() => ({
  role: auth.role,
  permissions: auth.permissions,
  defaultWorkspace: auth.defaultWorkspace,
  dataScopes: auth.dataScopes,
}))
const menuGroups = computed(() => resolveMenuGroups(menuSubject.value))
const utilityMenuItems = computed(() => resolveUtilityMenuItems(menuSubject.value))

const menuIcons: Record<MenuIconKey, unknown> = {
  assistant: Bot,
  dashboard: Home,
  exam: GraduationCap,
  expense: ReceiptText,
  export: Download,
  inspection: ClipboardCheck,
  inventory: Package,
  log: ClipboardPenLine,
  platform: ShieldCheck,
  profit: BarChart3,
  salary: ClipboardPenLine,
  store: Building2,
  users: UserRoundCog,
  warehouse: Warehouse,
}

function menuIcon(icon: MenuIconKey) {
  return menuIcons[icon]
}

const activeMenuKey = computed(() => String(route.meta.menuKey || ''))

function isMenuActive(item: PermissionMenuItem) {
  return item.key === activeMenuKey.value
}

async function revealActiveMenuItem() {
  await nextTick()
  const menu = menuScrollRef.value
  const activeElement = menu?.querySelector<HTMLElement>('[aria-current="page"]')
  if (!menu || !activeElement) return

  const menuRect = menu.getBoundingClientRect()
  const activeRect = activeElement.getBoundingClientRect()
  const visibilityTolerance = 1
  const isVisible = activeRect.top >= menuRect.top - visibilityTolerance
    && activeRect.bottom <= menuRect.bottom + visibilityTolerance
  if (isVisible) return

  activeElement.scrollIntoView({
    block: 'nearest',
    inline: 'nearest',
    behavior: window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth',
  })
}

watch(
  [() => route.fullPath, menuGroups],
  () => void revealActiveMenuItem(),
  { immediate: true, flush: 'post' },
)
</script>

<template>
  <aside class="app-sidebar" :class="`app-sidebar--${mode}`">
    <header class="sidebar-brand">
      <img class="sidebar-brand-icon" src="/icons/app-icon-192.png" alt="" width="32" height="32" />
      <span>门店经营</span>
    </header>

    <nav ref="menuScrollRef" class="sidebar-navigation" tabindex="0" aria-label="主导航">
      <div class="sidebar-navigation-primary">
        <div v-for="group in menuGroups" :key="group.title || 'default'" class="sidebar-navigation-group">
          <div v-if="group.title" class="sidebar-navigation-title">{{ group.title }}</div>
          <RouterLink v-for="item in group.items" :key="item.to" v-slot="{ href, navigate }" custom :to="item.to">
            <a
              class="sidebar-navigation-link"
              :class="{ 'router-link-active': isMenuActive(item) }"
              :href="href"
              :aria-current="isMenuActive(item) ? 'page' : undefined"
              :data-menu-key="item.key"
              @click="emit('navigate'); navigate($event)"
            >
              <component :is="menuIcon(item.icon)" :size="18" />
              <span>{{ item.label }}</span>
            </a>
          </RouterLink>
        </div>
      </div>

      <div v-if="utilityMenuItems.length" class="sidebar-navigation-utility">
        <div class="sidebar-navigation-group">
          <div class="sidebar-navigation-title">辅助工具</div>
          <RouterLink v-for="item in utilityMenuItems" :key="item.to" v-slot="{ href, navigate }" custom :to="item.to">
            <a
              class="sidebar-navigation-link"
              :class="{ 'router-link-active': isMenuActive(item) }"
              :href="href"
              :aria-current="isMenuActive(item) ? 'page' : undefined"
              :data-menu-key="item.key"
              @click="emit('navigate'); navigate($event)"
            >
              <component :is="menuIcon(item.icon)" :size="18" />
              <span>{{ item.label }}</span>
            </a>
          </RouterLink>
        </div>
      </div>
    </nav>

    <footer class="sidebar-account">
      <div class="sidebar-account-user">
        <span class="sidebar-account-avatar" aria-hidden="true"><UserRound :size="18" /></span>
        <span class="sidebar-account-copy">
          <strong :title="auth.user?.displayName || '当前用户'">{{ auth.user?.displayName || '当前用户' }}</strong>
          <span>{{ auth.roleLabel }}</span>
        </span>
      </div>
      <button class="sidebar-logout" type="button" :disabled="auth.loggingOut" @click="emit('navigate'); emit('logout')">
        <LogOut :size="17" />
        <span>{{ auth.loggingOut ? '正在退出' : '退出登录' }}</span>
      </button>
    </footer>
  </aside>
</template>

<style scoped>
.app-sidebar {
  display: grid;
  width: var(--ds-sidebar-width);
  height: 100vh;
  height: 100dvh;
  min-height: 0;
  grid-template-rows: auto minmax(0, 1fr) auto;
  padding: 0;
  overflow: hidden;
  border-right: 1px solid var(--ds-line);
  background: #fff;
  color: var(--ds-text);
}

.app-sidebar--desktop {
  position: relative;
  z-index: 1300;
}

.sidebar-brand {
  display: flex;
  height: 62px;
  flex: none;
  position: relative;
  z-index: 2;
  align-items: center;
  gap: 10px;
  padding: 0 19px;
  border-bottom: 1px solid var(--ds-line);
  background: var(--surface, #fff);
  color: var(--ds-ink);
  font-size: 17px;
  font-weight: 800;
}

.sidebar-brand-icon {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 7px;
  object-fit: contain;
}

.sidebar-navigation {
  min-width: 0;
  min-height: 0;
  overflow-x: hidden;
  overflow-y: auto;
  padding: 9px 12px 11px;
  overscroll-behavior-y: contain;
  touch-action: pan-y;
  -webkit-overflow-scrolling: touch;
  scrollbar-width: none;
  -ms-overflow-style: none;
  scrollbar-gutter: auto;
  outline: none;
}

.sidebar-navigation:focus-visible {
  box-shadow: inset 0 0 0 2px var(--ds-primary-soft);
}

.sidebar-navigation::-webkit-scrollbar,
.sidebar-navigation::-webkit-scrollbar-track,
.sidebar-navigation::-webkit-scrollbar-thumb,
.sidebar-navigation::-webkit-scrollbar-button {
  display: none;
  width: 0;
  height: 0;
}

.sidebar-navigation-primary,
.sidebar-navigation-utility,
.sidebar-navigation-group {
  flex: none;
}

.sidebar-navigation-group {
  display: grid;
  align-content: start;
  gap: 2px;
}

.sidebar-navigation-group + .sidebar-navigation-group,
.sidebar-navigation-utility {
  margin-top: 11px;
}

.sidebar-navigation-title {
  height: 20px;
  margin: 0 8px 2px;
  padding: 0;
  color: var(--ds-secondary);
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
}

.sidebar-navigation-link {
  display: flex;
  min-height: 37px;
  align-items: center;
  gap: 10px;
  margin: 0;
  padding: 0 11px;
  border-radius: 6px;
  color: var(--ds-secondary);
  font-size: 15px;
  font-weight: 600;
  text-decoration: none;
}

.sidebar-navigation-link:hover {
  background: var(--ds-surface-muted);
  color: var(--ds-ink);
}

.sidebar-navigation-link.router-link-active {
  background: var(--ds-primary-soft);
  color: var(--ds-primary-hover);
}

.sidebar-navigation-link.router-link-active svg {
  color: var(--ds-primary-hover);
}

.sidebar-account {
  display: grid;
  position: relative;
  z-index: 2;
  flex: none;
  gap: 8px;
  padding: 12px 14px 13px;
  border-top: 1px solid var(--ds-line);
  background: var(--surface, #fff);
}

.sidebar-account-user {
  display: flex;
  min-width: 0;
  min-height: 36px;
  align-items: center;
  gap: 10px;
}

.sidebar-account-avatar {
  display: inline-flex;
  width: 32px;
  height: 32px;
  flex: none;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--ds-primary-soft);
  color: var(--ds-primary);
}

.sidebar-account-copy {
  display: grid;
  min-width: 0;
  flex: 1;
  gap: 1px;
}

.sidebar-account-copy strong,
.sidebar-account-copy span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-account-copy strong {
  color: var(--ds-ink);
  font-size: 14px;
  font-weight: 700;
  line-height: 18px;
}

.sidebar-account-copy span {
  color: var(--ds-muted);
  font-size: 12px;
  line-height: 16px;
}

.sidebar-logout {
  display: flex;
  min-height: 35px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: #fff;
  color: var(--ds-secondary);
  font-size: 14px;
  font-weight: 600;
}

.sidebar-logout:hover:not(:disabled) {
  border-color: #efc4c8;
  background: var(--ds-danger-soft);
  color: var(--ds-danger);
}

.sidebar-logout:disabled {
  cursor: wait;
  opacity: 0.62;
}

@media (max-width: 768px) {
  .app-sidebar--desktop {
    display: none;
  }

  .app-sidebar--mobile {
    width: 100%;
    height: 100%;
    border-right: 0;
  }

  .app-sidebar--mobile .sidebar-brand {
    height: 68px;
    padding: 0 18px;
  }

  .app-sidebar--mobile .sidebar-navigation {
    padding: 10px 12px 12px;
  }

  .app-sidebar--mobile .sidebar-navigation-link {
    min-height: 44px;
    white-space: normal;
  }

  .app-sidebar--mobile .sidebar-account {
    padding: 12px 14px max(13px, env(safe-area-inset-bottom));
  }
}
</style>
