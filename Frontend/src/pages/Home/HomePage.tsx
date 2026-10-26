import { Link } from 'react-router-dom'
import heroBackground from '../../assets/images/hero-background.png'
import EmptyState from '../../components/EmptyState/EmptyState'
import PopularRail from '../../features/drop/components/PopularRail'
import {
  homeSections,
  type HomeSectionConfig,
} from '../../features/drop/logic/homeSections'
import useHomeDrops from '../../features/drop/logic/useHomeDrops'
import type { SharedProps } from '../../types/store'

function HomeSection({
  section,
  wishes,
  wishesReady,
  onWish,
}: {
  section: HomeSectionConfig
  wishes: ReadonlySet<number>
  wishesReady: boolean
  onWish: (id: number) => void
}) {
  const { state, retry } = useHomeDrops(section.status)

  return (
    <section className="home-section">
      <div className="catalog-heading">
        <div>
          <h2>{section.title}</h2>
        </div>
        <div className="home-section-actions">
          <Link className="text-link" to={section.to}>
            전체 보기 ↗
          </Link>
        </div>
      </div>

      {state.status === 'loading' ? (
        <div role="status" aria-live="polite">
          <EmptyState
            loading
            title="인기 상품을 불러오는 중이에요."
            variant="block"
          />
        </div>
      ) : state.status === 'error' ? (
        <div className="catalog-error" role="alert">
          <EmptyState
            title="인기 상품을 표시하지 못했어요."
            description="잠시 후 다시 시도해주세요."
            variant="block"
          />
          <button type="button" className="secondary-button" onClick={retry}>
            다시 시도
          </button>
        </div>
      ) : state.drops.length === 0 ? (
        <EmptyState
          title="아직 준비된 상품이 없어요."
          link={section.to}
          label="전체 보기"
          variant="block"
        />
      ) : (
        <PopularRail
          drops={state.drops}
          mode={section.status}
          wishes={wishes}
          wishReady={wishesReady}
          onWish={onWish}
          label={section.title}
        />
      )}
    </section>
  )
}

export default function HomePage({
  wishes,
  wishesReady,
  toggleWish,
}: SharedProps) {
  const onWish = (id: number) => {
    void toggleWish(id)
  }

  return (
    <>
      <section className="home-intro">
        <img
          className="home-brand-image"
          src={heroBackground}
          alt="GRAB — Grab what you want. 판매자의 DROP이 당신의 GRAB으로."
        />
      </section>

      {homeSections.map((section) => (
        <HomeSection
          key={section.status}
          section={section}
          wishes={wishes}
          wishesReady={wishesReady}
          onWish={onWish}
        />
      ))}
    </>
  )
}
