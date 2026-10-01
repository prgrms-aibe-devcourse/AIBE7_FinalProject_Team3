import { useState } from 'react'
import { Link } from 'react-router-dom'
import { drops } from '../../features/drop/mockDrops'
import { stock, upcoming } from '../../features/drop/selectors'
import { sellerOrders } from '../../features/order/mockSellerOrders'

// 상단은 "지금 손대야 하는 것"만 둔다. 읽고 끝나는 합계는 아래 DROP 목록이 상품별로 보여준다.
// 발송 대기: GET /seller/dashboard/summary (GR-41) 의 orderCounts.PAID
// 임박: GET /seller/dashboard/upcoming-drops (GR-38) 를 eventType=START·END 두 번 불러 합친 건수.
// 시작인지 종료인지는 카드가 아니라 아래 DROP 행의 태그에서 구분한다.
const loadSummary = () => ({
  pendingShipment: sellerOrders.filter((o) => o.orderStatus === 'PAID').length,
  upcomingCount: drops.filter((drop) => upcoming(drop)).length,
})

export default function SellerDashboardPage() {
  // 대시보드는 스냅샷이다. 재고와 임박 시각은 계속 움직이므로 기준 시각을 밝히고 수동으로 다시 읽는다.
  const [refreshedAt, setRefreshedAt] = useState(() => new Date())
  const [onlyUpcoming, setOnlyUpcoming] = useState(false)
  const summary = loadSummary()

  const visible = onlyUpcoming
    ? drops.filter((drop) => upcoming(drop))
    : drops.slice(0, 5)

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
          onClick={() => setOnlyUpcoming((current) => !current)}
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
                ? `임박 ${visible.length}개만 표시 중`
                : `총 ${drops.length}개 · 상태와 옵션별 재고를 확인합니다.`}
            </p>
          </div>
          {onlyUpcoming ? (
            <button
              className="text-link"
              type="button"
              onClick={() => setOnlyUpcoming(false)}
            >
              전체 보기
            </button>
          ) : (
            <Link className="text-link" to="/seller/drops/new">
              새 상품 ↗
            </Link>
          )}
        </div>
        <div className="drop-list">
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
                <Link to={`/drops/${drop.id}`} aria-label={`${drop.name} 보기`}>
                  ↗
                </Link>
              </article>
            )
          })}
        </div>
      </section>
    </section>
  )
}
