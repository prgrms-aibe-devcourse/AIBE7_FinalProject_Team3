import { Link } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import PopularRail from '../../features/drop/PopularRail'
import { catalogDrops } from '../../features/drop/mock/mockCatalog'
import type { SharedProps } from '../../types/store'

const HOME_LIMIT = 4

const popularBy = (status: 'GRAB' | 'WISH') =>
  catalogDrops
    .filter((drop) => drop.status === status)
    .sort((a, b) =>
      status === 'GRAB'
        ? b.soldQuantity - a.soldQuantity
        : b.wishCount - a.wishCount,
    )
    .slice(0, HOME_LIMIT)

const sections = [
  {
    status: 'GRAB' as const,
    title: '지금 인기 GRAB',
    to: '/grab',
    drops: popularBy('GRAB'),
  },
  {
    status: 'WISH' as const,
    title: '곧 만날 인기 WISH',
    to: '/wish',
    drops: popularBy('WISH'),
  },
]

export default function HomePage({ wishes, toggleWish, notify }: SharedProps) {
  const onWish = (id: number) => {
    const selected = wishes.has(id)
    if (!toggleWish(id)) return
    notify(selected ? 'WISH를 취소했어요.' : 'WISH에 담았어요.')
  }

  return (
    <>
      <section className="intro">
        <div>
          <p className="eyebrow">SELLER DROPS. CONSUMER GRABS.</p>
          <h1>발견한 취향을 놓치지 않도록.</h1>
          <p>한정 판매를 준비하는 브랜드와 지금 만날 수 있는 상품.</p>
        </div>
      </section>

      {sections.map((section) => (
        <section className="home-section" key={section.status}>
          <div className="catalog-heading">
            <h2>{section.title}</h2>
            <div className="home-section-actions">
              <span className="home-note">예시 데이터</span>
              <Link className="text-link" to={section.to}>
                전체 보기 ↗
              </Link>
            </div>
          </div>
          {section.drops.length === 0 ? (
            <EmptyState
              title="아직 준비된 상품이 없어요."
              link={section.to}
              label="전체 보기"
              variant="block"
            />
          ) : (
            <PopularRail
              drops={section.drops}
              mode={section.status}
              wishes={wishes}
              onWish={onWish}
              label={section.title}
            />
          )}
        </section>
      ))}
    </>
  )
}
