import { useEffect, useState } from 'react'
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
  inventoryTotals,
  optionLabel,
  readInventoryQuery,
  stockBreakdown,
} from '../../features/seller/logic/inventory'
import {
  sellerDropStatuses,
  type SellerMockDrop,
} from '../../features/seller/mock/mockSellerDrops'

type Result = 'READY' | 'LOADING' | 'ERROR' | 'EMPTY'

export default function SellerInventoryPage({
  user,
  authStatus,
  drops,
  ownerEmail,
}: {
  user: AuthUser | null
  authStatus: AuthStatus
  drops: SellerMockDrop[]
  ownerEmail: string
}) {
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const [listResult, setListResult] = useState<Result>('READY')
  const [stockResult, setStockResult] =
    useState<Exclude<Result, 'EMPTY'>>('READY')
  const selectionNotice = (
    location.state as { inventoryNotice?: string } | null
  )?.inventoryNotice
  const { status, page, dropId } = readInventoryQuery(params)
  const filtered = drops.filter(
    (drop) =>
      drop.ownerEmail === ownerEmail &&
      (status === 'ALL' || drop.status === status),
  )
  const selected = filtered.find((drop) => drop.id === dropId)
  const visible = filtered.slice(
    page * inventoryPageSize,
    (page + 1) * inventoryPageSize,
  )
  const totalPages = Math.max(1, Math.ceil(filtered.length / inventoryPageSize))

  const seller = isSeller(user)

  useEffect(() => {
    if (!seller || !dropId || selected) return
    setParams(
      { status, page: String(page) },
      {
        replace: true,
        state: {
          inventoryNotice: '선택한 DROP을 찾을 수 없거나 관리 권한이 없어요.',
        },
      },
    )
  }, [seller, dropId, page, selected, setParams, status])

  if (authStatus === 'loading') return <AuthLoading />
  if (!user)
    return (
      <Navigate
        to="/login"
        state={{ from: location.pathname + location.search }}
        replace
      />
    )
  if (!isSeller(user))
    return (
      <EmptyState
        title="판매자 권한이 필요해요."
        link={isAdmin(user) ? '/my' : '/seller/apply'}
        label={isAdmin(user) ? '내 계정으로 돌아가기' : '판매자 신청 보기'}
        variant="page"
      />
    )

  const setQuery = (nextStatus: string, nextPage: number, nextId?: string) => {
    setStockResult('READY')
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
          <p className="eyebrow">STOCK VIEW</p>
          <h1>재고 현황</h1>
          <p>상품과 옵션별 가용 · 확보 · 판매 수량을 확인합니다.</p>
        </div>
      </div>
      <p className="inventory-note">
        예시 데이터 · 가용 수량에는 종료된 상품과 비활성 옵션이 포함될 수 있어
        지금 구매 가능한 수량과 다릅니다.
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
            <label className="seller-drops-demo">
              목록 결과 예시
              <select
                value={listResult}
                onChange={(event) =>
                  setListResult(event.target.value as Result)
                }
              >
                <option value="READY">조회 성공</option>
                <option value="LOADING">불러오는 중</option>
                <option value="ERROR">조회 실패</option>
                <option value="EMPTY">빈 결과</option>
              </select>
            </label>
          </div>
          {listResult === 'LOADING' ? (
            <EmptyState title="재고 목록을 불러오는 중이에요." />
          ) : listResult === 'ERROR' ? (
            <div className="inventory-result">
              <EmptyState title="재고 목록을 불러오지 못했어요." />
              <button
                type="button"
                className="secondary-button"
                onClick={() => setListResult('READY')}
              >
                다시 시도
              </button>
            </div>
          ) : listResult === 'EMPTY' || filtered.length === 0 ? (
            <EmptyState title="조건에 맞는 DROP이 없어요." />
          ) : visible.length === 0 ? (
            <div className="inventory-result">
              <EmptyState title="해당 페이지에 DROP이 없어요." />
              <button
                type="button"
                className="secondary-button"
                onClick={() => setQuery(status, 0)}
              >
                첫 페이지
              </button>
            </div>
          ) : (
            <>
              <div className="inventory-list">
                {visible.map((drop) => {
                  const totals = inventoryTotals(drop)
                  return (
                    <article key={drop.id} className="inventory-item">
                      <button
                        type="button"
                        className="inventory-select"
                        aria-pressed={selected?.id === drop.id}
                        onClick={() => setQuery(status, page, drop.id)}
                      >
                        <span className="inventory-item-title">
                          <strong>{drop.name || '이름 없는 DROP'}</strong>
                          <span
                            className={`status-pill ${drop.status.toLowerCase()}`}
                          >
                            {drop.status}
                          </span>
                        </span>
                        <span className="inventory-item-counts">
                          <span>가용 {totals.available}</span>
                          <span>확보 {totals.reserved}</span>
                          <span>판매 {totals.sold}</span>
                        </span>
                      </button>
                      <Link
                        className="text-link"
                        to={`/seller/drops/${drop.id}`}
                      >
                        DROP 상세 ↗
                      </Link>
                    </article>
                  )
                })}
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
                    disabled={page + 1 >= totalPages}
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
          {!selected ? (
            <EmptyState title="상품을 선택하면 옵션별 재고가 보여요." />
          ) : (
            <>
              <label className="seller-drops-demo inventory-detail-demo">
                재고 결과 예시
                <select
                  value={stockResult}
                  onChange={(event) =>
                    setStockResult(event.target.value as typeof stockResult)
                  }
                >
                  <option value="READY">조회 성공</option>
                  <option value="LOADING">불러오는 중</option>
                  <option value="ERROR">조회 실패</option>
                </select>
              </label>
              {stockResult === 'LOADING' ? (
                <EmptyState title="옵션 재고를 불러오는 중이에요." />
              ) : stockResult === 'ERROR' ? (
                <div className="inventory-result">
                  <EmptyState title="옵션 재고를 불러오지 못했어요." />
                  <button
                    type="button"
                    className="secondary-button"
                    onClick={() => setStockResult('READY')}
                  >
                    다시 시도
                  </button>
                </div>
              ) : selected.skus.length === 0 ? (
                <EmptyState title="등록된 옵션 재고가 없어요." />
              ) : (
                <div className="inventory-options">
                  {selected.skus.map((sku) => {
                    const stock = stockBreakdown(sku)
                    return (
                      <article key={sku.id} className="inventory-option">
                        <div>
                          <strong>{optionLabel(selected, sku)}</strong>
                          <small>
                            {sku.active === false
                              ? '비활성 옵션'
                              : stock.available === 0
                                ? '가용 재고 0'
                                : '활성 옵션'}
                          </small>
                        </div>
                        <dl>
                          <div>
                            <dt>가용</dt>
                            <dd>{stock.available}</dd>
                          </div>
                          <div>
                            <dt>확보</dt>
                            <dd>{stock.reserved}</dd>
                          </div>
                          <div>
                            <dt>판매</dt>
                            <dd>{stock.sold}</dd>
                          </div>
                        </dl>
                      </article>
                    )
                  })}
                </div>
              )}
            </>
          )}
        </section>
      </div>
    </section>
  )
}
