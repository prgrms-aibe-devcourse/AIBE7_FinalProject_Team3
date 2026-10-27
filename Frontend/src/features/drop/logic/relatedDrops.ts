// 상세 상태(WISH 중 / 품절·종료)와 추천 방식(basis)에 맞춰 문구를 고른다(DROP.md 1.4)
export function relatedTitle(
  context: 'wish' | 'closed',
  basis?: 'CO_WISH' | 'SIMILAR' | null,
) {
  if (!basis) return null
  if (context === 'closed') return '비슷한 굿즈가 곧 열려요'
  return basis === 'CO_WISH'
    ? '이 DROP을 WISH한 사람들이 좋아할 만한 DROP'
    : '이런 DROP도 곧 열려요'
}
