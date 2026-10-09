import { useState, useTransition } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
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
  const [pending, startTransition] = useTransition()
  const [, setRetry] = useState(0)
  const updateParams: typeof setParams = (next, options) => {
    startTransition(() => setParams(next, options))
  }
  const category = params.get('category') || '전체'
  const keyword = params.get('keyword') ?? ''
  let result
  let invalidQuery = false
  try {
    result = queryCatalog(catalogDrops, readCatalogQuery(params, status))
  } catch (error) {
    invalidQuery = error instanceof RangeError
  }

  const onWish = (id: number) => {
    const selected = wishes.has(id)
    if (!toggleWish(id)) return
    notify(selected ? 'WISH를 취소했어요.' : 'WISH에 담았어요.')
  }

  return (
    <>
      <section className="catalog-title">
        <div>
          <p className="eyebrow">
            {status === 'WISH'
              ? 'BE READY FOR THE DROP'
              : 'LIMITED TIME. LIMITED QUANTITY.'}
          </p>
          <h1>
            {status === 'WISH' ? '다가올 DROP을 WISH' : '지금 GRAB 할 상품'}
          </h1>
          <p>
            {status === 'WISH'
              ? '판매 시작을 기다리는 상품을 살펴보세요.'
              : '작은 브랜드의 한정 판매를 만나보세요.'}
          </p>
        </div>
        {status === 'WISH' && (
          <Link className="text-link" to="/my">
            나의 WISH ↗
          </Link>
        )}
      </section>

      <section
        className="catalog-controls catalog-filters panel"
        aria-label="상품 필터"
      >
        <div className="chips">
          {categories.map((item) => (
            <button
              key={item}
              type="button"
              className={category === item ? 'active' : ''}
              aria-pressed={category === item}
              onClick={() =>
                updateParams(
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
        <div className="catalog-query-controls">
          <label className="search-box">
            <span className="sr-only">상품 검색</span>
            <input
              value={keyword}
              onChange={(event) =>
                updateParams(
                  changeCatalogParams(params, 'keyword', event.target.value),
                  { replace: true },
                )
              }
              maxLength={100}
              placeholder="상품명 검색"
            />
            <span aria-hidden="true">⌕</span>
          </label>
          <div className="catalog-sort">
            <label htmlFor="catalog-sort">정렬</label>
            <select
              id="catalog-sort"
              value={params.get('sort') ?? 'publishedAt,desc'}
              onChange={(event) =>
                updateParams(
                  changeCatalogParams(params, 'sort', event.target.value),
                )
              }
            >
              <option value="publishedAt,desc">최신 공개순</option>
              <option value="publishedAt,asc">오래된 공개순</option>
              <option value="saleStartsAt,asc">판매 시작 빠른순</option>
              <option value="saleStartsAt,desc">판매 시작 늦은순</option>
              <option value="createdAt,desc">최신 등록순</option>
              <option value="createdAt,asc">오래된 등록순</option>
              <option
                value={
                  status === 'WISH' ? 'wishCount,desc' : 'soldQuantity,desc'
                }
              >
                {status === 'WISH' ? '인기 WISH순' : '인기 GRAB순'}
              </option>
            </select>
          </div>
          {status === 'GRAB' && (
            <label className="catalog-sold-out">
              <input
                type="checkbox"
                checked={params.get('soldOut') === 'false'}
                onChange={(event) =>
                  updateParams(
                    changeCatalogParams(
                      params,
                      'soldOut',
                      event.target.checked ? 'false' : '',
                    ),
                  )
                }
              />
              품절 제외
            </label>
          )}
        </div>
      </section>

      <div className="section-head catalog-heading">
        <h2>
          {status} 상품{result && <small> {result.totalElements}개</small>}
        </h2>
        <span>예시 상품 · 24개/페이지</span>
      </div>

      {pending ? (
        <div role="status" aria-live="polite">
          <EmptyState title="상품 목록을 불러오는 중이에요." variant="block" />
        </div>
      ) : !result && invalidQuery ? (
        <EmptyState
          title="조회 조건이 올바르지 않아요."
          description="검색어·정렬·품절·페이지 조건을 확인해주세요."
          link={status === 'WISH' ? '/wish' : '/grab'}
          label="조회 조건 초기화"
          variant="block"
        />
      ) : !result ? (
        <div className="catalog-error" role="alert">
          <EmptyState
            title="상품 목록을 표시하지 못했어요."
            description="잠시 후 다시 시도해주세요."
            variant="block"
          />
          <button
            type="button"
            className="secondary-button"
            onClick={() => setRetry((retry) => retry + 1)}
          >
            다시 시도
          </button>
        </div>
      ) : result.content.length ? (
        <section
          className="product-grid catalog-grid"
          aria-label={`${status} 상품 목록`}
        >
          {result.content.map((drop) => (
            <DropCard
              key={drop.id}
              drop={drop}
              mode={status}
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

      {result && result.totalPages > 0 && (
        <nav className="catalog-pagination" aria-label="상품 목록 페이지">
          {result.page > 0 ? (
            <Link
              className="secondary-button"
              to={{
                search: `?${changeCatalogParams(params, 'page', String(Math.min(result.page - 1, result.totalPages - 1)))}`,
              }}
            >
              이전
            </Link>
          ) : (
            <button type="button" className="secondary-button" disabled>
              이전
            </button>
          )}
          <span aria-live="polite">
            {result.page < result.totalPages
              ? `${result.page + 1} / ${result.totalPages} 페이지`
              : `전체 ${result.totalPages}페이지 · 범위 밖`}
          </span>
          {result.hasNext ? (
            <Link
              className="secondary-button"
              to={{
                search: `?${changeCatalogParams(params, 'page', String(result.page + 1))}`,
              }}
            >
              다음
            </Link>
          ) : (
            <button type="button" className="secondary-button" disabled>
              다음
            </button>
          )}
        </nav>
      )}
    </>
  )
}
