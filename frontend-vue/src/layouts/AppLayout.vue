<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { Menu, Search, X } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import AppSidebar from '../components/sidebar/AppSidebar.vue'
import { useAuthStore } from '../stores/auth'
import { useWarehouseStore } from '../stores/warehouse'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()
const warehouse = useWarehouseStore()
const mobileNavOpen = ref(false)
const searchText = ref('')

const roleGreeting = computed(() => {
  if (auth.role === 'ADMIN') return '系统管理员，你好'
  if (auth.role === 'BOSS' || auth.role === 'OWNER') return '老板，你好'
  if (auth.role === 'FINANCE') return '财务，你好'
  if (auth.role === 'WAREHOUSE') return '仓库管理员，你好'
  if (auth.role === 'SUPERVISOR') return '督导，你好'
  if (auth.role === 'STORE_MANAGER') return '店长，你好'
  if (auth.role === 'OPERATIONS' || auth.role === 'OPS') return '运营，你好'
  if (auth.role === 'EMPLOYEE') return '员工，你好'
  return '你好'
})

const updatedAt = computed(() => {
  const now = new Date()
  return `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(now.getDate()).padStart(2, '0')} ${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}`
})

async function logout() {
  if (auth.loggingOut) return
  mobileNavOpen.value = false
  searchText.value = ''
  try {
    await auth.logout()
  } finally {
    warehouse.clear()
    await router.replace({ name: 'login' })
  }
}

function submitSearch() {
  if (!searchText.value.trim()) return
  void router.push({ path: '/assistant', query: { q: searchText.value.trim() } })
}

watch(
  () => route.fullPath,
  () => {
    mobileNavOpen.value = false
  },
)
</script>

<template>
  <div class="app-shell">
    <AppSidebar mode="desktop" @logout="logout" />
    <div v-if="mobileNavOpen" class="mobile-nav-backdrop" @click="mobileNavOpen = false" />
    <div class="mobile-nav-drawer" :class="{ open: mobileNavOpen }">
      <div class="mobile-nav-head">
        <b>菜单</b>
        <button class="icon-button" type="button" aria-label="关闭菜单" @click="mobileNavOpen = false">
          <X :size="18" />
        </button>
      </div>
      <AppSidebar mode="mobile" @navigate="mobileNavOpen = false" @logout="logout" />
    </div>

    <main class="app-main">
      <header class="topbar legacy-topbar">
        <button class="mobile-menu-button" type="button" aria-label="打开菜单" @click="mobileNavOpen = true">
          <Menu :size="20" />
        </button>
        <div class="topbar-title legacy-title">
          <h1>{{ roleGreeting }}</h1>
          <div class="trust-bar">
            <span><b>更新时间</b>{{ updatedAt }}</span>
            <span><b>访问范围</b>{{ auth.scopeText }}</span>
          </div>
        </div>
        <div class="top-actions legacy-actions">
          <form v-if="auth.role !== 'EMPLOYEE'" class="legacy-search" @submit.prevent="submitSearch">
            <Search :size="17" />
            <input v-model="searchText" type="search" placeholder="输入问题，回车询问助手" />
          </form>
          <button class="role-display" type="button" aria-label="当前角色">
            {{ auth.roleLabel }}
          </button>
        </div>
      </header>
      <RouterView />
    </main>
  </div>
</template>
