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
  readSalesQuery,
  salesPageSize,
} from '../../features/seller/logic/sales'
import { sellerDropStatuses } from '../../features/seller/logic/sellerDropQuery'
import { useDropStats } from '../../features/seller/logic/useDashboard'
import { applicationErrorMessage } from '../../features/seller/logic/sellerApplicationState'
import { won } from '../../utils/price'

export default function SellerSalesPage({
  user,
  authStatus,
}: {
  user: AuthUser | null
  authStatus: AuthStatus
}) {
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const { status, page, dropId } = readSalesQuery(params)
  const dropIdNum = dropId ? Number(dropId) : undefined
  const seller = isSeller(user)

  const query = useDropStats(
    {
      status: status === 'ALL' ? undefined : status,
      dropId: dropIdNum,
      page,
      size: salesPageSize,
    },
    seller,
  )

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

  const sales = query.data?.content ?? []
  const totalPages = Math.max(1, query.data?.totalPages ?? 1)
  const setQuery = (nextStatus: string, nextDropId: string, nextPage: number) =>
    setParams({
      status: nextStatus,
      ...(nextDropId ? { dropId: nextDropId } : {}),
      page: String(nextPage),
    })

  return (
    <section className="page-section seller-page seller-sales-page">
      <div className="seller-title">
        <div>
          <h1>판매 실적</h1>
          <p>상품별 누적 주문과 판매 결과를 확인합니다.</p>
        </div>
      </div>

      <section
        className="panel seller-sales-panel"
        aria-label="상품별 판매 실적"
      >
        <div className="panel-heading">
          <div>
            <h2>상품별 누적 실적</h2>
            <p>주문 건수와 판매 수량은 서로 다른 지표입니다.</p>
          </div>
        </div>
        <div className="seller-sales-controls">
          <div className="chips" aria-label="판매 실적 상태 필터">
            {sellerDropStatuses.map((item) => (
              <button
                key={item}
                type="button"
                aria-pressed={status === item}
                className={status === item ? 'active' : undefined}
                onClick={() => setQuery(item, dropId ?? '', 0)}
              >
                {item === 'ALL' ? '전체' : item}
              </button>
            ))}
          </div>
        </div>
        {query.isLoading ? (
          <EmptyState title="판매 실적을 불러오는 중이에요." />
        ) : query.isError ? (
          <div className="seller-sales-result">
            <EmptyState title={applicationErrorMessage(query.error)} />
            <button
              type="button"
              className="secondary-button"
              onClick={() => query.refetch()}
            >
              다시 시도
            </button>
          </div>
        ) : dropId && sales.length === 0 ? (
          <div className="seller-sales-result">
            <EmptyState title="선택한 DROP을 찾을 수 없거나 관리 권한이 없어요." />
            <button
              type="button"
              className="secondary-button"
              onClick={() => setQuery(status, '', 0)}
            >
              상품 필터 해제
            </button>
          </div>
        ) : sales.length === 0 ? (
          <EmptyState title="조건에 맞는 판매 실적이 없어요." />
        ) : (
          <>
            <div className="seller-sales-list">
              {sales.map((sale) => (
                <article key={sale.dropId} className="seller-sales-row">
                  <div className="seller-sales-product">
                    <span
                      className={`status-pill ${(sale.status ?? '').toLowerCase()}`}
                    >
                      {sale.status}
                    </span>
                    <Link to={`/seller/drops/${sale.dropId}`}>
                      {sale.name || '이름 없는 DROP'} ↗
                    </Link>
                  </div>
                  <dl className="seller-sales-metrics">
                    <div>
                      <dt>주문</dt>
                      <dd>{sale.orderCount ?? 0}건</dd>
                    </div>
                    <div>
                      <dt>판매</dt>
                      <dd>{sale.soldQuantity ?? 0}개</dd>
                    </div>
                    <div>
                      <dt>누적 매출</dt>
                      <dd>{won(sale.salesAmount ?? 0)}</dd>
                    </div>
                  </dl>
                  <Link
                    className="text-link"
                    to={`/seller/orders?dropId=${sale.dropId}`}
                  >
                    주문 목록 ↗
                  </Link>
                </article>
              ))}
            </div>
            <div className="seller-sales-pagination">
              <span>
                {page + 1} / {totalPages} 페이지
              </span>
              <div>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={page === 0}
                  onClick={() => setQuery(status, dropId ?? '', page - 1)}
                >
                  이전
                </button>
                <button
                  type="button"
                  className="secondary-button"
                  disabled={!query.data?.hasNext}
                  onClick={() => setQuery(status, dropId ?? '', page + 1)}
                >
                  다음
                </button>
              </div>
            </div>
          </>
        )}
      </section>
    </section>
  )
}
