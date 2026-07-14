<script setup lang="ts">
import { computed } from 'vue'
import { ShieldAlert } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { resolveAvailableWorkspace } from '../permissions/workspaces'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const missingStoreBinding = computed(() => (
  route.query.reason === 'STORE_NOT_BOUND'
  || (auth.role === 'STORE_MANAGER' && !auth.storeManagerHasStoreBinding)
))
const storeConfiguration = computed(() => {
  const ids = Array.from(new Set((auth.user?.dataScope?.storeIds || []).filter((storeId) => storeId && storeId !== 'all')))
  if (ids.length > 1) {
    return {
      title: '当前店长账号绑定了多个门店',
      detail: '店长账号只能负责一家门店，请联系老板调整为唯一绑定门店后重新登录。',
    }
  }
  if (ids.length === 1 && (!auth.user?.boundStoreId || auth.user.boundStoreId !== ids[0])) {
    return {
      title: '当前店长账号的门店范围配置不一致',
      detail: '请联系老板重新保存绑定门店和数据范围后再登录。系统不会使用请求参数代替账号范围。',
    }
  }
  return {
    title: '当前店长账号尚未绑定门店',
    detail: '请联系老板（系统管理员）绑定负责门店后重新登录。系统不会自动分配或展示其他门店数据。',
  }
})
const availableWorkspace = computed(() => (
  auth.defaultWorkspace === '/no-permission' ? null : resolveAvailableWorkspace(auth)
))

async function goHome() {
  const target = availableWorkspace.value
  if (!missingStoreBinding.value && target) {
    await router.replace(target)
    return
  }

  if (auth.isLoggedIn) {
    await auth.logout()
  }
  await router.replace({ name: 'login' })
}
</script>

<template>
  <section class="page-panel no-permission-page">
    <div class="empty-state">
      <ShieldAlert :size="34" />
      <b>{{ missingStoreBinding ? storeConfiguration.title : '当前账号没有访问该页面的权限' }}</b>
      <span v-if="missingStoreBinding">{{ storeConfiguration.detail }}</span>
      <span v-else-if="availableWorkspace">请返回有权限的角色工作台处理业务。如确实需要访问，请联系老板（系统管理员）调整权限。</span>
      <span v-else>该账号尚未配置任何可用工作台，请重新登录其他账号，或联系老板（系统管理员）配置权限。</span>
      <button class="primary-button submit-inline" type="button" @click="goHome">
        {{ missingStoreBinding || !availableWorkspace ? '退出并重新登录' : '返回可用工作台' }}
      </button>
    </div>
  </section>
</template>

<style scoped>
.no-permission-page {
  max-width: 760px;
}
</style>
