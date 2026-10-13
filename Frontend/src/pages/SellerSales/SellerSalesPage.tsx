import { useState } from 'react'
import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import type { MockAccount } from '../../features/auth/mock/mockAuth'
import {
  readSalesQuery,
  salesPageSize,
} from '../../features/seller/logic/sales'
import { mockSellerSales } from '../../features/seller/mock/mockSellerSales'
import {
  sellerDropStatuses,
  type SellerMockDrop,
} from '../../features/seller/mock/mockSellerDrops'
import { won } from '../../utils/price'

type Result = 'READY' | 'LOADING' | 'ERROR' | 'EMPTY'

export default function SellerSalesPage({
  account,
  drops,
  ownerEmail,
}: {
  account: MockAccount | null
  drops: SellerMockDrop[]
  ownerEmail: string
}) {
  const location = useLocation()
  const [params, setParams] = useSearchParams()
  const [result, setResult] = useState<Result>('READY')

  if (!account)
    return (
      <Navigate
        to="/login"
        state={{ from: location.pathname + location.search }}
        replace
      />
    )
  if (account.role !== 'SELLER')
    return (
      <EmptyState
        title="판매자 권한이 필요해요."
        link={account.role === 'MEMBER' ? '/seller/apply' : '/my'}
        label={
          account.role === 'MEMBER'
            ? '판매자 신청 보기'
            : '내 계정으로 돌아가기'
        }
        variant="page"
      />
    )

  const { status, page, dropId } = readSalesQuery(params)
  const owned = drops.filter((drop) => drop.ownerEmail === ownerEmail)
  const selected = owned.find((drop) => drop.id === dropId)
  const sales = mockSellerSales(drops, ownerEmail).filter(
    (sale) =>
      (status === 'ALL' || sale.status === status) &&
      (!dropId || sale.dropId === dropId),
  )
  const visible = sales.slice(page * salesPageSize, (page + 1) * salesPageSize)
  const totalPages = Math.max(1, Math.ceil(sales.length / salesPageSize))
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
          <p className="eyebrow">SALES VIEW</p>
          <h1>판매 실적</h1>
          <p>상품별 누적 주문과 판매 결과를 확인합니다.</p>
        </div>
      </div>
      <p className="seller-sales-note">
        예시 데이터 · 결제 확정·미취소 주문 기준 · 누적 매출은 배송비를
        포함합니다.
      </p>

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
          <label className="seller-drops-demo">
            DROP 선택
            <select
              value={selected?.id ?? ''}
              onChange={(event) => setQuery(status, event.target.value, 0)}
            >
              <option value="">전체 DROP</option>
              {owned.map((drop) => (
                <option key={drop.id} value={drop.id}>
                  {drop.name || '이름 없는 DROP'}
                </option>
              ))}
            </select>
          </label>
          <label className="seller-drops-demo">
            목록 결과 예시
            <select
              value={result}
              onChange={(event) => setResult(event.target.value as Result)}
            >
              <option value="READY">조회 성공</option>
              <option value="LOADING">불러오는 중</option>
              <option value="ERROR">조회 실패</option>
              <option value="EMPTY">빈 결과</option>
            </select>
          </label>
        </div>
        {dropId && !selected ? (
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
        ) : result === 'LOADING' ? (
          <EmptyState title="판매 실적을 불러오는 중이에요." />
        ) : result === 'ERROR' ? (
          <div className="seller-sales-result">
            <EmptyState title="판매 실적을 불러오지 못했어요." />
            <button
              type="button"
              className="secondary-button"
              onClick={() => setResult('READY')}
            >
              다시 시도
            </button>
          </div>
        ) : result === 'EMPTY' || sales.length === 0 ? (
          <EmptyState title="조건에 맞는 판매 실적이 없어요." />
        ) : visible.length === 0 ? (
          <div className="seller-sales-result">
            <EmptyState title="해당 페이지에 DROP이 없어요." />
            <button
              type="button"
              className="secondary-button"
              onClick={() => setQuery(status, dropId ?? '', 0)}
            >
              첫 페이지
            </button>
          </div>
        ) : (
          <>
            <div className="seller-sales-list">
              {visible.map((sale) => (
                <article key={sale.dropId} className="seller-sales-row">
                  <div className="seller-sales-product">
                    <span
                      className={`status-pill ${sale.status.toLowerCase()}`}
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
                      <dd>{sale.orderCount}건</dd>
                    </div>
                    <div>
                      <dt>판매</dt>
                      <dd>{sale.soldQuantity}개</dd>
                    </div>
                    <div>
                      <dt>누적 매출</dt>
                      <dd>{won(sale.salesAmount)}</dd>
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
                  disabled={page + 1 >= totalPages}
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
