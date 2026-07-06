import { createRouter, createWebHistory } from 'vue-router';
import DashboardView from './views/DashboardView.vue';
import ProfitEntryView from './views/ProfitEntryView.vue';
import StoreDirectoryView from './views/StoreDirectoryView.vue';
import AssistantView from './views/AssistantView.vue';

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/dashboard' },
    { path: '/dashboard', component: DashboardView, meta: { title: '经营驾驶舱' } },
    { path: '/profit-entry', component: ProfitEntryView, meta: { title: '利润录入' } },
    { path: '/stores', component: StoreDirectoryView, meta: { title: '门店档案' } },
    { path: '/assistant', component: AssistantView, meta: { title: '数据助手' } }
  ]
});

export default router;
