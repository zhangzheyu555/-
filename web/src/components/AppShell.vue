<template>
  <div class="app-shell">
    <aside class="sidebar">
      <div class="brand-lockup">
        <div class="brand-mark"></div>
        <div>
          <strong>门店利润系统</strong>
          <span>经营工作台</span>
        </div>
      </div>

      <nav class="nav-groups">
        <section v-for="group in navGroups" :key="group.name" class="nav-group">
          <div class="nav-group-title">{{ group.name }}</div>
          <RouterLink v-for="item in group.items" :key="item.path" class="nav-item" :to="item.path">
            <component :is="item.icon" />
            <span>{{ item.label }}</span>
          </RouterLink>
        </section>
      </nav>
    </aside>

    <main class="workspace">
      <header class="topbar">
        <div>
          <div class="page-title">{{ route.meta.title }}</div>
          <div class="page-subtitle">{{ todayText }} · {{ session.overview?.version || 'local' }}</div>
        </div>
        <div class="topbar-actions">
          <div class="global-search">
            <Search />
            <input v-model="keyword" placeholder="搜索门店、月份、人员" />
          </div>
          <button class="icon-button" title="刷新状态" @click="session.bootstrap()">
            <RefreshCw />
          </button>
          <div class="user-chip">
            <span>{{ session.user?.displayName || '管理员' }}</span>
            <strong>{{ session.user?.roleLabel || '管理员' }}</strong>
          </div>
        </div>
      </header>

      <RouterView />
    </main>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { RouterLink, RouterView, useRoute } from 'vue-router';
import {
  Bot,
  Building2,
  ClipboardCheck,
  Database,
  FilePenLine,
  History,
  LayoutDashboard,
  RefreshCw,
  Search,
  Settings,
  Users
} from 'lucide-vue-next';
import { useSessionStore } from '../stores/session';

const route = useRoute();
const session = useSessionStore();
const keyword = ref('');

const todayText = computed(() => new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  weekday: 'short'
}).format(new Date()));

const navGroups = [
  {
    name: '经营驾驶舱',
    items: [
      { label: '利润概览', path: '/dashboard', icon: LayoutDashboard },
      { label: '数据助手', path: '/assistant', icon: Bot }
    ]
  },
  {
    name: '数据中心',
    items: [
      { label: '利润录入', path: '/profit-entry', icon: FilePenLine },
      { label: '数据导出', path: '/dashboard', icon: Database }
    ]
  },
  {
    name: '门店运营',
    items: [
      { label: '门店档案', path: '/stores', icon: Building2 },
      { label: '督导巡店', path: '/dashboard', icon: ClipboardCheck }
    ]
  },
  {
    name: '系统管理',
    items: [
      { label: '用户权限', path: '/dashboard', icon: Users },
      { label: '操作日志', path: '/dashboard', icon: History },
      { label: '系统配置', path: '/dashboard', icon: Settings }
    ]
  }
];

onMounted(() => {
  session.bootstrap();
});
</script>
