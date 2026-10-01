import { useState } from 'react'
import { Link } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import { endedDrops } from '../../features/drop/mockEndedDrops'
import { drops } from '../../features/drop/mockDrops'
import { stock, upcoming } from '../../features/drop/selectors'
import type { DropStatus } from '../../types/drop'
import { sellerOrders } from '../../features/order/mockSellerOrders'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

// 상단은 "지금 손대야 하는 것"만 둔다. 읽고 끝나는 합계는 아래 DROP 목록이 상품별로 보여준다.
// 발송 대기: GET /seller/dashboard/summary (GR-41) 의 orderCounts.PAID
// 임박: GET /seller/dashboard/upcoming-drops (GR-38) 를 eventType=START·END 두 번 불러 합친 건수.
// 시작인지 종료인지는 카드가 아니라 아래 DROP 행의 태그에서 구분한다.
const loadSummary = () => ({
  pendingShipment: sellerOrders.filter((o) => o.orderStatus === 'PAID').length,
  upcomingCount: drops.filter((drop) => upcoming(drop)).length,
})

// 전체·임박은 상태가 아니라 보는 방식이라 같은 필터 하나로 묶는다.
type DropFilter = 'ALL' | 'UPCOMING' | DropStatus

const TABS: { key: DropFilter; label: string }[] = [
  { key: 'ALL', label: '전체' },
  { key: 'DRAFT', label: 'DRAFT' },
  { key: 'WISH', label: 'WISH' },
  { key: 'GRAB', label: 'GRAB' },
  { key: 'ENDED', label: 'ENDED' },
  { key: 'CANCELED', label: 'CANCELED' },
]

