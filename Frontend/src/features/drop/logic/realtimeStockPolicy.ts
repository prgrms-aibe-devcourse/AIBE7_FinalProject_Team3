export const realtimeStockPolicy = {
  refreshMinIntervalMs: 2000,
  // 변경 알림으로 생긴 재조회는 max(최소 간격의 남은 시간, 0~이 값 무작위)만큼 기다린다.
  // 알림이 모든 화면에 거의 동시에 도착해 재조회가 한순간에 몰리는 것을 막는다(REALTIME_STOCK.md 12절, GR-209).
  signalRefreshJitterMs: 1000,
  readyTimeoutMs: 10000,
  resyncIntervalMs: 30000,
  resyncJitterMs: 5000,
  fallbackIntervalMs: 10000,
  fallbackJitterMs: 2000,
  reconnectMinIntervalMs: 5000,
  reconnectMaxDelayMs: 60000,
} as const

// 재조회 예약 대기. 최소 간격의 남은 시간을 기다리고, 변경 알림으로만 생긴 재조회(urgent = false)는
// 0~signalRefreshJitterMs 무작위 대기와 비교해 긴 쪽을 기다린다. 무작위 대기는 처음 dirty가 된 시각부터
// 최소 간격 안으로 제한해 기존 "첫 dirty 이후 최대 대기" 계약을 넘지 않는다(조회가 길어 이미 지났으면 바로 조회).
export function refreshDelay(
  lane: { urgent: boolean; lastStart: number; dirtySince: number },
  now = Date.now(),
) {
  const interval = Math.max(
    0,
    lane.lastStart + realtimeStockPolicy.refreshMinIntervalMs - now,
  )
  if (lane.urgent) return interval
  const budget = Math.max(
    0,
    lane.dirtySince + realtimeStockPolicy.refreshMinIntervalMs - now,
  )
  const jitter = Math.random() * realtimeStockPolicy.signalRefreshJitterMs
  return Math.max(interval, Math.min(jitter, budget))
}
