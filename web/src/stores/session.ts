import { defineStore } from 'pinia';
import {
  authToken,
  clearAuthToken,
  fetchCurrentSession,
  fetchSystemOverview,
  login,
  logout,
  type SessionUser,
  type SystemOverview
} from '../services/api';

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
        const overview = await fetchSystemOverview();
        const user = authToken() ? await fetchCurrentSession() : null;
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
        this.user = null;
      } finally {
        this.loading = false;
      }
    },
    async login(username: string, password: string) {
      this.loading = true;
      this.error = '';
      try {
        this.user = await login(username, password);
      } catch (error) {
        clearAuthToken();
        this.user = null;
        this.error = '账号或密码不正确';
        throw error;
      } finally {
        this.loading = false;
      }
    },
    async logout() {
      await logout();
      this.user = null;
    }
  }
});
