const API_BASE = import.meta.env.VITE_API_BASE || 'http://localhost:8080/api'

async function request(path, options = {}) {
  const res = await fetch(`${API_BASE}${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options
  })
  if (!res.ok) {
    const text = await res.text().catch(() => res.statusText)
    throw new Error(`${res.status}: ${text}`)
  }
  const contentType = res.headers.get('content-type') || ''
  return contentType.includes('application/json') ? res.json() : null
}

export const api = {
  catalog: () => request('/devices/catalog'),
  latest: () => request('/sensors/latest'),
  history: (sensorId, minutes = 30) => request(`/sensors/${sensorId}/history?minutes=${minutes}`),
  averages: () => request('/analytics/averages'),
  runAnalytics: () => request('/analytics/run', { method: 'POST' }),
  alerts: (skip = 0, limit = 20, q = '') => {
    let url = `/alerts?skip=${skip}&limit=${limit}`
    if (q) url += `&q=${encodeURIComponent(q)}`
    return request(url)
  },

  generateReading: (roomId, temperature) =>
    request(`/devices/rooms/${roomId}/reading`, {
      method: 'POST',
      body: JSON.stringify(temperature != null ? { temperature } : {})
    }),
  simulateFire: (roomId) => request(`/devices/rooms/${roomId}/simulate-fire`, { method: 'POST' }),
  setLock: (lockId, locked) =>
    request(`/devices/locks/${lockId}`, { method: 'POST', body: JSON.stringify({ locked }) }),
  triggerMotion: (motionId, detected) =>
    request(`/devices/motion/${motionId}`, { method: 'POST', body: JSON.stringify({ detected }) }),
  autoModeState: () => request('/devices/auto-mode/state'),
  startAutoMode: (intervalSeconds) =>
    request('/devices/auto-mode/start', { method: 'POST', body: JSON.stringify({ intervalSeconds }) }),
  stopAutoMode: () => request('/devices/auto-mode/stop', { method: 'POST' }),
  setRoomAutoEnabled: (roomId, enabled) =>
    request(`/devices/rooms/${roomId}/auto-enabled`, { method: 'POST', body: JSON.stringify({ enabled }) }),


  topology: () => request('/system/topology'),
  enableSharding: () => request('/system/sharding/enable', { method: 'POST' }),
  getHomeMode: () => request('/system/mode'),
  setHomeMode: (mode) => request(`/system/mode?mode=${mode}`, { method: 'POST' }),
  resetSystem: () => request('/devices/reset', { method: 'POST' }),
  triggerLockdown: (sensorId) => request('/devices/lockdown', { method: 'POST', body: JSON.stringify({ sensorId }) }),
  setPower: (deviceId, powered) => request(`/devices/${deviceId}/power`, { method: 'POST', body: JSON.stringify({ powered }) }),
  setBattery: (deviceId, battery) => request(`/devices/${deviceId}/battery`, { method: 'POST', body: JSON.stringify({ battery }) }),

  // Phase 8: Rate limiting
  getRateLimits: () => request('/rate-limits'),
  releaseSensor: (sensorId) => request(`/rate-limits/${sensorId}`, { method: 'DELETE' }),
  getRateLimitStats: () => request('/rate-limits/stats'),
}
