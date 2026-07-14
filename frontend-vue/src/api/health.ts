export interface HealthResponse {
  status: string
  service: string
  time: string
}

export async function getHealth() {
  const response = await fetch('/api/health', {
    method: 'GET',
    headers: { Accept: 'application/json' },
    cache: 'no-store',
  })
  if (!response.ok) throw new Error('健康检查不可用')
  const payload = await response.json() as { data?: HealthResponse } & Partial<HealthResponse>
  return (payload.data || payload) as HealthResponse
}
