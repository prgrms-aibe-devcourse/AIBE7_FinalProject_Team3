import axios from 'axios'

// 버튼 상태 판정은 첫 페이지만으로 부족하다. hasNext가 끝날 때까지 size=100으로 순회한다(D3).
export const WISH_PAGE_SIZE = 100

export type WishPage = {
  content?: { dropId?: number }[]
  hasNext?: boolean
}

export async function loadMyWishes(
  fetchPage: (page: number, size: number) => Promise<WishPage>,
): Promise<Set<number>> {
  const ids = new Set<number>()
  for (let page = 0; ; page += 1) {
    const data = await fetchPage(page, WISH_PAGE_SIZE)
    for (const item of data.content ?? []) {
      if (item.dropId !== undefined) ids.add(item.dropId)
    }
    if (!data.hasNext) break
  }
  return ids
}

// 서버 상태·인증 경계에 맞는 안내 문구. 실제 인증 실패를 성공으로 표시하지 않는다(GR-45 경계).
export function wishErrorMessage(error: unknown): string {
  if (axios.isAxiosError(error)) {
    const status = error.response?.status
    const code = (error.response?.data as { message?: string } | undefined)
      ?.message
    if (status === 401 || status === 403)
      return '로그인 후 WISH를 이용할 수 있어요.'
    if (code === 'GRAB_ALREADY_STARTED')
      return '이미 판매가 시작되어 WISH할 수 없어요.'
    if (code === 'DROP_NOT_WISHABLE') return '지금은 WISH할 수 없는 상품이에요.'
    if (code === 'DROP_NOT_FOUND') return '상품을 찾을 수 없어요.'
  }
  return 'WISH 처리에 실패했어요. 잠시 후 다시 시도해주세요.'
}
