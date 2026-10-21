import { useCallback, useEffect, useRef } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { getDrops } from '../../../api/drop/drops'
import {
  normalizeDropIds,
  openDropStockStream,
} from '../../../api/drop/stockStream'
import { dropKeys, type DropListParams } from './queryKeys'
import { realtimeStockPolicy as policy } from './realtimeStockPolicy'

type ListResponse = Awaited<ReturnType<typeof getDrops>>
type ConnectionHistory = { startedAt: number | null }

const supportedChanges = new Set(['STOCK', 'SALES', 'STATUS'])

const sameIds = (left: readonly number[], right: readonly number[]) =>
  left.length === right.length && left.every((id, index) => id === right[index])

const sameKey = (left: readonly unknown[], right: readonly unknown[]) =>
  left.length === right.length &&
  left.every((value, index) => value === right[index])

function listDropIds(data: ListResponse | undefined) {
  if (!data) return []
  const ids = new Set<number>()
  for (const drop of data.content ?? []) {
    if (
      typeof drop.dropId === 'number' &&
      Number.isSafeInteger(drop.dropId) &&
      drop.dropId > 0
    )
      ids.add(drop.dropId)
  }
  return [...ids].sort((a, b) => a - b)
}

export function subscribeDropListStockStream(
  params: DropListParams,
  queryClient: QueryClient,
  connectionHistory: ConnectionHistory = { startedAt: null },
) {
  const key = dropKeys.list(params)
  const lane = {
    dirty: false,
    running: false,
    generation: 0,
    lastStart: -Infinity,
    timer: 0,
  }
  let disposed = false
  let source: EventSource | null = null
  let sourceIds: number[] = []
  let targetIds: number[] = []
  let ready = false
  let awaitingReadySync = false
  let invalidated = false
  let fallback = true
  let failures = 0
  let readyTimer = 0
  let reconnectTimer = 0
  let periodicTimer = 0

  const active = () =>
    (typeof document === 'undefined' ||
      document.visibilityState !== 'hidden') &&
    (typeof navigator === 'undefined' || navigator.onLine !== false)

  const schedule = () => {
    if (
      disposed ||
      !active() ||
      awaitingReadySync ||
      lane.running ||
      lane.timer ||
      !lane.dirty
    )
      return
    lane.timer = globalThis.setTimeout(
      () => {
        lane.timer = 0
        if (disposed || !active() || !lane.dirty) return
        lane.dirty = false
        lane.running = true
        lane.lastStart = Date.now()
        const startedGeneration = lane.generation
        void queryClient
          .fetchQuery({
            queryKey: key,
            queryFn: ({ signal }) => getDrops(params, signal),
          })
          .then(
            () => {
              lane.running = false
              schedule()
            },
            () => {
              lane.running = false
              lane.dirty = true
              if (lane.generation > startedGeneration) schedule()
            },
          )
      },
      Math.max(0, lane.lastStart + policy.refreshMinIntervalMs - Date.now()),
    )
  }

  const request = () => {
    lane.generation++
    lane.dirty = true
    schedule()
  }

  const stopTimers = () => {
    globalThis.clearTimeout(readyTimer)
    globalThis.clearTimeout(reconnectTimer)
    globalThis.clearTimeout(periodicTimer)
    readyTimer = reconnectTimer = periodicTimer = 0
    globalThis.clearTimeout(lane.timer)
    lane.timer = 0
  }

  const closeConnection = () => {
    if (!source) return
    const wasReady = ready
    source.close()
    source = null
    sourceIds = []
    ready = false
    awaitingReadySync = false
    globalThis.clearTimeout(readyTimer)
    readyTimer = 0
    if (wasReady) void queryClient.cancelQueries({ queryKey: key, exact: true })
  }

  const schedulePeriodic = () => {
    if (disposed || !active() || periodicTimer) return
    const base = fallback ? policy.fallbackIntervalMs : policy.resyncIntervalMs
    const jitter = fallback ? policy.fallbackJitterMs : policy.resyncJitterMs
    periodicTimer = globalThis.setTimeout(
      () => {
        periodicTimer = 0
        request()
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

  const nextConnectionDelay = () =>
    connectionHistory.startedAt === null
      ? 0
      : Math.max(
          0,
          connectionHistory.startedAt +
            policy.reconnectMinIntervalMs -
            Date.now(),
        ) +
        Math.random() * policy.reconnectMinIntervalMs

  const queueReconnect = (delayMs: number) => {
    if (
      disposed ||
      !active() ||
      source ||
      reconnectTimer ||
      invalidated ||
      targetIds.length === 0
    )
      return
    reconnectTimer = globalThis.setTimeout(() => {
      reconnectTimer = 0
      connect()
    }, delayMs)
  }

  const syncIds = (next: number[]) => {
    targetIds = next
    if (next.length === 0) {
      closeConnection()
      return
    }
    if (source && sameIds(sourceIds, next)) return
    if (source) closeConnection()
    queueReconnect(nextConnectionDelay())
  }

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

  const connect = () => {
    if (disposed || !active() || source || invalidated) return
    const ids = targetIds
    if (ids.length === 0) return
    const current = openDropStockStream(ids)
    source = current
    sourceIds = ids
    ready = false
    awaitingReadySync = false
    connectionHistory.startedAt = Date.now()
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
          !('serverTime' in payload) ||
          typeof payload.serverTime !== 'string'
        ) {
          fail()
          return
        }
        const readyIds = normalizeDropIds(payload.dropIds)
        if (!sameIds(readyIds, sourceIds)) {
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
      void queryClient
        .cancelQueries({ queryKey: key, exact: true })
        .then(() => {
          if (disposed || source !== current) return
          awaitingReadySync = false
          request()
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
          typeof payload.dropId !== 'number' ||
          !sourceIds.includes(payload.dropId) ||
          !('changes' in payload) ||
          !Array.isArray(payload.changes) ||
          payload.changes.length === 0 ||
          !payload.changes.every((change) => typeof change === 'string')
        ) {
          fail()
          return
        }
        if (payload.changes.some((change) => supportedChanges.has(change)))
          request()
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
          request()
        } else {
          queueReconnect(nextConnectionDelay())
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
    request()
    schedulePeriodic()
    syncIds(listDropIds(queryClient.getQueryData<ListResponse>(key)))
  }

  const onVisibility = () => {
    if (document.visibilityState === 'hidden') pause()
    else resume()
  }
  const onOffline = () => pause()
  const onOnline = () => resume()

  const unsubscribe = queryClient.getQueryCache().subscribe((event) => {
    if (
      disposed ||
      event.type !== 'updated' ||
      !sameKey(event.query.queryKey, key) ||
      event.action.type !== 'success'
    )
      return
    invalidated = false
    syncIds(listDropIds(event.query.state.data as ListResponse | undefined))
  })

  if (typeof document !== 'undefined')
    document.addEventListener('visibilitychange', onVisibility)
  if (typeof window !== 'undefined') {
    window.addEventListener('offline', onOffline)
    window.addEventListener('online', onOnline)
  }
  targetIds = listDropIds(queryClient.getQueryData<ListResponse>(key))
  syncIds(targetIds)
  schedulePeriodic()

  const dispose = () => {
    disposed = true
    pause()
    unsubscribe()
    void queryClient.cancelQueries({ queryKey: key, exact: true })
    if (typeof document !== 'undefined')
      document.removeEventListener('visibilitychange', onVisibility)
    if (typeof window !== 'undefined') {
      window.removeEventListener('offline', onOffline)
      window.removeEventListener('online', onOnline)
    }
  }

  return { dispose, refresh: request }
}

export function useDropListStockStream(params: DropListParams | null) {
  const queryClient = useQueryClient()
  const refreshRef = useRef<(() => void) | null>(null)
  const historyRef = useRef<ConnectionHistory>({ startedAt: null })
  const paramsRef = useRef(params)
  const keySignature = params ? JSON.stringify(dropKeys.list(params)) : ''

  useEffect(() => {
    paramsRef.current = params
  }, [params])

  useEffect(() => {
    const current = paramsRef.current
    if (!current) return
    const subscription = subscribeDropListStockStream(
      current,
      queryClient,
      historyRef.current,
    )
    refreshRef.current = subscription.refresh
    return () => {
      refreshRef.current = null
      subscription.dispose()
    }
  }, [keySignature, queryClient])

  return useCallback(() => {
    refreshRef.current?.()
  }, [])
}
