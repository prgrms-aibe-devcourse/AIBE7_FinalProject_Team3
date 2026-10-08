import { useState } from 'react'

const NO_WISHES = new Set<number>()

export default function useWish(
  authenticated: boolean,
  notify: (message: string) => void,
) {
  const [wishes, setWishes] = useState(() => new Set([101]))

  const toggleWish = (id: number) => {
    if (!authenticated) {
      notify('로그인 후 WISH를 이용할 수 있어요.')
      return false
    }
    setWishes((current) => {
      const next = new Set(current)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
    return true
  }

  // 데모 기본 WISH(101)는 로그인한 사용자의 것이다. 비로그인 상태에서는 담긴 WISH가 없다.
  return { wishes: authenticated ? wishes : NO_WISHES, toggleWish }
}