export default function SellerDashboardPage({
  notify,
}: {
  notify: (message: string) => void
}) {
  // 대시보드는 스냅샷이다. 재고와 임박 시각은 계속 움직이므로 기준 시각을 밝히고 수동으로 다시 읽는다.
  const [refreshedAt, setRefreshedAt] = useState(() => new Date())
  const [items, setItems] = useState(drops)
  const [filter, setFilter] = useState<DropFilter>('ALL')
  const summary = loadSummary()

  const onlyUpcoming = filter === 'UPCOMING'
  const countOf = (key: DropFilter) =>
    key === 'ALL' ? items.length : items.filter((d) => d.status === key).length
  const matched =
    filter === 'ALL'
      ? items
      : onlyUpcoming
        ? items.filter((drop) => upcoming(drop))
        : items.filter((drop) => drop.status === filter)
  // 전체 목록은 GR-57 연동 때 페이지네이션으로 가고, 여기서는 앞쪽만 보여준다.
  const visible = matched.slice(0, 5)

  // POST /seller/drops/{dropId}/publish (GR-14) · /cancel (GR-18)
  const setStatus = (id: number, status: DropStatus, message: string) => {
    setItems((current) =>
      current.map((drop) => (drop.id === id ? { ...drop, status } : drop)),
    )
    notify(message)
  }

  const totalSales = endedDrops.reduce((sum, drop) => sum + drop.salesAmount, 0)
  // 막대 폭은 최댓값 대비 비율이다. 차트 라이브러리를 붙일 정도의 화면이 아니다.
  const maxSales = Math.max(1, ...endedDrops.map((drop) => drop.salesAmount))

  return (
    <section className="page-section seller-page">
      <div className="seller-title">
        <div>
          <p className="eyebrow">CREATOR WORKSPACE</p>
          <h1>다음 DROP을 준비하는 곳.</h1>
          <p>지금 손대야 할 것부터 확인하세요.</p>
        </div>
        <Link className="primary-button" to="/seller/drops/new">
          ＋ 새 DROP 만들기
        </Link>
      </div>
      <div className="stats-caption">
        <small>
          기준{' '}
          {refreshedAt.toLocaleTimeString('ko-KR', {
            hour: '2-digit',
            minute: '2-digit',
          })}
        </small>
        <button type="button" onClick={() => setRefreshedAt(new Date())}>
          ↻ 새로고침
        </button>
      </div>
      <div className="stats-grid">
        <Link to="/seller/orders">
          <span>발송 대기</span>
          <strong>
            {summary.pendingShipment}
            <em>건</em>
          </strong>
          <small>결제 완료 · 송장 미등록</small>
        </Link>
        <button
          type="button"
          aria-pressed={onlyUpcoming}
          disabled={summary.upcomingCount === 0}
          onClick={() =>
            setFilter((current) => (current === 'UPCOMING' ? 'ALL' : 'UPCOMING'))
          }
        >
          <span>임박 DROP</span>
          <strong>
            {summary.upcomingCount}
            <em>개</em>
          </strong>
          <small>24시간 내 시작 또는 종료</small>
        </button>
      </div>
      <section className="panel drop-table-panel">
        <div className="panel-heading">
          <div>
            <h2>DROP 관리</h2>
            <p>
              {onlyUpcoming
                ? `임박 ${matched.length}개`
                : `총 ${items.length}개 · 상태와 옵션별 재고를 확인합니다.`}
            </p>
          </div>
          <Link className="text-link" to="/seller/drops/new">
            새 상품 ↗
          </Link>
        </div>
        <div className="chips drop-tabs">
          {TABS.map((tab) => (
            <button
              key={tab.key}
              type="button"
              className={filter === tab.key ? 'active' : undefined}
              onClick={() => setFilter(tab.key)}
            >
              {tab.label} {countOf(tab.key)}
            </button>
          ))}
        </div>
        <div className="drop-list">
          {visible.length === 0 && (
            <p className="drop-list-empty">해당 상태의 DROP이 없어요.</p>
          )}
          {visible.map((drop) => {
            const soon = upcoming(drop)
            return (
              <article key={drop.id} className={soon ? 'urgent' : undefined}>
                <img src={drop.image} alt="" />
                <div className="drop-name">
                  <strong>{drop.name}</strong>
                  <small>
                    {drop.optionGroups.map((group) => group.name).join(' · ') ||
                      '기본 옵션'}
                  </small>
                  {soon && (
                    <span className="urgent-tag">
                      {soon.label} · {soon.remain}
                    </span>
                  )}
                </div>
                <span className={`status-pill ${drop.status.toLowerCase()}`}>
                  {drop.status}
                </span>
                <div>
                  <small>WISH</small>
                  <b>{drop.wishCount}</b>
                </div>
                <div>
                  <small>재고</small>
                  <b>{stock(drop)}</b>
                </div>
                <div className="drop-actions">
                  {drop.status === 'DRAFT' && (
                    <button
                      className="secondary-button"
                      type="button"
                      onClick={() =>
                        setStatus(drop.id, 'WISH', `${drop.name} 을(를) 공개했어요.`)
                      }
                    >
                      공개
                    </button>
                  )}
                  {drop.status === 'WISH' && (
                    <button
                      className="secondary-button"
                      type="button"
                      onClick={() =>
                        setStatus(drop.id, 'CANCELED', `${drop.name} 출시를 취소했어요.`)
                      }
                    >
                      출시 취소
                    </button>
                  )}
                </div>
                <Link
                  to={`/seller/drops/${drop.id}`}
                  aria-label={`${drop.name} 관리`}
                >
                  ↗
                </Link>
              </article>
            )
          })}
          {matched.length > visible.length && (
            <p className="drop-list-more">
              {matched.length}개 중 {visible.length}개를 보고 있어요.
            </p>
          )}
        </div>
      </section>
      <section className="panel sales-panel">
        <div className="panel-heading">
          <div>
            <h2>판매 완료</h2>
            <p>
              {endedDrops.length
                ? `${endedDrops.length}개 · 누적 매출 ${won(totalSales)}`
                : '종료된 DROP의 매출을 정리합니다.'}
            </p>
          </div>
        </div>
        {endedDrops.length ? (
          <div className="sales-list">
            {endedDrops.map((drop) => (
              <article key={drop.dropId}>
                <div className="sales-name">
                  <strong>{drop.name}</strong>
                  <small>{dateLabel(drop.saleEndsAt)} 종료</small>
                </div>
                <div
                  className="sales-bar"
                  role="presentation"
                  title={`${drop.orderCount}건 · ${drop.soldStock}개 판매`}
                >
                  <span style={{ width: `${(drop.salesAmount / maxSales) * 100}%` }} />
                </div>
                <div className="sales-amount">
                  <b>{won(drop.salesAmount)}</b>
                  <small>
                    주문 {drop.orderCount}건 · {drop.soldStock}개
                  </small>
                </div>
              </article>
            ))}
          </div>
        ) : (
          <EmptyState
            title="아직 판매가 끝난 DROP이 없어요."
            link="/seller/drops/new"
            label="새 DROP 만들기"
          />
        )}
      </section>
    </section>
  )
}
