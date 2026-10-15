import { QueryClient, QueryObserver } from '@tanstack/react-query'
import { expect, test } from 'vitest'
import { clearSessionCache } from '../logic/clearSessionCache'
import { authKeys } from '../logic/queryKeys'

test('로그아웃은 인증 구독을 유지하며 사용자 데이터를 지우고 재로그인을 반영한다', async () => {
  const client = new QueryClient()
  const user = { nickname: '드롭헌터' }
  client.setQueryData(authKeys.me(), user)
  client.setQueryData(['wishes'], ['이전 사용자의 WISH'])
  const observer = new QueryObserver<typeof user | null>(client, {
    queryKey: authKeys.me(),
    enabled: false,
  })
  const unsubscribe = observer.subscribe(() => undefined)
  try {
    await clearSessionCache(client)
    expect(observer.getCurrentResult().data).toBeNull()
    expect(client.getQueryData(['wishes'])).toBeUndefined()
    client.setQueryData(authKeys.me(), { nickname: '다음 사용자' })
    expect(observer.getCurrentResult().data).toEqual({
      nickname: '다음 사용자',
    })
  } finally {
    unsubscribe()
    client.clear()
  }
})
