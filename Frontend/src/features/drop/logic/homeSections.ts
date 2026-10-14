export type HomeSectionKey = 'GRAB' | 'WISH'

export type HomeSectionConfig = {
  status: HomeSectionKey
  title: string
  to: string
}

// 두 영역은 각자의 인기 정렬 기준으로 분리된 목록을 사용한다.
export const homeSections: HomeSectionConfig[] = [
  {
    status: 'GRAB',
    title: '지금 인기 GRAB',
    to: '/grab?sort=soldQuantity,desc',
  },
  {
    status: 'WISH',
    title: '곧 만날 인기 WISH',
    to: '/wish?sort=wishCount,desc',
  },
]
