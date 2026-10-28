import { useCallback, useEffect, useRef } from 'react'
import axios from 'axios'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { getDropDetail, getDropStocks } from '../../../api/drop/drops'
import { openDropStockStream } from '../../../api/drop/stockStream'
import { serverTimeAt } from './serverTime'
import { dropKeys } from './queryKeys'
import {
  realtimeStockPolicy as policy,
  refreshDelay,
} from './realtimeStockPolicy'

type RefreshKey = 'detail' | 'stocks'
type Detail = Awaited<ReturnType<typeof getDropDetail>>

export function subscribeDropStockStream(
  dropId: number,
  queryClient: QueryClient,
) {
  const keys = {
    detail: dropKeys.detail(dropId),
    stocks: dropKeys.stocks(dropId),
  }
  const requests = { detail: getDropDetail, stocks: getDropStocks }
  const lanes = {
    detail: {
      dirty: false,
      urgent: false,
      jittered: false,
      dirtySince: 0,
      running: false,
      generation: 0,
      lastStart: -Infinity,
      timer: 0,
    },
    stocks: {
      dirty: false,
      urgent: false,
      jittered: false,
      dirtySince: 0,
      running: false,
      generation: 0,
      lastStart: -Infinity,
      timer: 0,
    },
  }
  let disposed = false
  let source: EventSource | null = null
  let ready = false
  let awaitingReadySync = false
  let invalidated = false
  let gone = false
  let fallback = true
  let failures = 0
  let connectionStartedAt = 0
  let readyTimer = 0
  let reconnectTimer = 0
  let periodicTimer = 0
  let saleTimer = 0

  const active = () =>
    !gone &&
    (typeof document === 'undefined' ||
      document.visibilityState !== 'hidden') &&
    (typeof navigator === 'undefined' || navigator.onLine !== false)

  const schedule = (key: RefreshKey) => {
    const lane = lanes[key]
    if (
      disposed ||
      !active() ||
      awaitingReadySync ||
      lane.running ||
      lane.timer ||
      !lane.dirty
    )
      return
    lane.timer = globalThis.setTimeout(() => {
      lane.timer = 0
      if (disposed || !active() || !lane.dirty) return
      lane.dirty = false
      lane.urgent = false
      lane.running = true
      lane.lastStart = Date.now()
      const startedGeneration = lane.generation
      void queryClient
        .fetchQuery({
          queryKey: keys[key],
          queryFn: ({ signal }) => requests[key](dropId, signal),
        })
        .then(
          () => {
            lane.running = false
            schedule(key)
          },
          () => {
            lane.running = false
            lane.dirty = true
            if (lane.generation > startedGeneration) schedule(key)
          },
        )
    }, refreshDelay(lane))
    lane.jittered = !lane.urgent
  }

  // 알림으로 생긴 재조회(urgent = false)만 분산 대기를 둔다. 연결 직후 동기화·주기 재동기화·화면 복귀·수동 갱신은
  // 바로 조회하고, 알림으로 분산 대기 중인 예약이 있으면 당긴다. 이미 예약됐거나 조회 중이면 dirty로 합친다.
  const request = (key: RefreshKey, urgent = true) => {
    const lane = lanes[key]
    lane.generation++
    if (!lane.dirty) lane.dirtySince = Date.now()
    lane.dirty = true
    if (urgent) {
      lane.urgent = true
      if (lane.timer && lane.jittered) {
        globalThis.clearTimeout(lane.timer)
        lane.timer = 0
      }
    }
    schedule(key)
  }
  const requestBoth = (urgent = true) => {
    request('detail', urgent)
    request('stocks', urgent)
  }

  const stopTimers = () => {
    globalThis.clearTimeout(readyTimer)
    globalThis.clearTimeout(reconnectTimer)
    globalThis.clearTimeout(periodicTimer)
    globalThis.clearTimeout(saleTimer)
    readyTimer = reconnectTimer = periodicTimer = saleTimer = 0
    for (const key of ['detail', 'stocks'] as const) {
      globalThis.clearTimeout(lanes[key].timer)
      lanes[key].timer = 0
    }
  }

  const closeConnection = () => {
    if (!source) return
    const wasReady = ready
    source.close()
    source = null
    ready = false
    awaitingReadySync = false
    globalThis.clearTimeout(readyTimer)
    readyTimer = 0
    if (wasReady)
      for (const key of ['detail', 'stocks'] as const)
        void queryClient.cancelQueries({ queryKey: keys[key], exact: true })
  }

  const schedulePeriodic = () => {
    if (disposed || !active() || periodicTimer) return
    const base = fallback ? policy.fallbackIntervalMs : policy.resyncIntervalMs
    const jitter = fallback ? policy.fallbackJitterMs : policy.resyncJitterMs
    periodicTimer = globalThis.setTimeout(
      () => {
        periodicTimer = 0
        requestBoth()
        schedulePeriodic()
      },
      base + Math.random() * jitter,
    )
  }

  const setFallbackMode = (next: boolean) => {
    if (fallback !== next) {
      fallback = next
      globalThis.clearTimeout(periodicTimer)
      periodicTimer = 0
    }
    schedulePeriodic()
  }

  const queueReconnect = (delayMs: number) => {
    if (disposed || !active() || source || reconnectTimer || invalidated) return
    reconnectTimer = globalThis.setTimeout(() => {
      reconnectTimer = 0
      connect()
    }, delayMs)
  }

  const normalReconnectDelay = () =>
    Math.max(
      0,
      connectionStartedAt + policy.reconnectMinIntervalMs - Date.now(),
    ) +
    Math.random() * policy.reconnectMinIntervalMs

  const fail = () => {
    closeConnection()
    setFallbackMode(true)
    failures++
    const base = Math.min(
      policy.reconnectMaxDelayMs,
      policy.reconnectMinIntervalMs * 2 ** (failures - 1),
    )
    queueReconnect(base * (0.5 + Math.random() * 0.5))
  }

  const scheduleSale = (
    detail: Detail | undefined,
    receivedAt: number | undefined,
  ) => {
    globalThis.clearTimeout(saleTimer)
    saleTimer = 0
    if (!detail || disposed || !active()) return
    const responseTime = Date.parse(detail.serverTime ?? '')
    const serverNow = serverTimeAt(detail.serverTime, receivedAt)
    if (!Number.isFinite(serverNow)) return
    const next = [detail.saleStartsAt, detail.saleEndsAt]
      .map((date) => Date.parse(date ?? ''))
      .filter((date) => Number.isFinite(date) && date > responseTime)
      .sort((a, b) => a - b)[0]
    if (next === undefined) return
    if (next <= serverNow) {
      requestBoth()
      return
    }
    saleTimer = globalThis.setTimeout(
      () => {
        saleTimer = 0
        requestBoth()
      },
      Math.min(next - serverNow, 2_147_483_647),
    )
  }

  const connect = () => {
    if (disposed || !active() || source || invalidated) return
    const current = openDropStockStream(dropId)
    source = current
    ready = false
    awaitingReadySync = false
    connectionStartedAt = Date.now()
    readyTimer = globalThis.setTimeout(() => {
      if (source === current && !ready) fail()
    }, policy.readyTimeoutMs)

    current.addEventListener('ready', (event) => {
      if (disposed || source !== current || ready) return
      try {
        const payload: unknown = JSON.parse((event as MessageEvent).data)
        if (
          !payload ||
          typeof payload !== 'object' ||
          !('dropIds' in payload) ||
          !Array.isArray(payload.dropIds) ||
          payload.dropIds.length !== 1 ||
          payload.dropIds[0] !== dropId ||
          !('serverTime' in payload) ||
          typeof payload.serverTime !== 'string'
        ) {
          fail()
          return
        }
      } catch {
        fail()
        return
      }
      ready = true
      globalThis.clearTimeout(readyTimer)
      readyTimer = 0
      failures = 0
      setFallbackMode(false)
      awaitingReadySync = true
      void Promise.all(
        (['detail', 'stocks'] as const).map((key) =>
          queryClient.cancelQueries({ queryKey: keys[key], exact: true }),
        ),
      ).then(() => {
        if (disposed || source !== current) return
        awaitingReadySync = false
        requestBoth()
      })
    })

    current.addEventListener('drop-changed', (event) => {
      if (disposed || source !== current) return
      if (!ready) {
        fail()
        return
      }
      try {
        const payload: unknown = JSON.parse((event as MessageEvent).data)
        if (
          !payload ||
          typeof payload !== 'object' ||
          !('dropId' in payload) ||
          payload.dropId !== dropId ||
          !('changes' in payload) ||
          !Array.isArray(payload.changes) ||
          payload.changes.length === 0 ||
          !payload.changes.every((change) => typeof change === 'string')
        ) {
          fail()
          return
        }
        if (
          payload.changes.includes('STOCK') ||
          payload.changes.includes('STATUS')
        ) {
          requestBoth(false)
        } else if (payload.changes.includes('SALES')) {
          request('stocks', false)
        }
      } catch {
        fail()
      }
    })

    current.addEventListener('stream-closed', (event) => {
      if (disposed || source !== current) return
      try {
        const payload: unknown = JSON.parse((event as MessageEvent).data)
        if (
          !payload ||
          typeof payload !== 'object' ||
          !('reason' in payload) ||
          !('reconnect' in payload)
        ) {
          fail()
          return
        }
        const reconnectRequired =
          payload.reason === 'RECONNECT_REQUIRED' && payload.reconnect === true
        const subscriptionInvalid =
          payload.reason === 'SUBSCRIPTION_INVALIDATED' &&
          payload.reconnect === false
        if (!reconnectRequired && !subscriptionInvalid) {
          fail()
          return
        }
        closeConnection()
        setFallbackMode(true)
        if (subscriptionInvalid) {
          invalidated = true
          requestBoth()
        } else {
          queueReconnect(normalReconnectDelay())
        }
      } catch {
        fail()
      }
    })

    current.onerror = () => {
      if (source === current) fail()
    }
  }

  const pause = () => {
    closeConnection()
    stopTimers()
  }
  const resume = () => {
    if (disposed || !active()) return
    requestBoth()
    schedulePeriodic()
    queueReconnect(normalReconnectDelay())
    scheduleSale(
      queryClient.getQueryData<Detail>(keys.detail),
      queryClient.getQueryState(keys.detail)?.dataUpdatedAt,
    )
  }
  const onVisibility = () => {
    if (document.visibilityState === 'hidden') pause()
    else resume()
  }
  const onOffline = () => pause()
  const onOnline = () => resume()

  const unsubscribe = queryClient.getQueryCache().subscribe((event) => {
    if (
      event.type !== 'updated' ||
      event.query.queryKey.length !== keys.detail.length ||
      event.query.queryKey.some(
        (value: unknown, index: number) => value !== keys.detail[index],
      )
    )
      return
    if (
      event.action.type === 'error' &&
      axios.isAxiosError(event.action.error) &&
      event.action.error.response?.status === 404
    ) {
      gone = true
      pause()
      return
    }
    if (event.action.type !== 'success') return
    scheduleSale(
      event.query.state.data as Detail,
      event.query.state.dataUpdatedAt,
    )
    if (invalidated && active()) {
      invalidated = false
      queueReconnect(normalReconnectDelay())
    }
  })

  if (typeof document !== 'undefined')
    document.addEventListener('visibilitychange', onVisibility)
  if (typeof window !== 'undefined') {
    window.addEventListener('offline', onOffline)
    window.addEventListener('online', onOnline)
  }
  scheduleSale(
    queryClient.getQueryData<Detail>(keys.detail),
    queryClient.getQueryState(keys.detail)?.dataUpdatedAt,
  )
  schedulePeriodic()
  connect()

  const dispose = () => {
    disposed = true
    pause()
    unsubscribe()
    if (typeof document !== 'undefined')
      document.removeEventListener('visibilitychange', onVisibility)
    if (typeof window !== 'undefined') {
      window.removeEventListener('offline', onOffline)
      window.removeEventListener('online', onOnline)
    }
  }

  return { dispose, refresh: () => requestBoth() }
}

export function useDropStockStream(dropId: number) {
  const queryClient = useQueryClient()
  const refreshRef = useRef<(() => void) | null>(null)

  useEffect(() => {
    if (!Number.isSafeInteger(dropId) || dropId <= 0) return
    const subscription = subscribeDropStockStream(dropId, queryClient)
    refreshRef.current = subscription.refresh
    return () => {
      refreshRef.current = null
      subscription.dispose()
    }
  }, [dropId, queryClient])

  return useCallback(() => {
    refreshRef.current?.()
  }, [])
}
