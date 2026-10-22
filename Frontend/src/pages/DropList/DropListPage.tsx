import { useTransition } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { getCategories } from '../../api/category/categories'
import EmptyState from '../../components/EmptyState/EmptyState'
import DropCard from '../../features/drop/components/DropCard'
import { CATALOG_PAGE_SIZE } from '../../features/drop/logic/catalog'
import { resolveListParams } from '../../features/drop/logic/catalogQuery'
import {
  changeCatalogParams,
  readCatalogQuery,
} from '../../features/drop/logic/catalogSearchParams'
import { toCardDrops } from '../../features/drop/logic/dropView'
import { categoryKeys } from '../../features/drop/logic/queryKeys'
import { useDropList } from '../../features/drop/logic/useDrops'
import { useDropListStockStream } from '../../features/drop/logic/useDropListStockStream'
import type { CatalogQuery } from '../../features/drop/logic/catalog'
import type { SharedProps } from '../../types/store'

export default function DropListPage({
  status,
  wishes,
  wishesReady,
  toggleWish,
}: SharedProps & { status: 'WISH' | 'GRAB' }) {
  const [params, setParams] = useSearchParams()
  const [pending, startTransition] = useTransition()
  const updateParams: typeof setParams = (next, options) => {
    startTransition(() => setParams(next, options))
  }

  const categoriesQuery = useQuery({
    queryKey: categoryKeys.all,
    queryFn: getCategories,
    staleTime: Infinity,
  })
  const categoryNames =
    categoriesQuery.data?.flatMap((category) =>
      category.name ? [category.name] : [],
    ) ?? []

  let catalogQuery: CatalogQuery | null = null
  let invalidQuery = false
  try {
    catalogQuery = readCatalogQuery(params, status)
  } catch (error) {
    if (!(error instanceof RangeError)) throw error
    invalidQuery = true
  }
  const listParams = resolveListParams(
    catalogQuery,
    categoriesQuery.data,
    CATALOG_PAGE_SIZE,
  )
  const listQuery = useDropList(listParams ?? null)
  const retryList = useDropListStockStream(listParams ?? null)

  const category = params.get('category') || '전체'
  const keyword = params.get('keyword') ?? ''
  const invalid = invalidQuery || listParams === null
  const cards = listQuery.data ? toCardDrops(listQuery.data.content ?? []) : []
  const totalElements = listQuery.data?.totalElements ?? 0
  const page = listQuery.data?.page ?? 0
  const totalPages = listQuery.data?.totalPages ?? 0
  const hasNext = listQuery.data?.hasNext ?? false
  const loading =
    pending ||
    categoriesQuery.isPending ||
    listParams === undefined ||
    (listParams !== null && listQuery.isPending)

  const onWish = (id: number) => {
    void toggleWish(id)
  }

  const retry = () => {
    if (categoriesQuery.isError) void categoriesQuery.refetch()
    retryList()
  }

  return (
    <>
      <section className="catalog-title">
        <div>
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
          {['전체', ...categoryNames].map((item) => (
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

      {listQuery.isError && listQuery.data && !categoriesQuery.isError && (
        <p className="form-error catalog-refresh-error" role="alert">
          최신 상품 목록을 불러오지 못했어요. 잠시 후 다시 시도해주세요.{' '}
          <button
            type="button"
            className="secondary-button"
            onClick={retry}
            disabled={listQuery.isFetching}
          >
            다시 시도
          </button>
        </p>
      )}

      <div className="section-head catalog-heading">
        <h2>
          {status} 상품
          {listQuery.data && <small> {totalElements}개</small>}
        </h2>
        <span>{CATALOG_PAGE_SIZE}개/페이지</span>
      </div>

      {invalid ? (
        <EmptyState
          title="조회 조건이 올바르지 않아요."
          description="검색어·카테고리·정렬·품절·페이지 조건을 확인해주세요."
          link={status === 'WISH' ? '/wish' : '/grab'}
          label="조회 조건 초기화"
          variant="block"
        />
      ) : categoriesQuery.isError || (listQuery.isError && !listQuery.data) ? (
        <div className="catalog-error" role="alert">
          <EmptyState
            title="상품 목록을 표시하지 못했어요."
            description="잠시 후 다시 시도해주세요."
            variant="block"
          />
          <button type="button" className="secondary-button" onClick={retry}>
            다시 시도
          </button>
        </div>
      ) : loading ? (
        <div role="status" aria-live="polite">
          <EmptyState title="상품 목록을 불러오는 중이에요." variant="block" />
        </div>
      ) : cards.length ? (
        <section
          className="product-grid catalog-grid"
          aria-label={`${status} 상품 목록`}
        >
          {cards.map((drop) => (
            <DropCard
              key={drop.id}
              drop={drop}
              mode={status}
              wished={wishes.has(drop.id)}
              wishReady={wishesReady}
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

      {listQuery.data && !invalid && totalPages > 0 && (
        <nav className="catalog-pagination" aria-label="상품 목록 페이지">
          {page > 0 ? (
            <Link
              className="secondary-button"
              to={{
                search: `?${changeCatalogParams(
                  params,
                  'page',
                  String(Math.min(page - 1, totalPages - 1)),
                )}`,
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
            {page < totalPages
              ? `${page + 1} / ${totalPages} 페이지`
              : `전체 ${totalPages}페이지 · 범위 밖`}
          </span>
          {hasNext ? (
            <Link
              className="secondary-button"
              to={{
                search: `?${changeCatalogParams(params, 'page', String(page + 1))}`,
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
