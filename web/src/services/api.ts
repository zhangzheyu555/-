import axios from 'axios';

export const api = axios.create({
  baseURL: '/api',
  timeout: 6000
});

export interface ApiResponse<T> {
  success: boolean;
  code: string;
  message: string;
  data: T;
}

export interface SystemOverview {
  appName: string;
  version: string;
  activeProfile: string;
  modules: string[];
}

export interface SessionUser {
  id: number;
  displayName: string;
  role: string;
  roleLabel: string;
  storeScope: string[];
}

export async function fetchSystemOverview() {
  const { data } = await api.get<ApiResponse<SystemOverview>>('/system/overview');
  return data.data;
}

export async function fetchCurrentSession() {
  const { data } = await api.get<ApiResponse<SessionUser>>('/session/me');
  return data.data;
}
