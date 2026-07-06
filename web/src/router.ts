import { createRouter, createWebHistory } from 'vue-router';
import DashboardView from './views/DashboardView.vue';
import ProfitEntryView from './views/ProfitEntryView.vue';
import StoreDirectoryView from './views/StoreDirectoryView.vue';
import AssistantView from './views/AssistantView.vue';
import LoginView from './views/LoginView.vue';
import ExportView from './views/ExportView.vue';
import LogsView from './views/LogsView.vue';
import UserPermissionsView from './views/UserPermissionsView.vue';
import { authToken } from './services/api';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/login', component: LoginView, meta: { title: '登录', public: true } },
    { path: '/dashboard', component: DashboardView, meta: { title: '经营驾驶舱' } },
    { path: '/profit-entry', component: ProfitEntryView, meta: { title: '利润录入' } },
    { path: '/export', component: ExportView, meta: { title: '数据导出' } },
    { path: '/stores', component: StoreDirectoryView, meta: { title: '门店档案' } },
    { path: '/assistant', component: AssistantView, meta: { title: '数据助手' } },
    { path: '/users', component: UserPermissionsView, meta: { title: '用户权限' } },
    { path: '/logs', component: LogsView, meta: { title: '操作日志' } }
  ]
});

router.beforeEach((to) => {
  if (!to.meta.public && !authToken()) {
    return { path: '/login', query: { redirect: to.fullPath } };
  }
  if (to.meta.public && authToken()) {
    return '/dashboard';
  }
  return true;
});

export default router;
