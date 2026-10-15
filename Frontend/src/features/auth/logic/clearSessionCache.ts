import type { QueryClient } from '@tanstack/react-query'
import { authKeys } from './queryKeys'

export async function clearSessionCache(queryClient: QueryClient) {
  await queryClient.cancelQueries()
  // 구독 중인 me 쿼리는 유지해야 로그인 상태 변경이 화면에 전달된다.
  queryClient.setQueryData(authKeys.me(), null)
  const meQuery = queryClient.getQueryCache().find({
    queryKey: authKeys.me(),
    exact: true,
  })
  queryClient.removeQueries({ predicate: (query) => query !== meQuery })
  queryClient.getMutationCache().clear()
}
