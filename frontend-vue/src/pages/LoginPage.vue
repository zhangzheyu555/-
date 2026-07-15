<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { AlertCircle, Eye, EyeOff, Headphones, LockKeyhole, ShieldCheck, UserRound } from 'lucide-vue-next'
import { useRoute, useRouter } from 'vue-router'
import { ApiError } from '../api/http'
import { getHealth } from '../api/health'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const username = ref('')
const password = ref('')
const rememberUsername = ref(false)
const passwordVisible = ref(false)
const capsLockOn = ref(false)
const usernameError = ref('')
const passwordError = ref('')
const submitError = ref('')
const showHelp = ref(false)
const systemHealthy = ref(false)
const REMEMBERED_USERNAME_KEY = 'ai_profit_remembered_username'

async function submit() {
  if (auth.loading) return
  usernameError.value = ''
  passwordError.value = ''
  submitError.value = ''
  if (!username.value.trim()) {
    usernameError.value = '请输入账号'
    return
  }
  if (!password.value) {
    passwordError.value = '请输入密码'
    return
  }
  try {
    await auth.login(username.value.trim(), password.value)
    if (rememberUsername.value) {
      localStorage.setItem(REMEMBERED_USERNAME_KEY, username.value.trim())
    } else {
      localStorage.removeItem(REMEMBERED_USERNAME_KEY)
    }
    const redirect = resolveRedirect(route.query.redirect)
    await router.push(redirect)
  } catch (err) {
    applyLoginError(err)
  }
}

function resolveRedirect(redirect: unknown) {
  if (typeof redirect !== 'string' || !redirect.trim()) return '/'
  const target = redirect.trim()
  if (!target.startsWith('/') || target.startsWith('//') || target.startsWith('/login')) return '/'
  const matched = router.resolve(target).matched
  if (!matched.length) return '/'
  const requiredPermission = [...matched]
    .reverse()
    .map((record) => record.meta.permission)
    .find((permission): permission is string => typeof permission === 'string' && Boolean(permission))
  if (requiredPermission && !auth.hasPermission(requiredPermission)) return '/'
  return target
}

function applyLoginError(err: unknown) {
  const status = err instanceof ApiError ? err.status : undefined
  const code = err instanceof ApiError ? err.code : undefined
  const message = err instanceof Error ? err.message : ''
  if (status === 401) {
    passwordError.value = '账号或密码错误'
    return
  }
  if (status === 429) {
    submitError.value = '登录尝试过多，请稍后再试'
    return
  }
  if (status === 503 && code === 'BACKEND_UNAVAILABLE') {
    submitError.value = '暂时无法连接服务器'
    return
  }
  if (status && status >= 500) {
    submitError.value = '服务器暂时无法完成登录，请稍后重试'
    return
  }
  if (!status || /network|连接|请求已取消/i.test(message)) {
    submitError.value = message.includes('超时') ? '登录请求超时，请稍后重试' : '暂时无法连接服务器'
    return
  }
  submitError.value = '登录失败，请稍后重试'
}

function updateCapsLock(event: Event) {
  const keyboardEvent = event as KeyboardEvent
  capsLockOn.value = typeof keyboardEvent.getModifierState === 'function'
    ? keyboardEvent.getModifierState('CapsLock')
    : false
}

function toggleRemember(event: Event) {
  rememberUsername.value = (event.target as HTMLInputElement).checked
  if (!rememberUsername.value) localStorage.removeItem(REMEMBERED_USERNAME_KEY)
}

onMounted(async () => {
  if (route.query.reason === 'SESSION_EXPIRED') {
    submitError.value = '登录已失效，请重新登录后重试。'
  }
  const remembered = localStorage.getItem(REMEMBERED_USERNAME_KEY) || ''
  if (remembered) {
    username.value = remembered
    rememberUsername.value = true
  }
  try {
    const health = await getHealth()
    systemHealthy.value = /^(up|ok|healthy)$/i.test(String(health.status || ''))
  } catch {
    systemHealthy.value = false
  }
})
</script>

