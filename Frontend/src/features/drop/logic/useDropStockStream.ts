import { useEffect } from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { getDropDetail, getDropStocks } from '../../../api/drop/drops'
import { openDropStockStream } from '../../../api/drop/stockStream'
import { dropKeys } from './queryKeys'

const REFRESH_MIN_INTERVAL_MS = 2000

type RefreshKey = 'detail' | 'stocks'

export function subscribeDropStockStream(
  dropId: number,
  queryClient: QueryClient,
) {
  const source = openDropStockStream(dropId)
  const keys = {
    detail: dropKeys.detail(dropId),
    stocks: dropKeys.stocks(dropId),
  }
  const requests = {
    detail: getDropDetail,
    stocks: getDropStocks,
  }
  const lanes = {
    detail: { dirty: false, running: false, lastStart: -Infinity, timer: 0 },
    stocks: { dirty: false, running: false, lastStart: -Infinity, timer: 0 },
  }
  let closed = false
  let ready = false
  let prepared = false

  const schedule = (key: RefreshKey) => {
    const lane = lanes[key]
    if (closed || !prepared || lane.running || lane.timer || !lane.dirty) return
    lane.timer = globalThis.setTimeout(
      () => {
        lane.timer = 0
        if (closed || !lane.dirty) return
        lane.dirty = false
        lane.running = true
        lane.lastStart = Date.now()
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
            },
          )
      },
      Math.max(0, lane.lastStart + REFRESH_MIN_INTERVAL_MS - Date.now()),
    )
  }

  const request = (key: RefreshKey) => {
    lanes[key].dirty = true
    schedule(key)
  }

  const close = () => {
    if (closed) return
    closed = true
    source.close()
    for (const key of ['detail', 'stocks'] as const) {
      globalThis.clearTimeout(lanes[key].timer)
    }
  }

  source.addEventListener('ready', (event) => {
    if (closed || ready) return
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
        close()
        return
      }
    } catch {
      close()
      return
    }
    ready = true
    void Promise.all(
      (['detail', 'stocks'] as const).map((key) =>
        queryClient.cancelQueries({ queryKey: keys[key], exact: true }),
      ),
    ).then(() => {
      if (closed) return
      prepared = true
      request('detail')
      request('stocks')
    })
  })

  source.addEventListener('drop-changed', (event) => {
    if (closed || !ready) return
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
        close()
        return
      }
      if (
        payload.changes.includes('STOCK') ||
        payload.changes.includes('STATUS')
      ) {
        request('detail')
        request('stocks')
      } else if (payload.changes.includes('SALES')) {
        request('stocks')
      }
    } catch {
      close()
    }
  })

  // shortcut: 연결 종료 뒤 자동 복구가 없다, GR-111 3단계에서 재연결과 주기 조회를 추가한다.
  source.addEventListener('stream-closed', close)
  source.onerror = close
  return () => {
    close()
    if (ready)
      for (const key of ['detail', 'stocks'] as const)
        void queryClient.cancelQueries({ queryKey: keys[key], exact: true })
  }
}

export function useDropStockStream(dropId: number) {
  const queryClient = useQueryClient()
  useEffect(() => {
    if (!Number.isSafeInteger(dropId) || dropId <= 0) return
    return subscribeDropStockStream(dropId, queryClient)
  }, [dropId, queryClient])
}
