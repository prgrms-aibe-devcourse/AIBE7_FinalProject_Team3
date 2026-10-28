import { useEffect, useState } from 'react'

// 입력이 멈춘 뒤에 조회해 타이핑마다 요청하지 않는다
export const DEBOUNCE_MS = 500

export function useDebounced<T>(value: T, delay = DEBOUNCE_MS) {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delay)
    return () => window.clearTimeout(timer)
  }, [value, delay])
  return debounced
}
