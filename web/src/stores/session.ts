import { defineStore } from 'pinia';
import { fetchCurrentSession, fetchSystemOverview, type SessionUser, type SystemOverview } from '../services/api';

interface SessionState {
  loading: boolean;
  user: SessionUser | null;
  overview: SystemOverview | null;
  error: string;
}

export const useSessionStore = defineStore('session', {
  state: (): SessionState => ({
    loading: false,
    user: null,
    overview: null,
    error: ''
  }),
  actions: {
    async bootstrap() {
      this.loading = true;
      this.error = '';
      try {
        const [overview, user] = await Promise.all([fetchSystemOverview(), fetchCurrentSession()]);
        this.overview = overview;
        this.user = user;
      } catch (error) {
        this.error = error instanceof Error ? error.message : '后端连接失败';
        this.overview = {
          appName: '门店利润系统',
          version: 'local',
          activeProfile: 'offline',
          modules: ['经营驾驶舱', '数据中心', '门店运营', '系统管理']
        };
        this.user = {
          id: 0,
          displayName: '管理员',
          role: 'ADMIN',
          roleLabel: '管理员',
          storeScope: ['all']
        };
      } finally {
        this.loading = false;
      }
    }
  }
});
