<script setup lang="ts">
import { LogOut, UserRound } from 'lucide-vue-next'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function logout() {
  if (auth.loggingOut) return
  try {
    await auth.logout()
  } finally {
    await router.replace({ name: 'login' })
  }
}
</script>

<template>
  <div class="learner-shell">
    <header class="learner-header">
      <RouterLink class="learner-brand" to="/learn/exams" aria-label="返回我的学习中心">
        <img src="/icons/app-icon-192.png" alt="" width="32" height="32" />
        <span>门店经营 · 学习中心</span>
      </RouterLink>
      <div class="learner-account">
        <span class="learner-account-copy">
          <UserRound :size="18" aria-hidden="true" />
          <span>
            <strong>{{ auth.user?.displayName || '当前学员' }}</strong>
            <small>{{ auth.roleLabel }}</small>
          </span>
        </span>
        <button type="button" :disabled="auth.loggingOut" @click="logout">
          <LogOut :size="17" />{{ auth.loggingOut ? '正在退出' : '退出登录' }}
        </button>
      </div>
    </header>
    <main class="learner-main">
      <RouterView />
    </main>
  </div>
</template>

<style scoped>
.learner-shell {
  min-height: 100vh;
  min-height: 100dvh;
  overflow-y: auto;
  background: var(--ds-bg);
}

.learner-header {
  display: flex;
  min-height: 64px;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  padding: 10px 24px;
  border-bottom: 1px solid var(--ds-line);
  background: #fff;
}

.learner-brand,
.learner-account,
.learner-account-copy,
.learner-account-copy > span,
.learner-account button {
  display: flex;
  align-items: center;
}

.learner-brand {
  gap: 10px;
  color: var(--ds-ink);
  font-size: 17px;
  font-weight: 800;
  text-decoration: none;
}

.learner-account {
  gap: 12px;
}

.learner-account-copy {
  min-width: 0;
  gap: 8px;
  color: var(--ds-secondary);
}

.learner-account-copy > span {
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
}

.learner-account-copy strong,
.learner-account-copy small {
  max-width: 180px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.learner-account-copy strong {
  color: var(--ds-ink);
  font-size: 14px;
}

.learner-account button {
  min-height: 36px;
  gap: 7px;
  padding: 0 13px;
  border: 1px solid var(--ds-line);
  border-radius: 6px;
  background: #fff;
  color: var(--ds-secondary);
  font-weight: 600;
}

.learner-account button:hover:not(:disabled) {
  border-color: #efc4c8;
  background: var(--ds-danger-soft);
  color: var(--ds-danger);
}

.learner-main {
  padding-top: 22px;
}

@media (max-width: 768px) {
  .learner-header {
    align-items: flex-start;
    padding: 12px 16px;
  }

  .learner-brand span {
    font-size: 15px;
  }

  .learner-account-copy {
    display: none;
  }

  .learner-account button {
    min-height: 44px;
  }

  .learner-main {
    padding-top: 16px;
  }
}
</style>