<template>
  <div class="login-screen">
    <header class="login-brandbar">
      <div class="login-logo">
        <img src="/icons/app-icon-192.png" alt="" width="40" height="40" />
        <span>门店经营</span>
      </div>
    </header>

    <main class="login-workspace">
      <section class="login-intro">
        <ShieldCheck :size="52" stroke-width="1.7" />
        <div>
          <h1>安全登录</h1>
          <p>使用公司账号进入工作台</p>
        </div>
      </section>

      <form class="login-form" novalidate @submit.prevent="submit">
        <div class="login-field" :class="{ invalid: usernameError }">
          <label for="login-username">账号</label>
          <div class="input-shell">
            <UserRound :size="21" />
            <input
              id="login-username"
              v-model="username"
              autocomplete="username"
              placeholder="请输入账号"
              :aria-invalid="Boolean(usernameError)"
              @input="usernameError = ''; submitError = ''"
            />
          </div>
          <span v-if="usernameError" class="field-error"><AlertCircle :size="16" />{{ usernameError }}</span>
        </div>

        <div class="login-field" :class="{ invalid: passwordError }">
          <label for="login-password">密码</label>
          <div class="input-shell">
            <LockKeyhole :size="20" />
            <input
              id="login-password"
              v-model="password"
              :type="passwordVisible ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="请输入密码"
              :aria-invalid="Boolean(passwordError)"
              @keydown="updateCapsLock"
              @keyup="updateCapsLock"
              @input="passwordError = ''; submitError = ''"
            />
            <button class="password-toggle" type="button" :aria-label="passwordVisible ? '隐藏密码' : '显示密码'" @click="passwordVisible = !passwordVisible">
              <EyeOff v-if="passwordVisible" :size="20" />
              <Eye v-else :size="20" />
            </button>
          </div>
          <span v-if="passwordError" class="field-error"><AlertCircle :size="16" />{{ passwordError }}</span>
          <span v-else-if="capsLockOn" class="caps-lock-tip">大写锁定已开启</span>
        </div>

        <div class="login-options">
          <label class="remember-account">
            <input type="checkbox" :checked="rememberUsername" @change="toggleRemember" />
            <span>记住账号</span>
          </label>
          <button type="button" class="help-link" @click="showHelp = !showHelp">登录帮助</button>
        </div>

        <div v-if="submitError" class="submit-error"><AlertCircle :size="16" />{{ submitError }}</div>
        <div v-if="showHelp" class="login-help">请联系系统维护人员核验身份后，通过账号权限执行安全重置。</div>

        <button class="login-submit" type="submit" :disabled="auth.loading">
          {{ auth.loading ? '正在登录...' : '登录' }}
        </button>

        <div class="support-row"><Headphones :size="18" />登录遇到问题？请联系系统维护人员</div>
      </form>
    </main>

    <div v-if="systemHealthy" class="system-health"><span />系统运行正常</div>
  </div>
</template>

<style scoped>
.login-screen {
  display: flex;
  min-width: 1180px;
  min-height: 100vh;
  align-items: stretch;
  flex-direction: column;
  padding: 0;
  background: var(--ds-bg);
}

.login-brandbar {
  display: flex;
  height: 86px;
  flex: none;
  align-items: center;
  padding: 0 44px;
  border-bottom: 1px solid var(--ds-line);
  background: #fff;
}

.login-logo {
  display: flex;
  align-items: center;
  gap: 10px;
  color: var(--ds-ink);
  font-size: 24px;
  font-weight: 800;
}

.login-logo img {
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: 8px;
  object-fit: contain;
}

.login-workspace {
  display: grid;
  width: min(980px, calc(100% - 96px));
  min-height: 520px;
  flex: 1;
  grid-template-columns: 390px minmax(0, 1fr);
  align-items: center;
  align-self: center;
  gap: 0;
  padding: 54px 0;
}

.login-intro {
  display: flex;
  min-height: 360px;
  align-items: center;
  gap: 20px;
  padding-right: 62px;
  border-right: 1px solid var(--ds-line-strong);
  color: var(--ds-primary);
}

.login-intro h1 {
  margin: 0;
  color: var(--ds-ink);
  font-size: 34px;
  font-weight: 800;
}

.login-intro p {
  margin: 12px 0 0;
  color: var(--ds-muted);
  font-size: 17px;
}

.login-form {
  display: grid;
  gap: 18px;
  padding-left: 74px;
}

.login-field {
  display: grid;
  gap: 8px;
}

.login-field > label {
  color: #515863;
  font-size: 15px;
  font-weight: 700;
}

