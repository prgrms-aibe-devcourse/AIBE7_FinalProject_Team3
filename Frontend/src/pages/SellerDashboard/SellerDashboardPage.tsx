import { useState } from 'react'
import { Link } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import { endedDrops } from '../../features/drop/mock/mockEndedDrops'
import { drops } from '../../features/drop/mock/mockDrops'
import { stock, upcoming } from '../../features/drop/selectors'
import type { DropStatus } from '../../types/drop'
import { sellerOrders } from '../../features/order/mockSellerOrders'
import { dateLabel, dayEnd, dayStart, inputDate } from '../../utils/date'
import { won } from '../../utils/price'

// 상단은 "지금 손대야 하는 것"만 둔다. 읽고 끝나는 합계는 아래 DROP 목록이 상품별로 보여준다.
// 발송 대기: GET /seller/dashboard/summary (GR-41) 의 orderCounts.PAID
// 임박: GET /seller/dashboard/upcoming-drops (GR-38) 를 eventType=START·END 두 번 불러 합친 건수.
// 시작인지 종료인지는 카드가 아니라 아래 DROP 행의 태그에서 구분한다.
// 보정 필요: summary.reconciliationRequired. 승인은 됐는데 주문이 확정되지 않은 결제라 기간과 무관하게 센다.
// 목에는 보정 상태가 없어 결제 UNKNOWN 주문 수로 대신한다.
const loadSummary = () => ({
  pendingShipment: sellerOrders.filter((o) => o.orderStatus === 'PAID').length,
  upcomingCount: drops.filter((drop) => upcoming(drop)).length,
  reconciliationRequired: sellerOrders.filter(
    (o) => o.paymentStatus === 'UNKNOWN',
  ).length,
})

/*
  기간 필터는 orderCounts·paymentCounts에만 적용된다(SELLER.md 2.1).
  dropCounts·stockSummary·reconciliationRequired는 현재 시점 값이라 날짜를 바꿨도 그대로다.
  그래서 날짜 선택기를 상단 전역 필터로 두지 않고 기간 실적 패널 안에 넣어 영향 범위를 보여 준다.
 */
const ORDER_STATUSES = [
  'PAYMENT_PENDING',
  'PAID',
  'PREPARING',
  'SHIPPED',
  'DELIVERED',
  'EXPIRED',
  'CANCELED',
] as const
const PAYMENT_STATUSES = [
  'PENDING',
  'SUCCEEDED',
  'FAILED',
  'UNKNOWN',
  'CANCELED',
] as const

// 전체는 기간을 보내지 않는다는 뜻이다. 서버도 from·to를 생략하면 제한하지 않는다.
const PRESETS = [
  { key: 'ALL', label: '전체', days: null },
  { key: 'TODAY', label: '오늘', days: 0 },
  { key: 'WEEK', label: '7일', days: 6 },
  { key: 'MONTH', label: '30일', days: 29 },
] as const

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
  const [preset, setPreset] = useState<string>('ALL')
  const [range, setRange] = useState({ from: '', to: '' })
  const summary = loadSummary()

  const applyPreset = (key: string, days: number | null) => {
    setPreset(key)
    if (days === null) {
      setRange({ from: '', to: '' })
      return
    }
    const end = new Date()
    const start = new Date()
    start.setDate(end.getDate() - days)
    setRange({ from: inputDate(start), to: inputDate(end) })
  }

  // 날짜를 직접 고치면 프리셋 선택은 해제한다
  const editRange = (next: { from: string; to: string }) => {
    setPreset('CUSTOM')
    setRange(next)
  }

  /*
    연동 시에는 이 블록 전체를 GET /seller/dashboard/summary?from=&to= (GR-41) 응답으로 갈아끼운다.
    쿼리 값은 dayStart(range.from).toISOString() 형태로 보낸다.
    toISOString()은 항상 UTC Z 형식이라 쿼리 스트링에서 +가 공백으로 해석되는 문제가 없다.
    서버는 건수가 없는 상태도 0으로 채워 주므로 키는 항상 전부 온다.
   */
  const inRange = (value: string) => {
    const at = new Date(value).getTime()
    if (range.from && at < dayStart(range.from).getTime()) return false
    if (range.to && at > dayEnd(range.to).getTime()) return false
    return true
  }
  const periodOrders = sellerOrders.filter((order) => inRange(order.orderedAt))
  const orderCounts = ORDER_STATUSES.map(
    (status) =>
      [
        status,
        periodOrders.filter((order) => order.orderStatus === status).length,
      ] as const,
  )
  // 결제는 주문이 아니라 시도 건수라 재시도가 있으면 주문 합계보다 크다. 목은 시도 이력이 없어 1:1로 보인다.
  const paymentCounts = PAYMENT_STATUSES.map(
    (status) =>
      [
        status,
        periodOrders.filter((order) => order.paymentStatus === status).length,
      ] as const,
  )

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
            setFilter((current) =>
              current === 'UPCOMING' ? 'ALL' : 'UPCOMING',
            )
          }
        >
          <span>임박 DROP</span>
          <strong>
            {summary.upcomingCount}
            <em>개</em>
          </strong>
          <small>24시간 내 시작 또는 종료</small>
        </button>
        {summary.reconciliationRequired > 0 && (
          <article className="warning">
            <span>⚠ 확인 필요 결제</span>
            <strong>
              {summary.reconciliationRequired}
              <em>건</em>
            </strong>
            <small>승인됐으나 주문 미확정 · 운영 문의</small>
          </article>
        )}
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
                        setStatus(
                          drop.id,
                          'WISH',
                          `${drop.name} 을(를) 공개했어요.`,
                        )
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
                        setStatus(
                          drop.id,
                          'CANCELED',
                          `${drop.name} 출시를 취소했어요.`,
                        )
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
      <section className="panel period-panel">
        <div className="panel-heading">
          <div>
            <h2>기간 실적</h2>
            <p>
              주문·결제 건수만 기간의 영향을 받아요. 위 현황과 DROP 목록은 현재
              시점 값입니다.
            </p>
          </div>
        </div>
        <div className="period-picker">
          <div className="chips">
            {PRESETS.map((item) => (
              <button
                key={item.key}
                type="button"
                className={preset === item.key ? 'active' : undefined}
                onClick={() => applyPreset(item.key, item.days)}
              >
                {item.label}
              </button>
            ))}
          </div>
          <input
            type="date"
            aria-label="시작일"
            value={range.from}
            max={range.to || undefined}
            onChange={(e) => editRange({ from: e.target.value, to: range.to })}
          />
          <span aria-hidden="true">~</span>
          <input
            type="date"
            aria-label="종료일"
            value={range.to}
            min={range.from || undefined}
            onChange={(e) =>
              editRange({ from: range.from, to: e.target.value })
            }
          />
        </div>
        <p className="count-label">주문 {periodOrders.length}건</p>
        <div className="count-grid">
          {orderCounts.map(([status, count]) => (
            <div key={status}>
              <small>{status}</small>
              <b className={count === 0 ? 'zero' : undefined}>{count}</b>
            </div>
          ))}
        </div>
        <p className="count-label">결제 시도</p>
        <div className="count-grid">
          {paymentCounts.map(([status, count]) => (
            <div key={status}>
              <small>{status}</small>
              <b className={count === 0 ? 'zero' : undefined}>{count}</b>
            </div>
          ))}
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
                  <span
                    style={{ width: `${(drop.salesAmount / maxSales) * 100}%` }}
                  />
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
