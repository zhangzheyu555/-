import { apiGet } from './http'

export interface HealthResponse {
  status: string
  service: string
  time: string
}

export function getHealth() {
  return apiGet<HealthResponse>('/api/health')
}
