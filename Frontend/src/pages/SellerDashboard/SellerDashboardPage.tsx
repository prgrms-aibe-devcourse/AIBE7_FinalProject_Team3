import { Link } from 'react-router-dom'
import { drops } from '../../features/drop/mockDrops'
import { stock } from '../../features/drop/selectors'

export default function SellerDashboardPage() {
  const wishDrops = drops.filter((drop) => drop.status === 'WISH')
  const grabDrops = drops.filter((drop) => drop.status === 'GRAB')

  return (
    <section className="page-section seller-page">
      <div className="seller-title">
        <div>
          <p className="eyebrow">CREATOR WORKSPACE</p>
          <h1>다음 DROP을 준비하는 곳.</h1>
          <p>관심부터 재고까지 한눈에 확인하세요.</p>
        </div>
        <Link className="primary-button" to="/seller/drops/new">
          ＋ 새 DROP 만들기
        </Link>
      </div>
      <div className="stats-grid">
        <article>
          <span>전체 WISH</span>
          <strong>
            {drops.reduce((sum, drop) => sum + drop.wishCount, 0)}
          </strong>
          <small>공개 상품 누적</small>
        </article>
        <article>
          <span>판매 예정</span>
          <strong>
            {wishDrops.length}
            <em>개</em>
          </strong>
          <small>WISH 상태</small>
        </article>
        <article>
          <span>판매 중</span>
          <strong>
            {grabDrops.length}
            <em>개</em>
          </strong>
          <small>GRAB 상태</small>
        </article>
        <article>
          <span>가용 재고</span>
          <strong>
            {grabDrops.reduce((sum, drop) => sum + stock(drop), 0)}
            <em>개</em>
          </strong>
          <small>모든 SKU 합계</small>
        </article>
      </div>
      <section className="panel drop-table-panel">
        <div className="panel-heading">
          <div>
            <h2>DROP 관리</h2>
            <p>상태와 옵션별 재고를 확인합니다.</p>
          </div>
          <Link className="text-link" to="/seller/drops/new">
            새 상품 ↗
          </Link>
        </div>
        <div className="drop-list">
          {drops.slice(0, 5).map((drop) => (
            <article key={drop.id}>
              <img src={drop.image} alt="" />
              <div className="drop-name">
                <strong>{drop.name}</strong>
                <small>
                  {drop.optionGroups.map((group) => group.name).join(' · ') ||
                    '기본 옵션'}
                </small>
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
          ))}
        </div>
      </section>
    </section>
  )
}
