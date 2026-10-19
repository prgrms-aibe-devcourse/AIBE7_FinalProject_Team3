export const realtimeStockPolicy = {
  refreshMinIntervalMs: 2000,
  readyTimeoutMs: 10000,
  resyncIntervalMs: 30000,
  resyncJitterMs: 5000,
  fallbackIntervalMs: 10000,
  fallbackJitterMs: 2000,
  reconnectMinIntervalMs: 5000,
  reconnectMaxDelayMs: 60000,
} as const