.input-shell {
  display: flex;
  width: 100%;
  height: 56px;
  align-items: center;
  gap: 11px;
  padding: 0 16px;
  border: 1px solid var(--ds-line-strong);
  border-radius: 6px;
  background: #fff;
  color: #737a85;
}

.input-shell:focus-within {
  border-color: var(--ds-primary);
  box-shadow: 0 0 0 2px rgba(118, 189, 184, .18);
}

.login-field.invalid .input-shell {
  border-color: var(--ds-danger);
  box-shadow: none;
}

.input-shell input {
  width: 100%;
  min-width: 0;
  height: 52px;
  padding: 0;
  border: 0 !important;
  border-radius: 0 !important;
  background: transparent !important;
  box-shadow: none !important;
  font-size: 15px;
}

.input-shell input:focus {
  border: 0 !important;
  box-shadow: none !important;
}

.password-toggle {
  display: inline-flex;
  flex: none;
  align-items: center;
  justify-content: center;
  padding: 4px;
  border: 0;
  background: transparent;
  color: #747b86;
}

.field-error,
.submit-error,
.caps-lock-tip {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ds-danger);
  font-size: 13px;
}

.caps-lock-tip {
  color: var(--ds-warning);
}

.login-options {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.remember-account {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: #4f5661;
  font-size: 14px;
}

.remember-account input {
  width: 18px;
  height: 18px;
  accent-color: var(--ds-primary);
}

.help-link {
  padding: 4px 0;
  border: 0;
  background: transparent;
  color: var(--ds-primary-hover);
  font-size: 14px;
  font-weight: 700;
}

.submit-error,
.login-help {
  padding: 9px 11px;
  border-radius: 6px;
  background: var(--ds-danger-soft);
}

.login-help {
  color: #6d4b24;
  background: var(--ds-warning-soft);
  font-size: 13px;
  line-height: 1.6;
}

.login-submit {
  width: 100%;
  height: 56px;
  border: 1px solid var(--ds-primary-hover);
  border-radius: 6px;
  background: var(--ds-primary-hover);
  color: #fff;
  font-size: 18px;
  font-weight: 800;
}

.login-submit:hover:not(:disabled) {
  border-color: var(--ds-primary-active);
  background: var(--ds-primary-active);
}

.support-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--ds-muted);
  font-size: 13px;
}

.system-health {
  display: flex;
  min-height: 52px;
  flex: none;
  align-items: center;
  justify-content: center;
  gap: 9px;
  color: #606772;
  font-size: 13px;
}

.system-health span {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: var(--ds-success);
}

@media (max-width: 768px) {
  .login-screen {
    height: 100vh;
    height: 100dvh;
    width: 100%;
    min-width: 0;
    max-width: 100%;
    min-height: 100dvh;
    overflow-y: auto;
    overscroll-behavior-y: contain;
  }

  .login-brandbar {
    min-height: 68px;
    height: auto;
    padding-left: max(18px, env(safe-area-inset-left));
    padding-right: max(18px, env(safe-area-inset-right));
  }

  .login-workspace {
    width: 100%;
    min-width: 0;
    min-height: 0;
    flex: 1 0 auto;
    grid-template-columns: minmax(0, 1fr);
    align-self: stretch;
    gap: 30px;
    padding: 36px 18px max(28px, calc(28px + env(safe-area-inset-bottom)));
    padding-left: max(18px, env(safe-area-inset-left));
    padding-right: max(18px, env(safe-area-inset-right));
  }

  .login-intro {
    min-width: 0;
    min-height: 0;
    padding: 0 0 24px;
    border-right: 0;
    border-bottom: 1px solid var(--ds-line-strong);
  }

  .login-intro h1 {
    font-size: 28px;
  }

  .login-intro p {
    font-size: 15px;
  }

  .login-form {
    min-width: 0;
    gap: 16px;
    padding-left: 0;
  }

  .input-shell {
    min-width: 0;
  }

  .password-toggle {
    width: 44px;
    height: 44px;
    padding: 0;
  }

  .login-options {
    flex-wrap: wrap;
    gap: 4px 16px;
  }

  .remember-account,
  .help-link {
    min-height: 44px;
  }

  .help-link {
    display: inline-flex;
    align-items: center;
  }

  .login-submit {
    min-height: 48px;
  }

  .system-health {
    min-height: 44px;
    padding: 0 18px;
    text-align: center;
  }
}
</style>
