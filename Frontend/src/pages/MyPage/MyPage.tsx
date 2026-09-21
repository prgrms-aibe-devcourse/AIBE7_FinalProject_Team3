import { Link, Navigate } from 'react-router-dom'
import EmptyState from '../../components/EmptyState/EmptyState'
import { drops } from '../../features/drop/mockDrops'
import type { SharedProps } from '../../types/store'
import { dateLabel } from '../../utils/date'
import { won } from '../../utils/price'

export default function MyPage({
  authenticated,
  wishes,
  orders,
  toggleWish,
  notify,
}: SharedProps) {
  if (!authenticated) return <Navigate to="/login" replace />
  const wishedDrops = drops.filter((drop) => wishes.has(drop.id))

  return (
    <section className="page-section">
      <p className="eyebrow">MY GRAB</p>
      <h1>나의 취향과 주문</h1>
      <div className="my-columns">
        <section className="panel">
          <div className="panel-heading">
            <h2>WISH</h2>
            <span>{wishedDrops.length}</span>
          </div>
          {wishedDrops.length ? (
            wishedDrops.map((drop) => (
              <div className="compact-item" key={drop.id}>
                <img src={drop.image} alt="" />
                <div>
                  <Link to={`/drops/${drop.id}`}>
                    <strong>{drop.name}</strong>
                  </Link>
                  <small>{dateLabel(drop.saleStartsAt)} 오픈</small>
                </div>
                <button
                  onClick={() => {
                    if (toggleWish(drop.id)) notify('WISH를 취소했어요.')
                  }}
                >
                  취소
                </button>
              </div>
            ))
          ) : (
            <EmptyState
              title="아직 담은 WISH가 없어요."
              link="/wish"
              label="WISH 둘러보기"
            />
          )}
        </section>
        <section className="panel">
          <div className="panel-heading">
            <h2>주문</h2>
            <span>{orders.length}</span>
          </div>
          {orders.length ? (
            orders.map((order) => (
              <div className="compact-item order" key={order.id}>
                <img src={order.drop.image} alt="" />
                <div>
                  <small>{order.id} · 결제 대기</small>
                  <strong>{order.drop.name}</strong>
                  <small>
                    {order.optionLabel} · {order.quantity}개
                  </small>
                </div>
                <b>{won(order.total)}</b>
              </div>
            ))
          ) : (
            <EmptyState
              title="아직 주문이 없어요."
              link="/grab"
              label="GRAB 둘러보기"
            />
          )}
        </section>
      </div>
    </section>
  )
}
