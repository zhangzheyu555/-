<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const error = ref('')

async function submit() {
  error.value = ''
  if (!username.value.trim()) {
    error.value = '请输入账号'
    return
  }
  if (!password.value) {
    error.value = '请输入密码'
    return
  }
  try {
    await auth.login(username.value.trim(), password.value)
    const redirect = resolveRedirect(route.query.redirect, auth.role)
    await router.push(redirect)
  } catch (err) {
    error.value = loginErrorMessage(err)
  }
}

function resolveRedirect(redirect: unknown, role: string) {
  if (typeof redirect !== 'string' || !redirect.trim()) {
    return defaultRouteForRole(role)
  }
  if (redirect.startsWith('/login')) {
    return defaultRouteForRole(role)
  }
  return redirect
}

function loginErrorMessage(err: unknown) {
  const message = err instanceof Error ? err.message : ''
  if (message.includes('timeout') || message.includes('超时')) return '登录请求超时，请稍后重试'
  if (message.includes('Network Error')) return '后端不可用，请检查 8080 服务'
  if (message.includes('401') || message.includes('403')) return '账号或密码错误'
  return message || '登录失败，请检查账号和密码'
}

function defaultRouteForRole(role: string) {
  if (role === 'ADMIN' || role === 'BOSS' || role === 'OWNER') return '/boss'
  if (role === 'FINANCE') return '/finance'
  if (role === 'WAREHOUSE') return '/warehouse'
  if (role === 'SUPERVISOR') return '/inspection'
  if (role === 'STORE_MANAGER') return '/warehouse'
  if (role === 'OPERATIONS' || role === 'OPS') return '/operations'
  return '/'
}
</script>

<template>
  <div class="login-screen">
    <form class="login-card" @submit.prevent="submit">
      <div class="login-logo">
        <span class="brand-mark" />
        <span>AI <b>Profit</b></span>
      </div>
      <h1>多门店经营异常处理系统</h1>

      <label>
        登录账号
        <input v-model="username" autocomplete="username" placeholder="请输入账号" />
      </label>
      <label>
        登录密码
        <input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" />
      </label>

      <div v-if="error" class="form-error">{{ error }}</div>
      <button class="primary-button" type="submit" :disabled="auth.loading">
        {{ auth.loading ? '正在登录...' : '登录' }}
      </button>
    </form>
  </div>
</template>
