import { useCallback, useEffect, useRef, useState } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { cancelWish, getMyWishes, registerWish } from '../../../api/wish/wishes'
import { dropKeys } from '../../drop/logic/queryKeys'
import { wishKeys } from './queryKeys'
import { loadMyWishes, wishErrorMessage } from './wishState'

const NO_WISHES: ReadonlySet<number> = new Set<number>()

export default function useWish(
  authenticated: boolean,
  notify: (message: string) => void,
) {
  const queryClient = useQueryClient()
  const pendingRef = useRef(new Set<number>())
  const [pending, setPending] = useState<ReadonlySet<number>>(new Set())

  const query = useQuery({
    queryKey: wishKeys.mine(),
    queryFn: () => loadMyWishes((page, size) => getMyWishes({ page, size })),
    enabled: authenticated,
  })

  // D5: 로그아웃·계정 전환 시 이전 사용자의 WISH 캐시를 남기지 않는다.
  useEffect(() => {
    if (!authenticated) queryClient.removeQueries({ queryKey: wishKeys.all })
  }, [authenticated, queryClient])

  const wishes = query.data ?? NO_WISHES
  const wishesReady = !authenticated || query.isSuccess

  const refresh = useCallback(() => {
    void queryClient.invalidateQueries({ queryKey: wishKeys.all })
    void queryClient.invalidateQueries({ queryKey: dropKeys.all })
  }, [queryClient])

  const toggleWish = useCallback(
    async (id: number) => {
      if (!authenticated) {
        notify('로그인 후 WISH를 이용할 수 있어요.')
        return false
      }
      // 같은 상품의 요청이 진행 중이면 연속 클릭을 막는다.
      if (pendingRef.current.has(id)) return false
      pendingRef.current.add(id)
      setPending(new Set(pendingRef.current))
      const wished = wishes.has(id)
      try {
        if (wished) await cancelWish(id)
        else await registerWish(id)
        notify(wished ? 'WISH를 취소했어요.' : 'WISH에 담았어요.')
        refresh()
        return true
      } catch (error) {
        notify(wishErrorMessage(error))
        refresh()
        return false
      } finally {
        pendingRef.current.delete(id)
        setPending(new Set(pendingRef.current))
      }
    },
    [authenticated, wishes, notify, refresh],
  )

  return {
    wishes: authenticated ? wishes : NO_WISHES,
    wishesReady,
    pendingWishIds: pending,
    toggleWish,
  }
}
