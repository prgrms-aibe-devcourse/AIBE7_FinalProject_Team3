import { useCallback, useEffect, useState } from 'react'
import type { Drop } from '../../types/drop'
import { fetchHomeDrops, type HomeSectionKey } from './mock/mockHome'

export type HomeDropsState =
  | { status: 'loading' }
  | { status: 'error' }
  | { status: 'ready'; drops: Drop[] }

// 영역별로 독립적인 로딩·실패·재시도 상태를 관리한다.
export default function useHomeDrops(section: HomeSectionKey) {
  const [attempt, setAttempt] = useState(0)
  const [result, setResult] = useState<{
    attempt: number
    drops?: Drop[]
    failed?: boolean
  }>({ attempt: -1 })

  useEffect(() => {
    let active = true
    fetchHomeDrops(section)
      .then((drops) => {
        if (active) setResult({ attempt, drops })
      })
      .catch(() => {
        if (active) setResult({ attempt, failed: true })
      })
    return () => {
      active = false
    }
  }, [section, attempt])

  const state: HomeDropsState =
    result.attempt === attempt
      ? result.failed
        ? { status: 'error' }
        : { status: 'ready', drops: result.drops ?? [] }
      : { status: 'loading' }

  const retry = useCallback(() => setAttempt((value) => value + 1), [])

  return { state, retry }
}
