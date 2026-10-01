import { Link, Navigate, useParams } from 'react-router-dom'
import { drops } from '../../features/drop/mockDrops'
import { sellerOrders } from '../../features/order/mockSellerOrders'
import { upcoming } from '../../features/drop/selectors'
import type { Drop, Sku } from '../../types/drop'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

// 서버는 total - reserved - sold - withheld 로 가용 재고를 계산해 내려준다(ERD 1.2).
// 목 데이터는 가용 재고를 들고 있으므로 역으로 전체 수량을 복원한다.
const breakdown = (sku: Sku) => {
  const reserved = sku.reserved ?? 0
  const sold = sku.sold ?? 0
  return { available: sku.stock, reserved, sold, total: sku.stock + reserved + sold }
}

const optionLabel = (drop: Drop, sku: Sku) =>
  drop.optionGroups
    .map((group) =>
      group.values.find((value) => value.id === sku.selections[group.id])?.label,
    )
    .filter(Boolean)
    .join(' / ') || '기본 옵션'

export default function SellerDropDetailPage() {
  const { dropId } = useParams()
  const drop = drops.find((item) => item.id === Number(dropId))
  if (!drop) return <Navigate to="/seller" replace />

  const soon = upcoming(drop)
  const totals = drop.skus.reduce(
    (sum, sku) => {
      const item = breakdown(sku)
      return {
        available: sum.available + item.available,
        reserved: sum.reserved + item.reserved,
        sold: sum.sold + item.sold,
      }
    },
    { available: 0, reserved: 0, sold: 0 },
  )

  // GET /seller/orders?dropId={dropId} — 명세에 이미 있는 필터다.
  const orders = sellerOrders.filter((order) => order.dropId === drop.id)
  const salesAmount = orders.reduce((sum, order) => sum + order.totalAmount, 0)

  return (
    <section className="page-section seller-page">
      <div className="seller-title">
        <div>
          <p className="eyebrow">DROP DETAIL</p>
          <h1>{drop.name}</h1>
          <p>
            {dateLabel(drop.saleStartsAt)} ~ {dateLabel(drop.saleEndsAt)}
            {soon && ` · ${soon.label} ${soon.remain}`}
          </p>
        </div>
        <div className="detail-actions">
          <span className={`status-pill ${drop.status.toLowerCase()}`}>
            {drop.status}
          </span>
          {drop.status === 'DRAFT' && (
            <>
              <Link className="secondary-button" to="/seller/drops/new">
                수정
              </Link>
              <button className="primary-button" type="button">
                공개
              </button>
            </>
          )}
          {drop.status === 'WISH' && (
            <button className="secondary-button" type="button">
              출시 취소
            </button>
          )}
        </div>
      </div>

      <div className="stats-grid">
        {drop.status === 'DRAFT' || drop.status === 'WISH' ? (
          <article>
            <span>활성 WISH</span>
            <strong>
              {drop.wishCount}
              <em>명</em>
            </strong>
            <small>취소 제외</small>
          </article>
        ) : null}
        <article>
          <span>가용 재고</span>
          <strong>
            {totals.available}
            <em>개</em>
          </strong>
          <small>
            예약 {totals.reserved} · 판매 {totals.sold}
          </small>
        </article>
        <article>
          <span>주문</span>
          <strong>
            {orders.length}
            <em>건</em>
          </strong>
          <small>{won(salesAmount)}</small>
        </article>
      </div>

      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>옵션별 재고</h2>
            <p>가용 재고는 전체에서 예약·판매 수량을 뺀 값입니다.</p>
          </div>
        </div>
        <div className="option-stock">
          <table>
            <thead>
              <tr>
                <th>옵션</th>
                <th>단가</th>
                <th>전체</th>
                <th>가용</th>
                <th>예약</th>
                <th>판매</th>
                <th>상태</th>
              </tr>
            </thead>
            <tbody>
              {drop.skus.map((sku) => {
                const item = breakdown(sku)
                const inactive = sku.active === false
                return (
                  <tr key={sku.id}>
                    <td>{optionLabel(drop, sku)}</td>
                    <td>{won(sku.price)}</td>
                    <td>{item.total}</td>
                    <td>
                      <b>{item.available}</b>
                    </td>
                    <td>{item.reserved}</td>
                    <td>{item.sold}</td>
                    <td>
                      {inactive ? (
                        <span className="status-pill">비활성</span>
                      ) : item.available === 0 ? (
                        <span className="urgent-tag">품절</span>
                      ) : (
                        <span className="status-pill grab">판매중</span>
                      )}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </section>

      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>이 DROP의 주문</h2>
            <p>{orders.length}건</p>
          </div>
          <Link className="text-link" to="/seller/orders">
            주문 관리 ↗
          </Link>
        </div>
        {orders.length ? (
          <div className="order-list">
            {orders.slice(0, 5).map((order) => (
              <article className="order-row" key={order.orderId}>
                <div className="order-main">
                  <strong>{order.orderNumber}</strong>
                  <small>{dateLabel(order.orderedAt)}</small>
                </div>
                <b>{won(order.totalAmount)}</b>
                <div className="order-action">
                  <small>{order.orderStatus}</small>
                </div>
              </article>
            ))}
          </div>
        ) : (
          <p className="drop-list-empty">아직 주문이 없어요.</p>
        )}
      </section>

      <section className="panel">
        <div className="panel-heading">
          <div>
            <h2>상품 정보</h2>
            <p>{drop.category}</p>
          </div>
          <Link className="text-link" to={`/drops/${drop.id}`}>
            소비자 화면 ↗
          </Link>
        </div>
        <div className="drop-info">
          <img src={drop.image} alt="" />
          <dl>
            <div>
              <dt>설명</dt>
              <dd>{drop.description}</dd>
            </div>
            <div>
              <dt>옵션 그룹</dt>
              <dd>
                {drop.optionGroups.map((group) => group.name).join(' · ') ||
                  '없음'}
              </dd>
            </div>
            <div>
              <dt>배송비</dt>
              <dd>{drop.shippingFee ? won(drop.shippingFee) : '무료'}</dd>
            </div>
            <div>
              <dt>배송 안내</dt>
              <dd>{drop.shippingNotice}</dd>
            </div>
          </dl>
        </div>
      </section>
    </section>
  )
}
