import { useEffect } from 'react'
import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import AuthLoading from '../../features/auth/components/AuthLoading'
import {
  isAdmin,
  isSeller,
  type AuthStatus,
  type AuthUser,
} from '../../features/auth/logic/authUser'
import {
  inventoryPageSize,
  readInventoryQuery,
} from '../../features/seller/logic/inventory'
import { sellerDropStatuses } from '../../features/seller/logic/sellerDropQuery'
import { useDropStats } from '../../features/seller/logic/useDashboard'
import { useSellerDropStocks } from '../../features/seller/logic/useSellerDropStocks'
import { applicationErrorMessage } from '../../features/seller/logic/sellerApplicationState'
import { isDropNotAccessible } from '../../features/seller/logic/dropStocksState'

export default function SellerInventoryPage({
  user,
  authStatus,
}: {
  user: AuthUser | null
  authStatus: AuthStatus
}) {
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const selectionNotice = (
    location.state as { inventoryNotice?: string } | null
  )?.inventoryNotice
  const { status, page, dropId } = readInventoryQuery(params)
  const dropIdNum = dropId ? Number(dropId) : null

  const seller = isSeller(user)

  const listQuery = useDropStats(
    {
      status: status === 'ALL' ? undefined : status,
      page,
      size: inventoryPageSize,
    },
    seller,
  )
  const selectedQuery = useDropStats(
    { dropId: dropIdNum ?? undefined },
    seller && dropIdNum !== null,
  )
  const stocksQuery = useSellerDropStocks(seller ? dropIdNum : null)

  const list = listQuery.data?.content ?? []
  const totalPages = Math.max(1, listQuery.data?.totalPages ?? 1)
  const selected = dropIdNum ? selectedQuery.data?.content?.[0] : undefined
  const selectedNotFound =
    seller &&
    dropIdNum !== null &&
    ((selectedQuery.isSuccess && !selected) ||
      (stocksQuery.isError && isDropNotAccessible(stocksQuery.error)))

  useEffect(() => {
    if (!selectedNotFound) return
    setParams(
      { status, page: String(page) },
      {
        replace: true,
        state: {
          inventoryNotice: '선택한 DROP을 찾을 수 없거나 관리 권한이 없어요.',
        },
      },
    )
  }, [selectedNotFound, page, setParams, status])

  if (authStatus === 'loading') return <AuthLoading />
  if (!user)
    return (
      <Navigate
        to="/login"
        state={{ from: location.pathname + location.search }}
        replace
      />
    )
  if (!seller)
    return (
      <EmptyState
        title="판매자 권한이 필요해요."
        link={isAdmin(user) ? '/my' : '/seller/apply'}
        label={isAdmin(user) ? '내 계정으로 돌아가기' : '판매자 신청 보기'}
        variant="page"
      />
    )

  const setQuery = (nextStatus: string, nextPage: number, nextId?: string) => {
    setParams(
      {
        status: nextStatus,
        page: String(nextPage),
        ...(nextId ? { dropId: nextId } : {}),
      },
      { state: null },
    )
  }

  return (
    <section className="page-section seller-page seller-inventory-page">
      <div className="seller-title">
        <div>
          <h1>재고 현황</h1>
          <p>상품과 옵션별 가용 · 확보 · 판매 수량을 확인합니다.</p>
        </div>
      </div>
      <p className="inventory-note">
        가용 수량에는 종료된 상품과 비활성 옵션이 포함될 수 있어 지금 구매
        가능한 수량과 다릅니다.
      </p>

      <div className="inventory-layout">
        <section
          className="panel inventory-list-panel"
          aria-label="상품별 현황"
        >
          <div className="panel-heading">
            <div>
              <h2>상품별 현황</h2>
              <p>현재 페이지의 상품만 표시합니다</p>
            </div>
          </div>
          <div className="inventory-controls">
            <div className="chips" aria-label="재고 상품 상태 필터">
              {sellerDropStatuses.map((item) => (
                <button
                  key={item}
                  type="button"
                  aria-pressed={status === item}
                  className={status === item ? 'active' : undefined}
                  onClick={() => setQuery(item, 0)}
                >
                  {item === 'ALL' ? '전체' : item}
                </button>
              ))}
            </div>
          </div>
          {listQuery.isLoading ? (
            <EmptyState loading title="재고 목록을 불러오는 중이에요." />
          ) : listQuery.isError ? (
            <div className="inventory-result">
              <EmptyState title={applicationErrorMessage(listQuery.error)} />
              <button
                type="button"
                className="secondary-button"
                onClick={() => listQuery.refetch()}
              >
                다시 시도
              </button>
            </div>
          ) : list.length === 0 ? (
            <EmptyState title="조건에 맞는 DROP이 없어요." />
          ) : (
            <>
              <div className="inventory-list">
                {list.map((drop) => (
                  <article key={drop.dropId} className="inventory-item">
                    <button
                      type="button"
                      className="inventory-select"
                      aria-pressed={dropIdNum === drop.dropId}
                      onClick={() =>
                        setQuery(status, page, String(drop.dropId))
                      }
                    >
                      <span className="inventory-item-title">
                        <strong>{drop.name || '이름 없는 DROP'}</strong>
                        <span
                          className={`status-pill ${(drop.status ?? '').toLowerCase()}`}
                        >
                          {drop.status}
                        </span>
                      </span>
                      <span className="inventory-item-counts">
                        <span>가용 {drop.availableStock ?? 0}</span>
                        <span>확보 {drop.reservedStock ?? 0}</span>
                        <span>판매 {drop.soldStock ?? 0}</span>
                      </span>
                    </button>
                    <Link
                      className="text-link"
                      to={`/seller/drops/${drop.dropId}`}
                    >
                      DROP 상세 ↗
                    </Link>
                  </article>
                ))}
              </div>
              <div className="inventory-pagination">
                <span>
                  {page + 1} / {totalPages} 페이지
                </span>
                <div>
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={page === 0}
                    onClick={() =>
                      setQuery(status, page - 1, dropId ?? undefined)
                    }
                  >
                    이전
                  </button>
                  <button
                    type="button"
                    className="secondary-button"
                    disabled={!listQuery.data?.hasNext}
                    onClick={() =>
                      setQuery(status, page + 1, dropId ?? undefined)
                    }
                  >
                    다음
                  </button>
                </div>
              </div>
            </>
          )}
        </section>

        <section
          className="panel inventory-detail-panel"
          aria-label="선택 상품 옵션별 재고"
        >
          <div className="panel-heading">
            <div>
              <h2>선택 상품 옵션별 재고</h2>
              {selected && <p>{selected.name}</p>}
            </div>
          </div>
          {selectionNotice && (
            <p className="inventory-notice" role="alert">
              {selectionNotice}
            </p>
          )}
          {!dropIdNum || selectedNotFound ? (
            <EmptyState title="상품을 선택하면 옵션별 재고가 보여요." />
          ) : stocksQuery.isLoading || selectedQuery.isLoading ? (
            <EmptyState loading title="옵션 재고를 불러오는 중이에요." />
          ) : stocksQuery.isError ? (
            <div className="inventory-result">
              <EmptyState title={applicationErrorMessage(stocksQuery.error)} />
              <button
                type="button"
                className="secondary-button"
                onClick={() => stocksQuery.refetch()}
              >
                다시 시도
              </button>
            </div>
          ) : !stocksQuery.data?.options?.length ? (
            <EmptyState title="등록된 옵션 재고가 없어요." />
          ) : (
            <div className="inventory-options">
              {stocksQuery.data.options.map((option) => (
                <article key={option.optionId} className="inventory-option">
                  <div>
                    <strong>{option.optionName}</strong>
                    <small>
                      {option.active === false
                        ? '비활성 옵션'
                        : (option.availableStock ?? 0) === 0
                          ? '가용 재고 0'
                          : '활성 옵션'}
                    </small>
                  </div>
                  <dl>
                    <div>
                      <dt>가용</dt>
                      <dd>{option.availableStock ?? 0}</dd>
                    </div>
                    <div>
                      <dt>확보</dt>
                      <dd>{option.reservedStock ?? 0}</dd>
                    </div>
                    <div>
                      <dt>판매</dt>
                      <dd>{option.soldStock ?? 0}</dd>
                    </div>
                  </dl>
                </article>
              ))}
            </div>
          )}
        </section>
      </div>
    </section>
  )
}
