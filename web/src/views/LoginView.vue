<template>
  <main class="login-screen">
    <section class="login-panel">
      <div class="login-brand">
        <div class="brand-mark"></div>
        <div>
          <strong>门店利润系统</strong>
          <span>Java 后端工作台</span>
        </div>
      </div>

      <form class="login-form" @submit.prevent="submit">
        <div>
          <h1>登录系统</h1>
          <p>使用后端账号进入利润、门店、权限和数据助手模块。</p>
        </div>

        <label>
          <span>账号</span>
          <div class="field-box">
            <UserRound />
            <input v-model.trim="username" autocomplete="username" placeholder="请输入账号" />
          </div>
        </label>

        <label>
          <span>密码</span>
          <div class="field-box">
            <KeyRound />
            <input v-model="password" type="password" autocomplete="current-password" placeholder="请输入密码" />
          </div>
        </label>

        <div v-if="errorMessage" class="login-error">
          <AlertTriangle />
          <span>{{ errorMessage }}</span>
        </div>

        <button class="login-button" :disabled="session.loading">
          <LogIn />
          {{ session.loading ? '正在登录' : '登录' }}
        </button>
      </form>
    </section>

    <aside class="login-aside">
      <div class="login-aside-head">
        <ShieldCheck />
        <span>当前阶段</span>
      </div>
      <h2>正在把系统从纯前端升级为前后端分离架构</h2>
      <div class="login-checks">
        <span>Spring Boot API</span>
        <span>MySQL 数据库</span>
        <span>Token 会话</span>
        <span>门店权限范围</span>
      </div>
    </aside>
  </main>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { AlertTriangle, KeyRound, LogIn, ShieldCheck, UserRound } from 'lucide-vue-next';
import { useSessionStore } from '../stores/session';

const route = useRoute();
const router = useRouter();
const session = useSessionStore();
const username = ref('admin');
const password = ref('');
const errorMessage = ref('');

async function submit() {
  errorMessage.value = '';
  if (!username.value || !password.value) {
    errorMessage.value = '请输入账号和密码';
    return;
  }
  try {
    await session.login(username.value, password.value);
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard';
    router.replace(redirect);
  } catch {
    errorMessage.value = session.error || '登录失败，请检查账号和密码';
  }
}
</script>
