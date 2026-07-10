<script setup lang="ts">
import { ShieldAlert } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const auth = useAuthStore()

function goHome() {
  if (auth.role === 'ADMIN' || auth.role === 'BOSS' || auth.role === 'OWNER') void router.push('/boss')
  else if (auth.role === 'FINANCE') void router.push('/finance')
  else if (auth.role === 'WAREHOUSE') void router.push('/warehouse')
  else if (auth.role === 'SUPERVISOR') void router.push('/inspection')
  else if (auth.role === 'STORE_MANAGER') void router.push('/warehouse')
  else if (auth.role === 'OPERATIONS' || auth.role === 'OPS') void router.push('/operations')
  else void router.push('/')
}
</script>

<template>
  <section class="page-panel no-permission-page">
    <div class="empty-state">
      <ShieldAlert :size="34" />
      <b>当前账号没有访问该页面的权限</b>
      <span>请回到你的角色工作台处理业务。如确实需要访问，请联系老板或管理员调整权限。</span>
      <button class="primary-button submit-inline" type="button" @click="goHome">返回我的工作台</button>
    </div>
  </section>
</template>

<style scoped>
.no-permission-page {
  max-width: 760px;
}
</style>
