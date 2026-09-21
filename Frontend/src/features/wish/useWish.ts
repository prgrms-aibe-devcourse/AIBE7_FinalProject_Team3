import { useState } from 'react'

export default function useWish(authenticated: boolean, notify: (message: string) => void) {
  const [wishes, setWishes] = useState(() => new Set([101]))

  const toggleWish = (id: number) => {
    if (!authenticated) {
      notify('로그인 후 WISH를 이용할 수 있어요.')
      return false
    }
    setWishes((current) => {
      const next = new Set(current)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
    return true
  }

  return { wishes, toggleWish }
}
