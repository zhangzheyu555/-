import axios from 'axios';

export const api = axios.create({
  baseURL: '/api',
  timeout: 6000
});

const TOKEN_KEY = 'store-profit-token';

api.interceptors.request.use((config) => {
  const token = localStorage.getItem(TOKEN_KEY);
  if (token) {
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error?.response?.status === 401) {
      clearAuthToken();
    }
    return Promise.reject(error);
  }
);

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

export interface LoginResponse {
  token: string;
  user: SessionUser;
}

export interface BrandRecord {
  id: number;
  code: string;
  name: string;
  color: string;
  sortOrder: number;
}

export interface StoreRecord {
  id: string;
  code: string;
  name: string;
  brandId: number;
  brandName: string;
  area: string;
  manager: string;
  openDate: string;
  status: string;
  note: string;
}

export interface StoreUpsertPayload {
  id: string;
  code: string;
  name: string;
  brandId: number;
  area: string;
  manager: string;
  openDate: string;
  status: string;
  note: string;
}

export function authToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function setAuthToken(token: string) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearAuthToken() {
  localStorage.removeItem(TOKEN_KEY);
}

export async function login(username: string, password: string) {
  const { data } = await api.post<ApiResponse<LoginResponse>>('/auth/login', { username, password });
  setAuthToken(data.data.token);
  return data.data.user;
}

export async function logout() {
  try {
    await api.post<ApiResponse<void>>('/auth/logout');
  } finally {
    clearAuthToken();
  }
}

export async function fetchSystemOverview() {
  const { data } = await api.get<ApiResponse<SystemOverview>>('/system/overview');
  return data.data;
}

export async function fetchCurrentSession() {
  const { data } = await api.get<ApiResponse<SessionUser>>('/session/me');
  return data.data;
}

export async function fetchStores() {
  const { data } = await api.get<ApiResponse<StoreRecord[]>>('/stores');
  return data.data;
}

export async function fetchBrands() {
  const { data } = await api.get<ApiResponse<BrandRecord[]>>('/brands');
  return data.data;
}

export async function saveStore(payload: StoreUpsertPayload, mode: 'create' | 'update') {
  const request = mode === 'create'
    ? api.post<ApiResponse<void>>('/stores', payload)
    : api.put<ApiResponse<void>>('/stores', payload);
  await request;
}
