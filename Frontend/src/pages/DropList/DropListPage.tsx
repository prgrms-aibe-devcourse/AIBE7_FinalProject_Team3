import { Link, useSearchParams } from 'react-router-dom'
import logo from '../../assets/images/grab-symbol.png'
import heroImage from '../../assets/images/hero-background.png'
import EmptyState from '../../components/EmptyState/EmptyState'
import DropCard from '../../features/drop/DropCard'
import { queryCatalog } from '../../features/drop/catalog'
import {
  changeCatalogParams,
  readCatalogQuery,
} from '../../features/drop/catalogSearchParams'
import { catalogDrops } from '../../features/drop/mock/mockCatalog'
import { categories } from '../../features/drop/mock/mockDrops'
import type { SharedProps } from '../../types/store'

export default function DropListPage({
  status,
  wishes,
  toggleWish,
  notify,
}: SharedProps & { status: 'WISH' | 'GRAB' }) {
  const [params, setParams] = useSearchParams()
  const category = params.get('category') || '전체'
  const keyword = params.get('keyword') ?? ''
  let result
  try {
    result = queryCatalog(catalogDrops, readCatalogQuery(params, status))
  } catch (error) {
    if (!(error instanceof RangeError)) throw error
  }

  const onWish = (id: number) => {
    const selected = wishes.has(id)
    if (!toggleWish(id)) return
    notify(selected ? 'WISH를 취소했어요.' : 'WISH에 담았어요.')
  }

  return (
    <>
      {status === 'WISH' ? (
        <>
          <section className="hero">
            <img src={heroImage} alt="GRAB, Grab what you want" />
            <span>SELLER DROPS. CONSUMER GRABS.</span>
          </section>
          <section className="intro">
            <div>
              <p className="eyebrow">BE READY FOR THE DROP</p>
              <h1>
                발견한 취향, <em>놓치지 않도록.</em>
              </h1>
              <p>마음에 드는 DROP을 WISH하고 판매 시작을 기다려보세요.</p>
            </div>
            <Link className="text-link" to="/my">
              나의 WISH ↗
            </Link>
          </section>
        </>
      ) : (
        <section className="grab-hero">
          <div>
            <p className="eyebrow">LIMITED TIME. LIMITED QUANTITY.</p>
            <h1>원하던 순간, 지금 GRAB.</h1>
            <p>작은 브랜드가 준비한 특별한 상품을 한정 수량으로 만나보세요.</p>
          </div>
          <img src={logo} alt="" />
        </section>
      )}

      <section className="catalog-controls" aria-label="상품 필터">
        <div className="chips">
          {categories.map((item) => (
            <button
              key={item}
              type="button"
              className={category === item ? 'active' : ''}
              onClick={() =>
                setParams(
                  changeCatalogParams(
                    params,
                    'category',
                    item === '전체' ? '' : item,
                  ),
                )
              }
            >
              {item}
            </button>
          ))}
        </div>
        <label className="search-box">
          <span className="sr-only">상품 검색</span>
          <input
            value={keyword}
            onChange={(event) =>
              setParams(
                changeCatalogParams(params, 'keyword', event.target.value),
                { replace: true },
              )
            }
            maxLength={100}
            placeholder="상품명 검색"
          />
          <span aria-hidden="true">⌕</span>
        </label>
      </section>

      {!result ? (
        <EmptyState
          title="조회 조건이 올바르지 않아요."
          description="검색어·정렬·품절·페이지 조건을 확인해주세요."
          link={status === 'WISH' ? '/wish' : '/grab'}
          label="조회 조건 초기화"
          variant="block"
        />
      ) : result.content.length ? (
        <section className="product-grid" aria-label={`${status} 상품 목록`}>
          {result.content.map((drop) => (
            <DropCard
              key={drop.id}
              drop={drop}
              wished={wishes.has(drop.id)}
              onWish={onWish}
            />
          ))}
        </section>
      ) : (
        <EmptyState
          title="조건에 맞는 상품이 없어요."
          description="검색어나 카테고리를 바꿔보세요."
          variant="block"
        />
      )}

      <section className="seller-banner">
        <img src={logo} alt="" />
        <div>
          <strong>하나의 상품, 하나의 DROP.</strong>
          <p>관심을 모으고 한정판매로 연결하세요.</p>
        </div>
        <Link className="secondary-button" to="/seller">
          셀러로 시작하기 ↗
        </Link>
      </section>
    </>
  )
}
